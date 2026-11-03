// 로컬 부하 측정: 앱이 자주 부르는 요청(홈·알림 계획·학습 실행·대시보드)의 엔드포인트별 지연(p95)을 잰다.
// 대상은 로컬 모니터링 스택(docker-compose.monitoring.yml, 실제 MySQL)의 API. 운영 서버에 돌리지 않는다.
// 실행 방법·전후 비교 절차는 perf/README.md.
import http from 'k6/http';
import { check, fail } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:18080';
const USERS = Number(__ENV.USERS || 20);
const TZ = 'Asia/Seoul';
const KST_OFFSET_MS = 9 * 3600 * 1000;

export const options = {
  setupTimeout: '5m',
  scenarios: {
    app: {
      executor: 'constant-vus',
      vus: Number(__ENV.VUS || 20),
      duration: __ENV.DURATION || '1m',
    },
  },
  // 기준값이 아니라 결과 요약에 엔드포인트별 p95 를 띄우기 위한 항목(넉넉한 상한)
  thresholds: {
    'http_req_failed{phase:load}': ['rate<0.01'],
    'http_req_duration{name:caffeine_today}': ['p(95)<2000'],
    'http_req_duration{name:notification_plan}': ['p(95)<2000'],
    'http_req_duration{name:learning_run}': ['p(95)<2000'],
    'http_req_duration{name:learning_dashboard}': ['p(95)<2000'],
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'max'],
};

const JSON_HEADERS = { 'Content-Type': 'application/json' };

/** KST 로컬 날짜(YYYY-MM-DD). daysAgo 일 전. */
function kstDate(daysAgo) {
  return new Date(Date.now() + KST_OFFSET_MS - daysAgo * 86400 * 1000).toISOString().slice(0, 10);
}

function post(path, body, token, name) {
  const headers = token ? Object.assign({}, JSON_HEADERS, { Authorization: `Bearer ${token}` }) : JSON_HEADERS;
  return http.post(`${BASE_URL}${path}`, body === null ? null : JSON.stringify(body),
    { headers, tags: { name, phase: 'setup' } });
}

/** 사용자 1명: 가입·로그인 + 최근 14일 하루 3잔 + 최근 7박 수면. 서버 Clock 이 실제 시각이므로 날짜도 실제 기준. */
function seedUser(i) {
  const email = `k6-${Date.now()}-${i}@example.com`;
  const password = 'password1!';
  if (post('/api/auth/signup', { email, password, nickname: `부하${i}` }, null, 'signup').status !== 201) {
    fail(`signup ${email}`);
  }
  const login = post('/api/auth/login', { email, password }, null, 'login');
  if (login.status !== 200) {
    fail(`login ${email}`);
  }
  const token = login.json('data.accessToken');

  for (let d = 14; d >= 1; d--) {
    for (const hm of ['08:00', '13:00', '16:00']) {
      post('/api/caffeine-records',
        { amount: 100, drinkName: '아메리카노', timestamp: `${kstDate(d)}T${hm}:00+09:00` }, token, 'seed_record');
    }
  }
  for (let d = 6; d >= 0; d--) {
    const morning = kstDate(d);
    const eve = kstDate(d + 1);
    const id = `${email}-${morning}`;
    const samples = [
      { clientUuid: `${id}-bed`, start: `${eve}T23:00:00+09:00`, end: `${eve}T23:20:00+09:00`, hkValue: 0 },
      { clientUuid: `${id}-c1`, start: `${eve}T23:20:00+09:00`, end: `${morning}T03:00:00+09:00`, hkValue: 3 },
      { clientUuid: `${id}-d`, start: `${morning}T03:00:00+09:00`, end: `${morning}T04:00:00+09:00`, hkValue: 4 },
      { clientUuid: `${id}-r`, start: `${morning}T04:00:00+09:00`, end: `${morning}T05:00:00+09:00`, hkValue: 5 },
      { clientUuid: `${id}-c2`, start: `${morning}T05:00:00+09:00`, end: `${morning}T07:00:00+09:00`, hkValue: 3 },
    ];
    post('/api/sleep/samples', { samples }, token, 'seed_sleep');
  }
  return token;
}

export function setup() {
  const tokens = [];
  for (let i = 0; i < USERS; i++) {
    tokens.push(seedUser(i));
  }
  return { tokens };
}

/** 앱을 연 사용자 1명이 부르는 요청 묶음. 첫 학습 실행만 7박을 학습하고, 이후는 재실행(학습할 밤 없음)이다. */
export default function (data) {
  const token = data.tokens[(__VU - 1) % data.tokens.length];
  const params = (name) => ({ headers: { Authorization: `Bearer ${token}` }, tags: { name, phase: 'load' } });
  const now = encodeURIComponent(new Date().toISOString());
  const q = `now=${now}&tz=${encodeURIComponent(TZ)}`;

  const responses = [
    http.get(`${BASE_URL}/api/caffeine/today?${q}`, params('caffeine_today')),
    http.get(`${BASE_URL}/api/notifications/plan?${q}`, params('notification_plan')),
    http.post(`${BASE_URL}/api/learning/run?tz=${encodeURIComponent(TZ)}`, null, params('learning_run')),
    http.get(`${BASE_URL}/api/learning/dashboard`, params('learning_dashboard')),
  ];
  for (const res of responses) {
    check(res, { '200': (r) => r.status === 200 });
  }
}
