# 성능 측정

두 가지를 잰다. 앞의 것은 빌드가 자동으로 지키고, 뒤의 것은 필요할 때 PC에서 직접 돌린다.

| 무엇 | 어디서 | 도구 |
|---|---|---|
| 요청당 SQL 실행 횟수 | `./gradlew build` (H2) | `QueryCountIT` — 엔드포인트별 예산을 넘으면 빌드 실패 |
| 엔드포인트별 지연 시간(p95) | PC의 로컬 모니터링 스택 (실제 MySQL) | `perf/k6/learning-load.js` + Grafana |

SQL 수는 DB 종류와 무관해서 H2에서 세어도 MySQL과 같다. 지연 시간은 DB 왕복 시간에 따라 달라서, 실제 MySQL이 있는 환경에서만 의미가 있다.
**운영 서버에는 부하를 걸지 않는다.**

## 지연 시간 측정 (k6)

1. 로컬 모니터링 스택을 띄운다 (Docker 필요).

   ```bash
   docker compose -f docker-compose.monitoring.yml up --build -d
   ```

2. k6를 실행한다. 설치하지 않고 Docker 이미지로 돌릴 수 있다.

   ```bash
   # macOS·Windows (Docker Desktop)
   docker run --rm -i -e BASE_URL=http://host.docker.internal:18080 grafana/k6:0.54.0 run - < perf/k6/learning-load.js
   # Linux
   docker run --rm -i --network host grafana/k6:0.54.0 run - < perf/k6/learning-load.js
   ```

   환경변수로 규모를 바꿀 수 있다: `USERS`(준비할 사용자 수, 기본 20) · `VUS`(동시 사용자, 기본 20) · `DURATION`(기본 `1m`).
   준비 단계에서 사용자마다 최근 14일 기록(하루 3잔)과 최근 7박 수면을 만든다. 첫 학습 실행만 7박을 학습하고 이후는 재실행이다.

3. 결과 요약의 `http_req_duration{name:learning_run}` 같은 줄에서 엔드포인트별 p95를 읽는다.
   같은 시간대를 Grafana(http://localhost:13000, admin / local-grafana-pw) 대시보드의 p95 패널로도 볼 수 있다.

4. 끝나면 내린다(볼륨이 없어 데이터는 남지 않는다).

   ```bash
   docker compose -f docker-compose.monitoring.yml down
   ```

### 변경 전후 비교

같은 PC에서 같은 설정으로 두 커밋을 차례로 잰다. 스택을 내렸다 다시 올리면 DB가 비어 있으므로 조건이 같아진다.

```bash
git checkout <변경 전 커밋>
docker compose -f docker-compose.monitoring.yml up --build -d
docker run ... run - < perf/k6/learning-load.js      # 결과 저장
docker compose -f docker-compose.monitoring.yml down

git checkout <변경 후 커밋>
# 위와 같은 순서로 반복
```

스크립트 자체는 두 커밋에서 같아야 한다. 변경 전 커밋에 이 스크립트가 없으면 변경 후 커밋의 파일을 레포 밖에 복사해 두고 그 파일로 돌린다.

## SQL 예산 (`QueryCountIT`)

| 요청 | 예산 |
|---|---|
| `POST /api/learning/run` (7박 처음 학습) | 13 — 조회 5회(잠금·설정·수면·학습 날짜·카페인) + 관측 INSERT 7 + 설정 UPDATE 1 |
| `POST /api/learning/run` (재실행) | 4 |
| 그 외 주요 요청 | 1~4 (테스트 파일의 `budgets()` 참조) |

예산을 넘겨 빌드가 실패하면 예산부터 올리지 말고, 반복문 안에서 조회하게 된 곳이 없는지 먼저 확인한다.
학습 실행은 이전에 밤마다 따로 조회해서 7박 기준 31회(재실행 16회)였다.
