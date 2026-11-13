# API 명세

카페인 트래커 서버 REST API. (서버 구현 기준, 2026-09-25)

- 기계용 명세: [`openapi.yaml`](openapi.yaml) — OpenAPI 3.0.3. Swagger Editor·Redoc·Postman에서 열 수 있다.
- 화면별 기능 목록은 [`features.md`](../features.md), DB 스키마는 [`db-schema.md`](../db/db-schema.md).
- 이 문서·`openapi.yaml`·서버 코드의 **엔드포인트 목록, DTO 필드, 에러 코드**는 `ApiDocsConsistencyIT`가 대조한다.
  설명·제약 문구는 검사하지 않으므로 사람이 맞춘다.

## 공통 규칙

### 필드 표 표기

아래 필드 표는 테스트가 DTO record 와 대조하므로 표기를 지킨다.

| 표기 | 뜻 |
|---|---|
| `field` | 그 DTO 의 필드 |
| `parent.child` | 중첩 객체의 필드 |
| `parent[].child` | 배열 원소의 필드 (`parent[]`와 `parent.`는 같게 취급) |
| `a` / `b` | 두 필드를 한 행에. **양쪽 다 전체 경로로 적는다** |
| `*Suffix[].x` | 이름이 `Suffix`로 끝나는 최상위 필드 전부 (예: `*Histogram[]` = `residualHistogram[]`·`solHistogram[]`) |
| 타입 칸의 `{ x, y }` | 그 필드의 자식을 인라인으로 적은 것 (별도 행 불필요) |

- 자체 필드 표가 있는 DTO(예: `CaffeineRecordResponse`)를 참조하는 필드는 그 행 하나만 적고 내부를 펼치지 않는다.
- 표를 통째로 생략하려면 앵커 줄에 **가리키는 DTO를 백틱으로 적고 "…와 동일"** 이라고 쓴다
  (예: **요청 바디** `UpdateCaffeineRecordRequest` — 추가(`CreateCaffeineRecordRequest`)와 동일한 필드·제약).
  테스트가 그 참조를 따라가 필드 이름·순서와 검증 애노테이션까지 대조한다.
- 같은 DTO를 다른 절에서 다시 앵커할 때는 그 DTO의 표가 이미 있으므로 "(조회와 동일)"처럼 참조 없이 써도 된다.
  **표도 참조도 없으면 빌드가 실패한다** — 필드 표를 빠뜨린 채로는 넘어갈 수 없다.

### 기본

| 항목 | 값 |
|---|---|
| Base URL | 로컬 `http://localhost:8080`. 운영은 EC2 주소(배포 환경에 따름) |
| 인증 | `Authorization: Bearer <accessToken>` 헤더. 공개 API는 회원가입·로그인·토큰 재발급 3개뿐(그 외 인증 없는 경로는 [운영](#운영)용 actuator) |
| Content-Type | 요청·응답 모두 `application/json` (UTF-8) |
| 토큰 | access 토큰(JWT) 1시간, refresh 토큰(JWT) 14일. 서버는 토큰을 저장하지 않는다. 재발급 시 새 쌍이 나오며 기존 refresh도 만료 전까지 유효하다(회전 없음). 로그아웃은 그 사용자의 refresh를 전부 무효화한다. refresh 토큰으로 일반 API를 호출하면 401 |

### 날짜·시간·타임존

- 요청의 시각은 ISO-8601 + 오프셋: `2026-06-01T14:00:00+09:00`. 응답의 시각은 UTC `Z`: `2026-06-01T05:00:00Z`.
- 날짜는 `YYYY-MM-DD`(로컬 날짜). `tz`는 IANA 타임존 이름(`Asia/Seoul`)이며 잘못된 값은 400 `INVALID_PARAMETER`.
- 서버는 시각을 UTC로 저장하고, "오늘 경계·취침시각·수면 윈도우" 같은 로컬 달력 연산만 요청의 `tz`로 계산한다.
  - 오늘 = `tz` 기준 그날 05:00부터 24시간. 00:00~05:00에는 그날 05:00이 아직 오지 않았으므로 오늘 기록·합계가 비어 있다
  - 잔량·마감시각은 오늘 경계와 무관하게 `now` 이전 24시간부터의 기록으로 계산한다(학습과 같은 룩백)
  - 다음 취침시각 = 오늘의 설정 `bedtimeHour:bedtimeMinute`, 이미 지났으면 내일
  - 수면 윈도우(`date` 기준) = 전날 18:00 ~ 당일 12:00

### 응답 봉투

모든 응답은 아래 봉투다. 키 4개는 값이 없어도 항상 존재한다(`null`).

| 키 | 성공 | 실패 |
|---|---|---|
| `success` | `true` | `false` |
| `data` | 페이로드(객체·배열). 바디 없는 성공은 `null` | `null` |
| `error` | `null` | `{ "code": "...", "fieldErrors": [...] }` — `fieldErrors`는 `VALIDATION_FAILED`에만 |
| `message` | `null` | 사용자에게 보여줄 문장 |

```json
{ "success": true,  "data": { "userId": 1, "email": "user@example.com" }, "error": null, "message": null }
{ "success": false, "data": null, "error": { "code": "INVALID_CREDENTIALS" }, "message": "이메일 또는 비밀번호가 올바르지 않습니다." }
{ "success": false, "data": null, "error": { "code": "VALIDATION_FAILED", "fieldErrors": [ { "field": "email", "message": "must not be blank" } ] }, "message": "입력값이 올바르지 않습니다." }
```

HTTP 상태 코드는 그대로 의미를 가진다(201 생성, 400/401/404/409 등). 바디 없는 성공(로그아웃·삭제)도 204가 아니라 `200` + `data: null`이다.
`fieldErrors[].message`는 검증 라이브러리의 기본 문구(영문)이며 화면 표시용이 아니다.

### 에러 코드

`error.code`와 HTTP 상태의 단일 출처는 `global/error/ErrorCode.java`.

| 코드 | HTTP | message | 발생 조건 |
|---|---|---|---|
| `EMAIL_ALREADY_EXISTS` | 409 | 이미 사용 중인 이메일입니다. | 회원가입 이메일 중복 |
| `INVALID_CREDENTIALS` | 401 | 이메일 또는 비밀번호가 올바르지 않습니다. | 로그인 실패(없는 이메일 포함, 구분하지 않음) |
| `INVALID_REFRESH_TOKEN` | 401 | RefreshToken 이 유효하지 않습니다. | 토큰 재발급: 서명 불일치·만료·access 토큰 사용·사용자 없음·로그아웃으로 무효화됨 |
| `TOO_MANY_LOGIN_ATTEMPTS` | 429 | 로그인 시도가 너무 많습니다. 15분 후 다시 시도해 주세요. | 같은 이메일 로그인 실패 한도 초과(로그인 절 참조) |
| `UNAUTHORIZED` | 401 | 인증이 필요합니다. | Bearer 토큰 없음·만료·위조(시큐리티 단계) |
| `FORBIDDEN` | 403 | 접근 권한이 없습니다. | 권한 부족(현재 발생 경로 없음, 예비) |
| `VALIDATION_FAILED` | 400 | 입력값이 올바르지 않습니다. | 바디 필드 검증 실패. `error.fieldErrors` 포함 |
| `MALFORMED_JSON` | 400 | 잘못된 요청 형식입니다. | JSON 파싱 실패·바디 필드 타입 불일치 |
| `INVALID_PARAMETER` | 400 | 요청 파라미터가 올바르지 않습니다. | 쿼리·경로 파라미터 누락·형식 오류(잘못된 `tz`·`now`·`date`·`from`·`to`, 숫자가 아닌 `id`), 인사이트 기간 검증 실패(`from > to`, 31일 초과, `to`가 오늘 이후) |
| `NOT_FOUND` | 404 | 요청한 리소스를 찾을 수 없습니다. | 존재하지 않는 경로 |
| `METHOD_NOT_ALLOWED` | 405 | 허용되지 않은 HTTP 메서드입니다. | 경로는 있으나 메서드 미지원 |
| `UNSUPPORTED_MEDIA_TYPE` | 415 | 지원하지 않는 Content-Type 입니다. | JSON이 아닌 바디 |
| `CAFFEINE_RECORD_NOT_FOUND` | 404 | 카페인 기록을 찾을 수 없습니다. | 본인 기록이 아니거나 없는 `id` |
| `INTERNAL_ERROR` | 500 | 서버 오류가 발생했습니다. | 처리되지 않은 예외 |

그 외 프레임워크가 정하는 상태(406 등)는 `HTTP_{status}` 코드와 표준 reason phrase로 내려간다.

**공통 에러(엔드포인트 상세에서는 반복하지 않는다)**
- 인증이 필요한 엔드포인트 전부: 401 `UNAUTHORIZED`
- JSON 바디를 받는 엔드포인트 전부: 400 `VALIDATION_FAILED`, 400 `MALFORMED_JSON`, 415 `UNSUPPORTED_MEDIA_TYPE`
- 쿼리·경로 파라미터를 받는 엔드포인트 전부: 400 `INVALID_PARAMETER`

## 엔드포인트 목록

| 화면 | 기능 | 메서드 | 경로 | 인증 |
|---|---|---|---|---|
| 회원가입 화면 | 회원가입 | `POST` | `/api/auth/signup` | 불필요 |
| 로그인 화면 | 로그인 | `POST` | `/api/auth/login` | 불필요 |
| (화면 없음 — 자동) | 토큰 재발급 | `POST` | `/api/auth/refresh` | 불필요 |
| 설정 화면 | 로그아웃 | `POST` | `/api/auth/logout` | Bearer |
| 설정 화면 | 내 정보 조회 | `GET` | `/api/me` | Bearer |
| 설정 화면 | 설정 조회 | `GET` | `/api/settings` | Bearer |
| 설정 화면 | 설정 변경 | `PUT` | `/api/settings` | Bearer |
| 기록 입력 화면 | 카페인 기록 추가 | `POST` | `/api/caffeine-records` | Bearer |
| 기록 입력 화면 | 카페인 기록 수정 | `PUT` | `/api/caffeine-records/{id}` | Bearer |
| 홈 화면 | 카페인 기록 삭제 | `DELETE` | `/api/caffeine-records/{id}` | Bearer |
| 홈 화면 | 오늘 기록 조회 | `GET` | `/api/caffeine-records` | Bearer |
| 홈 화면 | 오늘의 카페인 현황 | `GET` | `/api/caffeine/today` | Bearer |
| (화면 없음 — 자동 동기화) | 수면 샘플 업로드 | `POST` | `/api/sleep/samples` | Bearer |
| 수면 화면 | 수면 요약 조회 | `GET` | `/api/sleep/summary` | Bearer |
| (화면 없음 — 자동 실행) | 반감기 학습 실행 | `POST` | `/api/learning/run` | Bearer |
| 학습 대시보드 화면 | 학습 관측 이력 조회 | `GET` | `/api/learning/observations` | Bearer |
| 학습 대시보드 화면 | 학습 대시보드 | `GET` | `/api/learning/dashboard` | Bearer |
| (화면 없음 — 로컬 알림) | 알림 계획 조회 | `GET` | `/api/notifications/plan` | Bearer |
| 인사이트 화면 | 기간별 섭취 통계 | `GET` | `/api/insight/intake-stats` | Bearer |
| 인사이트 화면 | 카페인·수면 상관 리포트 | `GET` | `/api/insight/caffeine-sleep` | Bearer |

## 인증

### POST /api/auth/signup — 회원가입

이메일·비밀번호·닉네임으로 계정을 만든다. 인증 불필요.

**요청 바디** `SignupRequest`

| 필드 | 타입 | 필수 | 제약 | 설명 |
|---|---|---|---|---|
| `email` | string | ✔ | 이메일 형식, 최대 255자 | 로그인 ID. 중복 불가 |
| `password` | string | ✔ | 8~72자 | 평문 전송, 서버가 BCrypt로 저장 |
| `nickname` | string | ✔ | 2~20자 | |

**응답 201** `data: SignupResponse`

| 필드 | 타입 | 설명 |
|---|---|---|
| `userId` | integer | 생성된 사용자 id |
| `email` | string | |
| `nickname` | string | |

```json
{ "success": true, "data": { "userId": 1, "email": "user@example.com", "nickname": "테스터" }, "error": null, "message": null }
```

**에러**: 409 `EMAIL_ALREADY_EXISTS` (같은 이메일로 동시에 가입하면 하나만 201, 나머지는 이 409)

가입과 같은 트랜잭션에서 기본 설정 행도 만든다.

### POST /api/auth/login — 로그인

이메일·비밀번호를 검증하고 access/refresh 토큰 쌍을 발급한다. 인증 불필요.

**요청 바디** `LoginRequest`

| 필드 | 타입 | 필수 | 제약 | 설명 |
|---|---|---|---|---|
| `email` | string | ✔ | 이메일 형식 | |
| `password` | string | ✔ | | |

**응답 200** `data: TokenResponse`

| 필드 | 타입 | 설명 |
|---|---|---|
| `accessToken` | string | JWT. `Authorization: Bearer` 헤더에 사용 |
| `refreshToken` | string | JWT. 재발급 전용(일반 API 호출 불가) |
| `tokenType` | string | 항상 `Bearer` |
| `expiresIn` | integer | access 토큰 유효 시간(초). 3600 |
| `refreshExpiresIn` | integer | refresh 토큰 유효 시간(초). 1209600 |

```json
{ "success": true, "data": { "accessToken": "eyJhbGciOiJIUzI1NiJ9...", "refreshToken": "eyJhbGciOiJIUzI1NiJ9...", "tokenType": "Bearer", "expiresIn": 3600, "refreshExpiresIn": 1209600 }, "error": null, "message": null }
```

**에러**: 401 `INVALID_CREDENTIALS`, 429 `TOO_MANY_LOGIN_ATTEMPTS`

**시도 제한** (이메일 단위, 대소문자 무시)
- 첫 시도부터 15분 안에 5번 실패하면, 6번째부터 그 15분이 끝날 때까지 429. 막힌 동안의 시도는 해제 시각을 늦추지 않는다.
- 막힌 동안에는 맞는 비밀번호도 429다(429/200 차이로 비밀번호를 맞혔는지 알 수 없게).
- 없는 이메일도 같은 규칙을 적용하고, 응답 시간도 있는 이메일과 같게 맞춘다(가입 여부를 드러내지 않게).
- 로그인에 성공하면 그 이메일의 실패 횟수를 지운다. 기록은 서버 메모리에 있어 재시작하면 초기화된다.

### POST /api/auth/refresh — 토큰 재발급

refresh 토큰으로 새 access/refresh 쌍을 발급한다. 회전하지 않으므로 기존 refresh도 만료·로그아웃 전까지 유효하다. 인증 불필요.

**요청 바디** `RefreshTokenRequest`

| 필드 | 타입 | 필수 | 제약 | 설명 |
|---|---|---|---|---|
| `refreshToken` | string | ✔ | | 로그인·재발급으로 받은 refresh 토큰 |

**응답 200** `data: TokenResponse` (로그인과 동일)

**에러**: 401 `INVALID_REFRESH_TOKEN` — 서명 불일치·만료·access 토큰 사용·사용자 없음·로그아웃으로 무효화됨

### POST /api/auth/logout — 로그아웃

토큰의 사용자에 속한 refresh 토큰을 전부 무효화한다(모든 기기 로그아웃, `users.token_version` +1). access 토큰은 만료 전까지 유효하므로 앱이 폐기한다.

**요청 바디** `LogoutRequest`

| 필드 | 타입 | 필수 | 제약 | 설명 |
|---|---|---|---|---|
| `refreshToken` | string | ✔ | | 클라이언트 계약 호환용. 서버는 값과 무관하게 토큰의 사용자 기준으로 무효화 |

**응답 200** `data: null`

```json
{ "success": true, "data": null, "error": null, "message": null }
```

## 사용자

### GET /api/me — 내 정보 조회

access 토큰의 클레임을 그대로 돌려준다. DB 조회 없음.

**응답 200** `data: MeResponse`

| 필드 | 타입 | 설명 |
|---|---|---|
| `userId` | integer | |
| `email` | string | |

```json
{ "success": true, "data": { "userId": 1, "email": "user@example.com" }, "error": null, "message": null }
```

## 설정

### GET /api/settings — 설정 조회

유저 설정과 학습 상태를 조회한다. 설정 행은 가입 시 기본값으로 만들어지며, 그 이전에 가입해 행이 없는 계정은 이 조회에서 생성한다.

**응답 200** `data: SettingsResponse`

| 필드 | 타입 | 설명 |
|---|---|---|
| `halfLife` | number | 수동 설정 반감기(시간). 기본 5.0 |
| `condition` | integer | 건강 상태 0=NONE, 1=SMOKER, 2=ORAL_CONTRACEPTIVE, 3=PREGNANT. 반감기 배수 1.0/0.5/2.0/2.5 |
| `bedtimeHour` | integer | 취침 시각(로컬) 시. 기본 23 |
| `bedtimeMinute` | integer | 취침 시각(로컬) 분. 기본 0 |
| `referenceDoseMg` | integer | 기준 용량(mg). 마감시각 계산에 사용. 기본 75 |
| `isLearningEnabled` | boolean | 자동 학습 여부. 기본 true |
| `learnedMean` | number | 학습된 반감기 평균(시간). 기본 5.0 |
| `learnedVariance` | number | 학습된 반감기 분산(시간²). 기본 2.25 |
| `lastLearnedDate` | string(date) · null | 마지막 학습 날짜(로컬). 학습 전이면 null |
| `inferredBaseHalfLife` | number | 학습 ON이면 `learnedMean`, OFF면 `halfLife` |
| `effectiveHalfLifeHours` | number | `inferredBaseHalfLife × 배수`. 잔량·마감시각 연산에 실제로 쓰는 값 |
| `notifications` | object | 알림 종류별 on/off. 알림 계획 조회에서 꺼진 종류는 생략된다 |
| `notifications.cutoff` | boolean | 섭취 마감 알림. 기본 true |
| `notifications.bedtimeResidual` | boolean | 취침 잔량 예고. 기본 true |
| `notifications.recordReminder` | boolean | 기록 리마인더. 기본 true |
| `notifications.cafeNearby` | boolean | 카페 근처 알림. 기본 false (동 선택·'항상' 위치 권한이 있어야 동작) |

```json
{ "success": true, "data": { "halfLife": 5.0, "condition": 0, "bedtimeHour": 23, "bedtimeMinute": 0, "referenceDoseMg": 75, "isLearningEnabled": true, "learnedMean": 5.0, "learnedVariance": 2.25, "lastLearnedDate": null, "inferredBaseHalfLife": 5.0, "effectiveHalfLifeHours": 5.0, "notifications": { "cutoff": true, "bedtimeResidual": true, "recordReminder": true, "cafeNearby": false } }, "error": null, "message": null }
```

### PUT /api/settings — 설정 변경

설정 전체를 교체한다. `halfLife`가 이전 값과 다르면 학습 prior가 리셋된다(`learnedMean=halfLife`, `learnedVariance=2.25`, `lastLearnedDate=null`).
같은 사용자의 학습 실행·수면 업로드가 진행 중이면 그것이 끝난 뒤에 반영된다(서로의 갱신을 덮어쓰지 않는다).

**요청 바디** `UpdateSettingsRequest`

| 필드 | 타입 | 필수 | 제약 | 설명 |
|---|---|---|---|---|
| `halfLife` | number | ✔ | 3.0~7.0 | |
| `condition` | integer | ✔ | 0~3 | |
| `bedtimeHour` | integer | ✔ | 0~23 | |
| `bedtimeMinute` | integer | ✔ | 0~59 | |
| `referenceDoseMg` | integer | ✔ | 1~1000 | |
| `isLearningEnabled` | boolean | ✔ | | |
| `notifications` | object | ✔ | | 알림 종류별 on/off. `cafeNearby` 외 세 필드는 필수 |
| `notifications.cutoff` | boolean | ✔ | | |
| `notifications.bedtimeResidual` | boolean | ✔ | | |
| `notifications.recordReminder` | boolean | ✔ | | |
| `notifications.cafeNearby` | boolean | | | 생략하면 기존 값 유지(이 필드를 모르는 이전 버전 앱 호환) |
| `learnedMean` | number | | 0 초과 | `learnedVariance`와 함께 보내면 학습 상태를 1회 시드(기존 기기의 학습값 이전용). 둘 중 하나만 있으면 무시 |
| `learnedVariance` | number | | 0 초과 | 위와 같음 |
| `lastLearnedDate` | string(date) | | | 시드 시 함께 저장. 생략하면 null |

**응답 200** `data: SettingsResponse` (조회와 동일)

## 카페인

`tz`·`now`를 받는 엔드포인트는 "오늘"을 `tz` 기준 05:00 경계로 계산한다.

### POST /api/caffeine-records — 카페인 기록 추가

**요청 바디** `CreateCaffeineRecordRequest`

| 필드 | 타입 | 필수 | 제약 | 설명 |
|---|---|---|---|---|
| `amount` | integer | ✔ | 1~10000 | 카페인량(mg) |
| `drinkName` | string | ✔ | 최대 100자 | 음료명 |
| `timestamp` | string(date-time) | ✔ | 오프셋 포함 | 섭취 시각 |

**응답 201** `data: CaffeineRecordResponse`

| 필드 | 타입 | 설명 |
|---|---|---|
| `id` | integer | 기록 id |
| `amount` | integer | mg |
| `drinkName` | string | |
| `timestamp` | string(date-time) | UTC |

```json
{ "success": true, "data": { "id": 10, "amount": 100, "drinkName": "아메리카노", "timestamp": "2026-06-01T00:00:00Z" }, "error": null, "message": null }
```

### PUT /api/caffeine-records/{id} — 카페인 기록 수정

본인 기록만 수정할 수 있다. 세 필드를 전부 보내는 전체 교체.

**경로** `id` integer — 기록 id

**요청 바디** `UpdateCaffeineRecordRequest` — 추가(`CreateCaffeineRecordRequest`)와 동일한 필드·제약

**응답 200** `data: CaffeineRecordResponse`

**에러**: 404 `CAFFEINE_RECORD_NOT_FOUND`

### DELETE /api/caffeine-records/{id} — 카페인 기록 삭제

**경로** `id` integer — 기록 id

**응답 200** `data: null`

**에러**: 404 `CAFFEINE_RECORD_NOT_FOUND`

### GET /api/caffeine-records — 오늘 기록 조회

`now`가 속한 오늘(05:00 경계)의 기록을 섭취 시각 오름차순으로 돌려준다.

**쿼리**

| 파라미터 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `now` | string(date-time) | ✔ | 기기 현재 시각, 오프셋 포함 |
| `tz` | string | ✔ | IANA 타임존 |

**응답 200** `data: CaffeineRecordResponse[]`

```json
{ "success": true, "data": [ { "id": 10, "amount": 100, "drinkName": "아메리카노", "timestamp": "2026-06-01T00:00:00Z" } ], "error": null, "message": null }
```

### GET /api/caffeine/today — 오늘의 카페인 현황

홈 화면이 필요로 하는 값을 서버가 설정을 읽어 한 번에 계산한다. 잔량 계산에는 `effectiveHalfLifeHours`를 쓴다.

**쿼리**: 오늘 기록 조회와 동일(`now`, `tz`)

**응답 200** `data: CaffeineTodayResponse`

| 필드 | 타입 | 설명 |
|---|---|---|
| `now` | string(date-time) | 요청 `now`(UTC) |
| `bedtime` | string(date-time) | 다음 취침시각(UTC) |
| `records` | CaffeineRecordResponse[] | 오늘 기록 |
| `todayTotal` | integer | 오늘 섭취 합(mg) |
| `overDailyLimit` | boolean | `todayTotal > 400` |
| `chart` | DataPointResponse[] | 잔량 시계열. 오늘 05:00부터 24시간, 15분 간격 + 섭취 직전·직후 점. 최근 24시간 기록 없으면 `[]` |
| `chart[].time` | string(date-time) | UTC |
| `chart[].concentrationMg` | number | 그 시각 체내 잔량(mg) |
| `currentResidual` | number | `now` 시점 잔량(mg). 최근 24시간 기록 없으면 0 |
| `predictedAtBedtime` | number | 취침시각 예상 잔량(mg). 최근 24시간 기록 없으면 0 |
| `cutoff` | CutoffResponse | 기준 용량(`referenceDoseMg`)을 취침 시 잔량 50mg 이내로 마실 수 있는 가장 늦은 시각 |
| `cutoff.status` | string | `SAFE_ANYTIME`(언제든 가능) · `CUTOFF`(마감시각 있음) · `ALREADY_EXCEEDED`(이미 초과) |
| `cutoff.cutoff` | string(date-time) · null | `CUTOFF`일 때만. 과거일 수 있다 |
| `cutoff.existingAtBedtime` | number · null | `ALREADY_EXCEEDED`일 때만. 취침 시 기존 잔량(mg) |
| `effectiveHalfLifeHours` | number | 계산에 쓴 유효 반감기 |
| `referenceDoseMg` | integer | 계산에 쓴 기준 용량 |
| `carryoverRecords` | CaffeineRecordResponse[] | 오늘 경계 이전이지만 잔량 계산에 포함된 최근 24시간 기록. 합계·`records`에는 포함하지 않는다 |

```json
{ "success": true, "data": {
  "now": "2026-06-01T05:00:00Z", "bedtime": "2026-06-01T14:00:00Z",
  "records": [ { "id": 10, "amount": 100, "drinkName": "아메리카노", "timestamp": "2026-06-01T00:00:00Z" } ],
  "todayTotal": 100, "overDailyLimit": false,
  "chart": [ { "time": "2026-05-31T20:00:00Z", "concentrationMg": 0.0 }, { "time": "2026-06-01T00:00:00Z", "concentrationMg": 100.0 } ],
  "currentResidual": 50.0, "predictedAtBedtime": 14.36,
  "cutoff": { "status": "CUTOFF", "cutoff": "2026-06-01T08:38:00Z", "existingAtBedtime": null },
  "effectiveHalfLifeHours": 5.0, "referenceDoseMg": 75
}, "error": null, "message": null }
```

## 수면

`hkValue`는 HealthKit `HKCategoryValueSleepAnalysis` rawValue: 0=inBed, 1=asleepUnspecified, 2=awake, 3=asleepCore, 4=asleepDeep, 5=asleepREM.

### POST /api/sleep/samples — 수면 샘플 업로드

기기에서 읽은 원시 샘플을 배치로 올린다. `clientUuid`가 이미 저장돼 있으면 건너뛰므로 같은 배치를 다시 보내도 안전하다(멱등). 같은 배치가 동시에 와도 샘플은 한 번만 저장되고 둘 다 200이다(`inserted` 합계가 실제 저장 수). 배치 안의 중복 `clientUuid`는 첫 항목만 저장.

**요청 바디** `UploadSleepSamplesRequest`

| 필드 | 타입 | 필수 | 제약 | 설명 |
|---|---|---|---|---|
| `samples` | SampleItem[] | ✔ | 1개 이상 | |
| `samples[].clientUuid` | string | ✔ | 최대 64자 | HealthKit 샘플 UUID. 멱등 키 |
| `samples[].start` | string(date-time) | ✔ | 오프셋 포함 | |
| `samples[].end` | string(date-time) | ✔ | 오프셋 포함, `start`보다 늦어야 함 | 어기면 400 `VALIDATION_FAILED`(`field: samples[i].endAfterStart`), 배치 전체 미저장 |
| `samples[].hkValue` | integer | ✔ | 0~5 | 수면 단계 rawValue |

**응답 200** `data: UploadSleepSamplesResponse`

| 필드 | 타입 | 설명 |
|---|---|---|
| `received` | integer | 요청에 담긴 샘플 수 |
| `inserted` | integer | 새로 저장된 수 |

```json
{ "success": true, "data": { "received": 2, "inserted": 2 }, "error": null, "message": null }
```

### GET /api/sleep/summary — 수면 요약 조회

`date`(잠에서 깬 아침 날짜) 기준 수면 윈도우(전날 18:00 ~ 당일 12:00)의 원시 샘플을 서버가 병합해 요약한다. 앱은 계산하지 않는다.

**쿼리**

| 파라미터 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `date` | string(date) | ✔ | 아침 날짜(로컬) |
| `tz` | string | ✔ | IANA 타임존 |

**응답 200** `data: SleepSummaryResponse`

| 필드 | 타입 | 설명 |
|---|---|---|
| `date` | string(date) | 요청 `date` |
| `hasData` | boolean | 윈도우에 샘플이 있는지. false면 나머지는 0·`[]`·null |
| `bedtimeStart` | string(date-time) · null | 원시 샘플의 최초 inBed 시작(UTC) |
| `records` | RecordItem[] | 병합된 수면 구간 |
| `records[].start` / `records[].end` | string(date-time) | UTC |
| `records[].hkValue` | integer | 단계 rawValue |
| `totalSleepSeconds` | number | 수면 단계(unspecified·core·deep·rem) 합(초) |
| `timeInBedSeconds` | number | 병합 구간 전체 스팬(최초 시작~최후 끝, 초) |
| `sleepEfficiency` | number | `totalSleep / timeInBed`, 0~1 비율 |
| `sleepOnsetLatencySeconds` | number · null | 수면 잠복기(SOL): inBed 시작 → 최초 수면 단계 시작(초). 계산 불가면 null |
| `stageBreakdown` | StageItem[] | 지속시간 > 0인 단계만, 순서 inBed→awake→core→deep→rem→unspecified |
| `stageBreakdown[].hkValue` | integer | |
| `stageBreakdown[].durationSeconds` | number | |

```json
{ "success": true, "data": {
  "date": "2026-06-01", "hasData": true, "bedtimeStart": "2026-05-31T14:00:00Z",
  "records": [ { "start": "2026-05-31T14:00:00Z", "end": "2026-05-31T14:30:00Z", "hkValue": 0 }, { "start": "2026-05-31T14:30:00Z", "end": "2026-05-31T20:00:00Z", "hkValue": 3 } ],
  "totalSleepSeconds": 19800.0, "timeInBedSeconds": 21600.0, "sleepEfficiency": 0.9167, "sleepOnsetLatencySeconds": 1800.0,
  "stageBreakdown": [ { "hkValue": 0, "durationSeconds": 1800.0 }, { "hkValue": 3, "durationSeconds": 19800.0 } ]
}, "error": null, "message": null }
```

## 학습

카페인 취침 잔량과 수면 잠복기(SOL)로 개인 반감기를 베이지안 학습한다. 관측 1건 = 밤 1개이며, 관측 날짜는 그 밤이 끝나는 아침의 로컬 날짜다.

### POST /api/learning/run — 반감기 학습 실행

오늘(`tz` 기준) 포함 최근 7일 중 수면 데이터가 있고 아직 학습하지 않은 밤을 오래된 순으로 학습하고 설정의 학습 상태에 반영한다. 같은 날짜는 1회만 학습된다. 같은 사용자의 실행이 겹치면 차례로 처리되어, 뒤의 실행은 `ALREADY_LEARNED`로 건너뛴다.

**쿼리**

| 파라미터 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `tz` | string | ✔ | IANA 타임존 |

**응답 200** `data: LearningRunResponse`

| 필드 | 타입 | 설명 |
|---|---|---|
| `updatedCount` | integer | 이번 실행에서 학습된 밤 수 |
| `skipReason` | string · null | `updatedCount`가 0일 때만. 데이터가 있는 가장 최근 밤의 사유 |
| `skipMessage` | string · null | `skipReason`의 사용자 표시 문장 |

`skipReason` 값: `LEARNING_DISABLED` · `NO_SLEEP_SUMMARY` · `SLEEP_TOO_SHORT`(4시간 미만) · `MISSING_BEDTIME` · `INVALID_SOL`(0~180분 밖) · `ALREADY_LEARNED` · `INVALID_PRIOR` · `INSUFFICIENT_CAFFEINE_RESIDUAL`(취침 잔량 1mg 미만) · `INVALID_OBSERVATION`

```json
{ "success": true, "data": { "updatedCount": 1, "skipReason": null, "skipMessage": null }, "error": null, "message": null }
{ "success": true, "data": { "updatedCount": 0, "skipReason": "ALREADY_LEARNED", "skipMessage": "최근 수면 기록은 이미 학습에 반영됐어요." }, "error": null, "message": null }
```

### GET /api/learning/observations — 학습 관측 이력 조회

관측을 날짜 오름차순으로 돌려준다.

**응답 200** `data: ObservationResponse[]`

| 필드 | 타입 | 설명 |
|---|---|---|
| `date` | string(date) | 관측 날짜(아침, 로컬) |
| `predictedResidualAtBedtime` | number | 취침 시 예측 잔량(mg) |
| `observedSolMinutes` | number | 관측 SOL(분) |
| `priorMean` | number | 학습 전 반감기 평균(시간) |
| `posteriorMean` | number | 학습 후 반감기 평균(시간) |
| `posteriorVariance` | number | 학습 후 분산(시간²) |
| `conditionMultiplier` | number | 학습 당시 건강 상태 배수 |

```json
{ "success": true, "data": [ { "date": "2026-06-01", "predictedResidualAtBedtime": 114.87, "observedSolMinutes": 30.0, "priorMean": 5.0, "posteriorMean": 5.2, "posteriorVariance": 1.9, "conditionMultiplier": 1.0 } ], "error": null, "message": null }
```

### GET /api/learning/dashboard — 학습 대시보드

관측 이력에서 대시보드 통계를 서버가 계산한다.

**응답 200** `data: LearningDashboardResponse`

| 필드 | 타입 | 설명 |
|---|---|---|
| `count` | integer | 관측 수. 앱 카드 분기 0 / 1~2 / 3 이상 |
| `latest` | LatestEstimate · null | 최신 관측 기준 추정. `count`가 0이면 null |
| `latest.posteriorMean` | number | 반감기 추정(시간) |
| `latest.posteriorVariance` | number | |
| `latest.ciLower` / `latest.ciUpper` | number | 95% 신뢰구간(±1.96σ) |
| `latest.confidence` | number | 신뢰도 0~1 (`1 − σ_post / 1.5`) |
| `observations` | ObservationResponse[] | 날짜 오름차순 |
| `calibration` | Calibration | 예측 SOL(=15 + 0.1×잔량) 대 관측 SOL |
| `calibration.points[]` | { `predicted`, `observed` } | 분 |
| `calibration.rSquared` | number · null | 결정계수. 관측이 적거나 분산이 0이면 null |
| `calibration.rmse` | number | 분 |
| `calibration.domainLower` / `calibration.domainUpper` | number | 산점도 양 축 공통 범위(분) |
| `residualHistogram` | HistogramBinItem[] | 취침 잔량 분포, 최대 6개 빈 |
| `solHistogram` | HistogramBinItem[] | 관측 SOL 분포, 최대 6개 빈 |
| `*Histogram[].lower` / `*Histogram[].upper` | number | 빈 구간(마지막 빈 상한 포함) |
| `*Histogram[].count` | integer | |

```json
{ "success": true, "data": {
  "count": 1,
  "latest": { "posteriorMean": 5.2, "posteriorVariance": 1.9, "ciLower": 2.5, "ciUpper": 7.9, "confidence": 0.08 },
  "observations": [ { "date": "2026-06-01", "predictedResidualAtBedtime": 114.87, "observedSolMinutes": 30.0, "priorMean": 5.0, "posteriorMean": 5.2, "posteriorVariance": 1.9, "conditionMultiplier": 1.0 } ],
  "calibration": { "points": [ { "predicted": 26.49, "observed": 30.0 } ], "rSquared": null, "rmse": 3.51, "domainLower": 16.49, "domainUpper": 40.0 },
  "residualHistogram": [ { "lower": 114.87, "upper": 114.87, "count": 1 } ],
  "solHistogram": [ { "lower": 30.0, "upper": 30.0, "count": 1 } ]
}, "error": null, "message": null }
```

## 알림

서버는 푸시를 보내지 않는다. 앱이 예약할 **로컬 알림 계획**을 서버가 계산해 내려주고, iOS가 로컬 알림으로 예약한다.
운영 EC2가 외부 인터넷이 안 되어 APNs를 쓰지 않으며, 계산 로직은 나중에 푸시로 바꿔도 그대로 쓴다.

카페 근처 알림은 시각이 아니라 **구간**으로 내려간다. 서버는 "언제 울려야 하는지"만 알고 "어디"(카페 좌표·사용자 위치)는 모른다.
앱은 받은 구간을 기기에 저장해 두고, 카페 구역 지오펜스에 들어서는 순간 `activeFrom ≤ 지금 < activeUntil`인지 네트워크 없이 판정해 울린다.

### GET /api/notifications/plan — 알림 계획 조회

`now` 이후에 울려야 할 알림 목록을 돌려준다. 설정(`notifications.*`)에서 꺼진 종류는 생략한다.

- 앱은 기존에 예약한 알림을 **전부 취소하고** 이 목록대로 다시 예약한다.
- 재조회 시점: 카페인 기록 추가·수정·삭제 후, 설정 변경 후, 학습 실행 후, 앱 포그라운드 진입 시.

**쿼리**

| 파라미터 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `now` | string(date-time) | ✔ | 기기 현재 시각, 오프셋 포함 |
| `tz` | string | ✔ | IANA 타임존 |

**응답 200** `data: NotificationPlanResponse`

| 필드 | 타입 | 설명 |
|---|---|---|
| `now` | string(date-time) | 요청 `now`(UTC) |
| `items` | NotificationItem[] | 예약할 알림. `fireAt` 오름차순. 없으면 `[]` |
| `items[].type` | string | `CUTOFF`(섭취 마감 알림) · `BEDTIME_RESIDUAL`(취침 잔량 예고) · `RECORD_REMINDER`(기록 리마인더) |
| `items[].fireAt` | string(date-time) | 울릴 시각(UTC). 항상 `now` 이후 |
| `items[].title` | string | 알림 제목(한국어) |
| `items[].body` | string | 알림 본문(한국어) |
| `cafeNearbyWindows` | CafeNearbyWindow[] | 카페 근처 알림 금지 구간. 설정 `notifications.cafeNearby`가 꺼져 있으면 `[]`. 오늘 구간, 기록 없는 다음 날의 기본 구간 순 |
| `cafeNearbyWindows[].reason` | string | `ALREADY_EXCEEDED`(취침 잔량 이미 초과) · `DAILY_LIMIT_EXCEEDED`(하루 400mg 초과) · `CUTOFF_PASSED`(섭취 마감 지남) |
| `cafeNearbyWindows[].activeFrom` | string(date-time) | 구간 시작(UTC). 과거일 수 있다 |
| `cafeNearbyWindows[].activeUntil` | string(date-time) | 구간 끝(UTC, 미포함). 그날 하루 경계(다음 05:00 `tz`) |
| `cafeNearbyWindows[].title` | string | 알림 제목(한국어) |
| `cafeNearbyWindows[].body` | string | 알림 본문(한국어) |

**종류별 생성 규칙** — 조건을 만족하지 않는 종류는 목록에서 빠진다.

| 종류 | 생성 조건 | `fireAt` | 문구 |
|---|---|---|---|
| `CUTOFF` | `notifications.cutoff` 켜짐 · 오늘 현황의 `cutoff.status`가 `CUTOFF` · `cutoff − 30분`이 `now` 이후 | `cutoff − 30분` | 제목 `섭취 마감 30분 전`. 본문 `{취침 HH:mm} 취침 기준, {referenceDoseMg}mg를 마실 수 있는 마지막 시각은 {마감 HH:mm}이에요.` (HH:mm은 `tz` 로컬) |
| `BEDTIME_RESIDUAL` | `notifications.bedtimeResidual` 켜짐 · 오늘 현황의 `predictedAtBedtime`이 50mg 이상 · `bedtime − 60분`이 `now` 이후 | `bedtime − 60분` | 제목 `취침 60분 전 잔량 예고`. 본문 `{취침 HH:mm} 취침 시 카페인이 약 {반올림 mg}mg 남아 있을 것으로 보여요.` |
| `RECORD_REMINDER` | `notifications.recordReminder` 켜짐 · 오늘(05:00 경계) 기록 없음 · 최근 14일 중 기록 있는 날 5일 이상 · `평소 첫 기록 시각 + 2시간`이 `now` 이후이고 오늘 창(05:00부터 24시간) 안 | `평소 첫 기록 시각 + 2시간` | 제목 `오늘 카페인 기록이 없어요`. 본문 `평소 {HH:mm}쯤 첫 잔을 기록했어요. 마셨다면 잊지 말고 기록해 주세요.` |

`SAFE_ANYTIME`(마감 없음)·`ALREADY_EXCEEDED`(취침 잔량 이미 초과)·마감까지 30분 미만 남음이면 `CUTOFF` 항목은 없다. 홈 화면이 마감시각을 이미 보여주므로 중복 안내하지 않는다.
기록이 없거나(잔량 0) 예상 잔량이 50mg 미만이거나 취침 60분 전이 지났으면 `BEDTIME_RESIDUAL` 항목은 없다.
예상 잔량 50mg 이상은 곧 `ALREADY_EXCEEDED`이므로 `CUTOFF`와 `BEDTIME_RESIDUAL`은 같은 계획에 함께 나오지 않는다(여유 있으면 마감 알림, 초과면 잔량 예고).
`RECORD_REMINDER`는 `CUTOFF`와 함께 나올 수 있다(둘 다 오늘 기록이 없을 때 생기며 `fireAt` 오름차순).

**평소 첫 기록 시각** = 최근 14일(`오늘 05:00 − 14일 ≤ timestamp < 오늘 05:00`)의 기록을 로컬 하루(05:00 경계, 05:00 이전은 전날)로 묶어
날마다 첫 기록이 그날 05:00으로부터 얼마나 뒤인지 구한 뒤, 그 중앙값(짝수면 가운데 둘의 평균)을 오늘 05:00에 더한 시각.
기록이 오늘 생기면 앱이 재조회하고 항목이 사라진다.

**카페 근처 금지 구간 규칙** — `notifications.cafeNearby`가 켜져 있을 때만. 하루에 한 구간이며, 사유가 겹치면 위의 것이 이긴다.
끝(`activeUntil`)은 항상 그날 하루 경계(다음 05:00 `tz`)다 — 취침 뒤 새벽 섭취도 막고 "오늘" 경계와 맞춘다.

| `reason` | 조건 | `activeFrom` | 문구 |
|---|---|---|---|
| `ALREADY_EXCEEDED` | 오늘 현황의 `cutoff.status`가 `ALREADY_EXCEEDED` | `now` | 제목 `카페 근처예요`. 본문 `지금도 취침 때 카페인이 약 {반올림 mg}mg 남을 것 같아요. 오늘은 디카페인으로 골라 주세요.` |
| `DAILY_LIMIT_EXCEEDED` | 오늘 현황의 `overDailyLimit`이 true | `now` | 제목 같음. 본문 `오늘 이미 {todayTotal}mg를 마셨어요(하루 권장 400mg). 오늘은 디카페인으로 골라 주세요.` |
| `CUTOFF_PASSED` | 오늘 현황의 `cutoff.status`가 `CUTOFF` | `cutoff` | 제목 같음. 본문 `오늘 마감 {마감 HH:mm}이 지났어요. 지금 {referenceDoseMg}mg 한 잔이면 취침 때 기준(50mg)을 넘어요. 들어간다면 디카페인으로!` |

`SAFE_ANYTIME`(마감 없음)이고 400mg 이하면 구간이 없다.
마감 시각이 지나면 기준 용량 한 잔이 곧 취침 시 50mg 초과이므로 `CUTOFF_PASSED` 문구는 진입 순간 계산 없이 참이다.

**기록 없는 다음 날의 기본 구간** — 같은 규칙을 다음 하루 경계 시각(`now`가 속한 날의 다음 05:00)에 새 기록이 없다고 보고 한 번 더 계산해 두 번째 원소로 붙인다. 그 시각 기준 최근 24시간 기록의 잔량은 계산에 포함한다.
앱을 하루 종일 열지 않은 날에도 동작하게 하기 위해서다. 다음 날 기록은 앱에서만 생기고 그때 앱이 재조회하므로 가정이 틀리는 순간 교체된다.

```json
{ "success": true, "data": { "now": "2026-06-01T05:00:00Z", "items": [
  { "type": "CUTOFF", "fireAt": "2026-06-01T10:34:30.675Z", "title": "섭취 마감 30분 전", "body": "23:00 취침 기준, 75mg를 마실 수 있는 마지막 시각은 20:04이에요." }
], "cafeNearbyWindows": [
  { "reason": "CUTOFF_PASSED", "activeFrom": "2026-06-01T11:04:30.675Z", "activeUntil": "2026-06-01T20:00:00Z", "title": "카페 근처예요", "body": "오늘 마감 20:04이 지났어요. 지금 75mg 한 잔이면 취침 때 기준(50mg)을 넘어요. 들어간다면 디카페인으로!" },
  { "reason": "CUTOFF_PASSED", "activeFrom": "2026-06-02T11:04:30.675Z", "activeUntil": "2026-06-02T20:00:00Z", "title": "카페 근처예요", "body": "오늘 마감 20:04이 지났어요. 지금 75mg 한 잔이면 취침 때 기준(50mg)을 넘어요. 들어간다면 디카페인으로!" }
] }, "error": null, "message": null }
```

## 인사이트

기간을 정해 섭취 통계와 카페인·수면 비교를 서버가 계산한다. 문장도 서버가 만들고(한국어) 앱은 그대로 표시한다.
문장은 비교 결과를 서술만 하고 인과·통계 단정("때문에", "유의미하게")은 쓰지 않는다. p값·신뢰구간은 내지 않는다.

**쿼리(두 엔드포인트 공통)**

| 파라미터 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `from` | string(date) | ✔ | 시작 날짜(로컬), 포함 |
| `to` | string(date) | ✔ | 끝 날짜(로컬), 포함 |
| `tz` | string | ✔ | IANA 타임존 |

- `from ≤ to`, 기간(양 끝 포함) **31일 이하**, `to ≤ 오늘`(`tz` 기준 로컬 날짜). 어기면 400 `INVALID_PARAMETER`. 앱은 7·14·30일 프리셋만 보낸다.
- 섭취 통계의 날짜는 **섭취일**(로컬 05:00 ~ 다음 날 05:00), 리포트의 날짜는 **밤이 끝나는 아침 날짜**(수면 요약 `date`와 같다)다. 키가 하루 어긋난다.

### GET /api/insight/intake-stats — 기간별 섭취 통계

`from`~`to` 각 섭취일의 총량·기록 수·첫/마지막 섭취 시각과 요약을 돌려준다.

- 기록이 없는 날도 `totalMg = 0`, `recordCount = 0`으로 `days`에 들어간다(날짜 오름차순, 기간 일수만큼).
- 요약(평균·최대·400mg 초과 일수·마지막 섭취 시각 평균)은 **기록이 있는 날**만으로 내고, **오늘**(`to`가 오늘일 때)은 끝나지 않은 날이라 뺀다. 그런 날이 없으면 평균·최대·시각 평균은 null.
- 마지막 섭취 시각 평균은 05:00 기준 경과 분으로 평균한 뒤 벽시계 분으로 되돌린다(새벽 1시 섭취가 평균을 끌어내리지 않게).

**응답 200** `data: IntakeStatsResponse`

| 필드 | 타입 | 설명 |
|---|---|---|
| `from` / `to` | string(date) | 요청 값 |
| `dayCount` | integer | 기간 일수(= `days` 길이) |
| `recordedDayCount` | integer | 기록이 있는 날 수(오늘 포함) |
| `averageDailyMg` | number · null | 기록 있는 날(오늘 제외)의 하루 평균 mg. 그런 날이 없으면 null |
| `maxDailyMg` | number · null | 같은 조건의 최대 mg |
| `overLimitDayCount` | integer | 하루 400mg 초과 일수(오늘 제외) |
| `averageLastIntakeMinuteOfDay` | integer · null | 마지막 섭취 로컬 시각 평균, 벽시계 분 0~1439(예: 1000 = 16:40). 같은 조건, 없으면 null |
| `summarySentence` | string | 요약 문장 |
| `days` | DayItem[] | 섭취일 오름차순 |
| `days[].date` | string(date) | 섭취일(로컬, 05:00 경계) |
| `days[].totalMg` | number | 총 섭취량 mg |
| `days[].recordCount` | integer | 기록 수 |
| `days[].firstIntakeAt` / `days[].lastIntakeAt` | string(date-time) · null | 첫·마지막 섭취 시각(UTC). 기록 없으면 null |
| `days[].overLimit` | boolean | `totalMg` > 400 |

`summarySentence` 규칙 — `{기간}`은 `to`가 오늘이면 `최근 {dayCount}일`, 아니면 `{M}월 {d}일~{M}월 {d}일`. mg는 반올림 정수.
- 평균을 낼 날이 있음: `{기간} 하루 평균 {averageDailyMg}mg, 400mg을 넘긴 날은 {overLimitDayCount}일이에요. 마지막 한 잔은 평균 {H}시 {m}분이에요.`
  (`overLimitDayCount`가 0이면 `400mg을 넘긴 날은 없어요.`, 분이 0이면 `{H}시`)
- 없음: `{기간} 하루 평균을 낼 섭취 기록이 아직 없어요.`

```json
{ "success": true, "data": {
  "from": "2026-06-01", "to": "2026-06-07", "dayCount": 7, "recordedDayCount": 2,
  "averageDailyMg": 210.0, "maxDailyMg": 420.0, "overLimitDayCount": 1, "averageLastIntakeMinuteOfDay": 1000,
  "summarySentence": "최근 7일 하루 평균 210mg, 400mg을 넘긴 날은 1일이에요. 마지막 한 잔은 평균 16시 40분이에요.",
  "days": [
    { "date": "2026-06-01", "totalMg": 0.0, "recordCount": 0, "firstIntakeAt": null, "lastIntakeAt": null, "overLimit": false },
    { "date": "2026-06-02", "totalMg": 420.0, "recordCount": 3, "firstIntakeAt": "2026-06-02T00:00:00Z", "lastIntakeAt": "2026-06-02T08:00:00Z", "overLimit": true }
  ]
}, "error": null, "message": null }
```

### GET /api/insight/caffeine-sleep — 카페인·수면 상관 리포트

`from`~`to`(아침 날짜) 각 밤의 취침 잔량·마감 준수와 수면 지표를 모아 두 비교를 계산한다.

**밤(night)** — `date` 아침에 끝나는 밤. 수면 윈도우·병합은 `GET /api/sleep/summary`와 같고, 수면 지표도 같은 계산이다.
- 수면 데이터가 없거나 `bedtimeStart`가 null인 밤은 **전부에서 제외**한다(`nights`에도 없다). 설정 취침 시각으로 폴백하지 않는다.
- 취침 시각 = `bedtimeStart`. 취침 잔량 = 취침 직전 24시간 기록의 잔량(학습과 같은 창). 기록이 없으면 0.
- 반감기는 **요청 시점의 유효 반감기** 하나로 기간 전체를 계산한다(`halfLifeHoursUsed`, 홈과 같은 값). 그래서 학습 관측의
  `predictedResidualAtBedtime`(그 밤 학습 당시 반감기)과 같은 밤이라도 값이 다를 수 있다.
- 짝지은 섭취일: 아침 날짜 D의 밤은 섭취일 D−1(로컬 D−1 05:00 ~ D 05:00)의 **취침 전** 기록과 짝짓는다. `dayTotalMg`·`lastIntakeAt`·`cutoffKept`가 이 규칙을 따른다.
- 깊은 수면 비율 = deep ÷ 총 수면. 단계 구분 데이터(core·deep·rem)가 없거나 총 수면이 0이면 null.

**비교 A — 취침 잔량**: `high` = 잔량 ≥ 50mg, `low` = 50mg 미만(카페인 0mg 밤 포함). 50mg은 홈 안전선과 같은 값이다.

**비교 B — 마감 시각**: 짝지은 섭취일에 기록이 있는 밤만. 그 날 마지막 기록을 `last`라 하고, `last` 이전(취침 전 24시간 안) 기록으로
홈과 같은 마감 시각 계산(현재 설정 `referenceDoseMg`, 유효 반감기, 50mg)을 한다.
`SAFE_ANYTIME` → 지킴, `CUTOFF`면 `last` ≤ 마감 → 지킴 / 늦으면 넘김, `ALREADY_EXCEEDED` → 넘김. `high` = 넘김, `low` = 지킴.
과거 당시가 아니라 **지금 설정 기준** 판정이라, 비교 B `sentence`는 항상 `지금 기준 용량({referenceDoseMg}mg)으로 따지면, `으로 시작한다.

**표본 기준**
- 리포트: 밤 **5밤** 이상이면 최상위 `sufficient = true`. 모자라도 응답 형태는 같고 `nights`는 항상 내린다.
- 비교: 리포트가 충분하고 두 그룹이 각각 **3밤** 이상일 때 `sufficient = true`. 아니면 `moreNightsNeeded` = 두 그룹의 `max(0, 3 − nightCount)` 합, 차이는 null.
- `solDiffMinutes`는 두 그룹 모두 SOL 있는 밤이 3밤 이상일 때만. 모자라면 null이고 효율만으로 문장을 낸다(`sufficient`는 그대로).
- "차이 있음": |SOL 차이| ≥ **5분** 또는 |효율 차이| ≥ **0.03**(3%p). 둘 다 미만이면 "뚜렷한 차이 없음" 문장.

**응답 200** `data: CaffeineSleepReportResponse`

| 필드 | 타입 | 설명 |
|---|---|---|
| `from` / `to` | string(date) | 요청 값 |
| `nightCount` | integer | 리포트에 들어간 밤 수(= `nights` 길이) |
| `sufficient` | boolean | 5밤 이상. false여도 모든 필드가 채워진다 |
| `residualThresholdMg` | number | 비교 A 경계 50.0 (차트·문구용) |
| `halfLifeHoursUsed` | number | 잔량 계산에 쓴 유효 반감기(시간, 요청 시점) |
| `residualComparison` | Comparison | 비교 A. `high` = 잔량 ≥ 50mg, `low` = 미만 |
| `cutoffComparison` | Comparison | 비교 B. `high` = 마감 넘김, `low` = 지킴 |
| `*Comparison.high` / `*Comparison.low` | Group | 그룹 지표 |
| `*Comparison.high.nightCount` / `*Comparison.low.nightCount` | integer | 그룹 밤 수 |
| `*Comparison.high.solNightCount` / `*Comparison.low.solNightCount` | integer | 그중 SOL이 있는 밤 수 |
| `*Comparison.high.avgSolMinutes` / `*Comparison.low.avgSolMinutes` | number · null | SOL 평균(분). SOL 있는 밤이 없으면 null |
| `*Comparison.high.avgSleepEfficiency` / `*Comparison.low.avgSleepEfficiency` | number · null | 수면 효율 평균(0~1). 밤이 없으면 null |
| `*Comparison.high.avgTotalSleepMinutes` / `*Comparison.low.avgTotalSleepMinutes` | number · null | 총 수면 평균(분). 밤이 없으면 null |
| `*Comparison.high.avgDeepSleepRatio` / `*Comparison.low.avgDeepSleepRatio` | number · null | 깊은 수면 비율 평균(0~1). 값 있는 밤이 없으면 null |
| `*Comparison.sufficient` | boolean | 이 비교의 표본 충족 |
| `*Comparison.moreNightsNeeded` | integer · null | 모자란 밤 수 합. `sufficient`면 null |
| `*Comparison.solDiffMinutes` | number · null | `high − low` SOL 평균(분). 계산 불가면 null |
| `*Comparison.efficiencyDiff` | number · null | `high − low` 효율 평균(0~1). `sufficient`가 false면 null |
| `*Comparison.sentence` | string | 비교 문장(아래 규칙) |
| `headlines` | string[] | 화면 상단 문장 1~2개(아래 규칙) |
| `nights` | NightItem[] | 밤 날짜 오름차순. `sufficient`와 무관하게 항상 |
| `nights[].date` | string(date) | 밤이 끝나는 아침 날짜(수면 요약 `date`와 같다) |
| `nights[].bedtime` | string(date-time) | `bedtimeStart`(UTC) |
| `nights[].residualAtBedtimeMg` | number | 취침 잔량 mg, 반올림 없음. 취침 전 24시간 기록이 없으면 0(null 아님) |
| `nights[].dayTotalMg` | number | 짝지은 섭취일의 취침 전 섭취 총량 mg. 없으면 0 |
| `nights[].lastIntakeAt` | string(date-time) · null | 짝지은 섭취일의 취침 전 마지막 섭취 시각. 없으면 null |
| `nights[].cutoffKept` | boolean · null | 비교 B 판정(true = 지킴). 짝지은 섭취일 기록이 없으면 null |
| `nights[].solMinutes` | number · null | 같은 `date` 수면 요약 `sleepOnsetLatencySeconds` ÷ 60. null 조건도 같다 |
| `nights[].sleepEfficiency` | number | 0~1 |
| `nights[].totalSleepMinutes` | number | 분 |
| `nights[].deepSleepRatio` | number · null | 깊은 수면 비율(0~1) |

**문장 규칙** — 분·%p·mg는 반올림 정수. `{d}` = |SOL 차이|분, `{e}` = |효율 차이|×100 %p.

| 경우 | 비교 A `sentence` | 비교 B `sentence`(앞에 `지금 기준 용량({referenceDoseMg}mg)으로 따지면, `) |
|---|---|---|
| 표본 부족 | `비교하려면 잔량이 높았던 밤이 {n}밤 더 필요해요` (낮은 쪽은 `잔량이 낮았던 밤이`, 둘 다면 `잔량이 높았던 밤이 {n}밤, 낮았던 밤이 {m}밤`) | `마감을 넘긴 밤이 {n}밤 더 있어야 비교할 수 있어요` (지킨 쪽은 `마감을 지킨 밤이`, 둘 다면 `마감을 넘긴 밤이 {n}밤, 지킨 밤이 {m}밤`) |
| SOL 차이 있음 | `취침 때 카페인이 50mg 이상 남아 있던 밤은 잠드는 데 평균 {d}분 더(덜) 걸렸어요` — 효율 차이도 있으면 `…걸렸고, 수면 효율은 {e}%p 낮았어요(높았어요)` | `마감 시각을 지킨 밤은 평균 {low SOL}분 만에 잠들었고 넘긴 밤은 {high SOL}분 걸렸어요` — 효율 차이도 있으면 `. 수면 효율은 넘긴 밤이 {e}%p 낮았어요(높았어요)` |
| 효율 차이만 있음 | `취침 때 카페인이 50mg 이상 남아 있던 밤은 수면 효율이 평균 {e}%p 낮았어요(높았어요)` | `마감 시각을 넘긴 밤은 지킨 밤보다 수면 효율이 평균 {e}%p 낮았어요(높았어요)` |
| 차이 없음 | `취침 잔량에 따른 뚜렷한 차이는 아직 보이지 않아요` | `마감 시각을 지켰는지에 따른 뚜렷한 차이는 아직 보이지 않아요` |

표본이 충분하면 끝에 밤 수를 붙인다 — 비교 A ` ({high}밤 vs {low}밤)`, 비교 B ` (지킨 밤 {low}밤 · 넘긴 밤 {high}밤)`.

`headlines`: 리포트가 5밤 미만이면 `["리포트를 보려면 수면 기록이 있는 밤이 {5 − nightCount}밤 더 필요해요"]`.
아니면 "차이 있음"인 비교의 `sentence`를 A, B 순으로 담고, 하나도 없으면 비교 A `sentence` 하나.

```json
{ "success": true, "data": {
  "from": "2026-06-01", "to": "2026-06-14", "nightCount": 12, "sufficient": true,
  "residualThresholdMg": 50.0, "halfLifeHoursUsed": 5.0,
  "residualComparison": {
    "high": { "nightCount": 5, "solNightCount": 5, "avgSolMinutes": 27.0, "avgSleepEfficiency": 0.86, "avgTotalSleepMinutes": 402.0, "avgDeepSleepRatio": 0.14 },
    "low": { "nightCount": 7, "solNightCount": 7, "avgSolMinutes": 16.0, "avgSleepEfficiency": 0.9, "avgTotalSleepMinutes": 431.0, "avgDeepSleepRatio": 0.17 },
    "sufficient": true, "moreNightsNeeded": null, "solDiffMinutes": 11.0, "efficiencyDiff": -0.04,
    "sentence": "취침 때 카페인이 50mg 이상 남아 있던 밤은 잠드는 데 평균 11분 더 걸렸고, 수면 효율은 4%p 낮았어요 (5밤 vs 7밤)"
  },
  "cutoffComparison": {
    "high": { "nightCount": 2, "solNightCount": 2, "avgSolMinutes": 30.0, "avgSleepEfficiency": 0.85, "avgTotalSleepMinutes": 395.0, "avgDeepSleepRatio": 0.13 },
    "low": { "nightCount": 8, "solNightCount": 8, "avgSolMinutes": 18.0, "avgSleepEfficiency": 0.89, "avgTotalSleepMinutes": 425.0, "avgDeepSleepRatio": 0.16 },
    "sufficient": false, "moreNightsNeeded": 1, "solDiffMinutes": null, "efficiencyDiff": null,
    "sentence": "지금 기준 용량(75mg)으로 따지면, 마감을 넘긴 밤이 1밤 더 있어야 비교할 수 있어요"
  },
  "headlines": [ "취침 때 카페인이 50mg 이상 남아 있던 밤은 잠드는 데 평균 11분 더 걸렸고, 수면 효율은 4%p 낮았어요 (5밤 vs 7밤)" ],
  "nights": [
    { "date": "2026-06-02", "bedtime": "2026-06-01T14:00:00Z", "residualAtBedtimeMg": 114.87, "dayTotalMg": 200.0, "lastIntakeAt": "2026-06-01T10:00:00Z", "cutoffKept": false, "solMinutes": 30.0, "sleepEfficiency": 0.9375, "totalSleepMinutes": 450.0, "deepSleepRatio": null }
  ]
}, "error": null, "message": null }
```

## 운영

앱이 호출하지 않는 운영용 경로. Spring Boot Actuator가 제공하며 컨트롤러가 아니므로 위 엔드포인트 목록 표와
`ApiDocsConsistencyIT` 대조 대상에서 빠진다. 배포 확인 절차는 [`운영-가이드.md`](../ops/운영-가이드.md).

운영(prod)에서는 관리 포트 **8081**에서만 응답하고 8080에서는 404다. compose가 8081을 루프백에만 바인딩하므로 앱에서는 호출할 수 없다.

### GET /actuator/health — 헬스체크

인증 불필요(`SecurityConfig`의 공개 경로). 배포 후 기동 확인과 컨테이너 healthcheck에 쓴다.

- 응답은 **공통 봉투가 아니다** — Actuator 형식 그대로 `{"status": ...}`만 내려간다.
- 운영(prod)은 `show-details: never`라 DB 등 구성 요소별 상세는 나오지 않는다.
- `probes.enabled: true`라 하위 경로 `/actuator/health/liveness`·`/actuator/health/readiness`도 같은 형식으로 응답한다.

| 상태 | HTTP | 바디 |
|---|---|---|
| 정상 | 200 | `{"status":"UP"}` |
| 비정상(DB 연결 실패 등) | 503 | `{"status":"DOWN"}` |

```bash
curl localhost:8081/actuator/health   # {"status":"UP"}
```

### GET /actuator/prometheus — 메트릭

인증 불필요(`SecurityConfig`의 공개 경로). Prometheus 수집용이며 **공통 봉투가 아니라** Prometheus 텍스트 형식으로 응답한다.
운영(prod)에서만 노출하고, 그 외 프로파일은 별도 설정이 없으면 404다.
HTTP·JVM·DB 풀 기본 메트릭 외에 학습·수면 업로드·로그인 결과를 세는 도메인 카운터(`caffeine_*_total`)가 함께 나간다.
목록과 읽는 법은 [`운영-가이드.md`](../ops/운영-가이드.md) "도메인 메트릭".

```bash
curl localhost:8081/actuator/prometheus
```
