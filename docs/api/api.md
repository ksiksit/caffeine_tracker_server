# API 명세

카페인 트래커 서버 REST API. (서버 구현 기준, 2026-09-06)

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
| 인증 | `Authorization: Bearer <accessToken>` 헤더. 공개 엔드포인트는 회원가입·로그인·토큰 재발급 3개뿐 |
| Content-Type | 요청·응답 모두 `application/json` (UTF-8) |
| 토큰 | access 토큰(JWT) 1시간, refresh 토큰 14일. refresh는 1회용 — 재발급 시 새 쌍이 나오고 기존 refresh는 폐기된다(재사용 시 401). 로그아웃은 그 사용자의 refresh를 전부 폐기한다 |

### 날짜·시간·타임존

- 요청의 시각은 ISO-8601 + 오프셋: `2026-06-01T14:00:00+09:00`. 응답의 시각은 UTC `Z`: `2026-06-01T05:00:00Z`.
- 날짜는 `YYYY-MM-DD`(로컬 날짜). `tz`는 IANA 타임존 이름(`Asia/Seoul`)이며 잘못된 값은 400 `INVALID_PARAMETER`.
- 서버는 시각을 UTC로 저장하고, "오늘 경계·취침시각·수면 윈도우" 같은 로컬 달력 연산만 요청의 `tz`로 계산한다.
  - 오늘 = `tz` 기준 그날 05:00부터 24시간. 00:00~05:00에는 그날 05:00이 아직 오지 않았으므로 오늘 기록·잔량이 비어 있다
    (iOS `filterTodayRecords`와 같은 동작)
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
| `INVALID_REFRESH_TOKEN` | 401 | RefreshToken 이 유효하지 않습니다. | 토큰 재발급: 없음·만료·폐기·재사용 |
| `UNAUTHORIZED` | 401 | 인증이 필요합니다. | Bearer 토큰 없음·만료·위조(시큐리티 단계) |
| `FORBIDDEN` | 403 | 접근 권한이 없습니다. | 권한 부족(현재 발생 경로 없음, 예비) |
| `VALIDATION_FAILED` | 400 | 입력값이 올바르지 않습니다. | 바디 필드 검증 실패. `error.fieldErrors` 포함 |
| `MALFORMED_JSON` | 400 | 잘못된 요청 형식입니다. | JSON 파싱 실패·바디 필드 타입 불일치 |
| `INVALID_PARAMETER` | 400 | 요청 파라미터가 올바르지 않습니다. | 쿼리·경로 파라미터 누락·형식 오류(잘못된 `tz`·`now`·`date`, 숫자가 아닌 `id`) |
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

**에러**: 409 `EMAIL_ALREADY_EXISTS`

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
| `refreshToken` | string | 불투명 문자열. 재발급 전용, 1회용 |
| `tokenType` | string | 항상 `Bearer` |
| `expiresIn` | integer | access 토큰 유효 시간(초). 3600 |
| `refreshExpiresIn` | integer | refresh 토큰 유효 시간(초). 1209600 |

```json
{ "success": true, "data": { "accessToken": "eyJhbGciOiJIUzI1NiJ9...", "refreshToken": "9f3c...", "tokenType": "Bearer", "expiresIn": 3600, "refreshExpiresIn": 1209600 }, "error": null, "message": null }
```

**에러**: 401 `INVALID_CREDENTIALS`

### POST /api/auth/refresh — 토큰 재발급

refresh 토큰으로 새 access/refresh 쌍을 발급한다(회전). 기존 refresh는 즉시 폐기되며 다시 쓰면 401. 인증 불필요.

**요청 바디** `RefreshTokenRequest`

| 필드 | 타입 | 필수 | 제약 | 설명 |
|---|---|---|---|---|
| `refreshToken` | string | ✔ | | 로그인·재발급으로 받은 refresh 토큰 |

**응답 200** `data: TokenResponse` (로그인과 동일)

**에러**: 401 `INVALID_REFRESH_TOKEN` — 없음·만료·폐기·재사용

### POST /api/auth/logout — 로그아웃

토큰의 사용자에 속한 refresh 토큰을 전부 폐기한다(모든 기기 로그아웃). access 토큰은 만료 전까지 유효하므로 앱이 폐기한다.

**요청 바디** `LogoutRequest`

| 필드 | 타입 | 필수 | 제약 | 설명 |
|---|---|---|---|---|
| `refreshToken` | string | ✔ | | 클라이언트 계약 호환용. 서버는 값과 무관하게 토큰의 사용자 기준으로 폐기 |

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

유저 설정과 학습 상태를 조회한다. 설정 행이 없으면 기본값으로 생성해 반환한다.

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

```json
{ "success": true, "data": { "halfLife": 5.0, "condition": 0, "bedtimeHour": 23, "bedtimeMinute": 0, "referenceDoseMg": 75, "isLearningEnabled": true, "learnedMean": 5.0, "learnedVariance": 2.25, "lastLearnedDate": null, "inferredBaseHalfLife": 5.0, "effectiveHalfLifeHours": 5.0, "notifications": { "cutoff": true, "bedtimeResidual": true, "recordReminder": true } }, "error": null, "message": null }
```

### PUT /api/settings — 설정 변경

설정 전체를 교체한다. `halfLife`가 이전 값과 다르면 학습 prior가 리셋된다(`learnedMean=halfLife`, `learnedVariance=2.25`, `lastLearnedDate=null`).

**요청 바디** `UpdateSettingsRequest`

| 필드 | 타입 | 필수 | 제약 | 설명 |
|---|---|---|---|---|
| `halfLife` | number | ✔ | 3.0~7.0 | |
| `condition` | integer | ✔ | 0~3 | |
| `bedtimeHour` | integer | ✔ | 0~23 | |
| `bedtimeMinute` | integer | ✔ | 0~59 | |
| `referenceDoseMg` | integer | ✔ | 1~1000 | |
| `isLearningEnabled` | boolean | ✔ | | |
| `notifications` | object | ✔ | | 알림 종류별 on/off. 세 필드 모두 필수 |
| `notifications.cutoff` | boolean | ✔ | | |
| `notifications.bedtimeResidual` | boolean | ✔ | | |
| `notifications.recordReminder` | boolean | ✔ | | |
| `learnedMean` | number | | | `learnedVariance`와 함께 보내면 학습 상태를 1회 시드(기존 기기의 학습값 이전용). 둘 중 하나만 있으면 무시 |
| `learnedVariance` | number | | | 위와 같음 |
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
| `chart` | DataPointResponse[] | 잔량 시계열. 오늘 05:00부터 24시간, 15분 간격 + 섭취 직전·직후 점. 기록 없으면 `[]` |
| `chart[].time` | string(date-time) | UTC |
| `chart[].concentrationMg` | number | 그 시각 체내 잔량(mg) |
| `currentResidual` | number | `now` 시점 잔량(mg). 기록 없으면 0 |
| `predictedAtBedtime` | number | 취침시각 예상 잔량(mg). 기록 없으면 0 |
| `cutoff` | CutoffResponse | 기준 용량(`referenceDoseMg`)을 취침 시 잔량 50mg 이내로 마실 수 있는 가장 늦은 시각 |
| `cutoff.status` | string | `SAFE_ANYTIME`(언제든 가능) · `CUTOFF`(마감시각 있음) · `ALREADY_EXCEEDED`(이미 초과) |
| `cutoff.cutoff` | string(date-time) · null | `CUTOFF`일 때만. 과거일 수 있다 |
| `cutoff.existingAtBedtime` | number · null | `ALREADY_EXCEEDED`일 때만. 취침 시 기존 잔량(mg) |
| `effectiveHalfLifeHours` | number | 계산에 쓴 유효 반감기 |
| `referenceDoseMg` | integer | 계산에 쓴 기준 용량 |

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

기기에서 읽은 원시 샘플을 배치로 올린다. `clientUuid`가 이미 저장돼 있으면 건너뛰므로 같은 배치를 다시 보내도 안전하다(멱등). 배치 안의 중복 `clientUuid`는 첫 항목만 저장.

**요청 바디** `UploadSleepSamplesRequest`

| 필드 | 타입 | 필수 | 제약 | 설명 |
|---|---|---|---|---|
| `samples` | SampleItem[] | ✔ | 1개 이상 | |
| `samples[].clientUuid` | string | ✔ | 최대 64자 | HealthKit 샘플 UUID. 멱등 키 |
| `samples[].start` | string(date-time) | ✔ | 오프셋 포함 | |
| `samples[].end` | string(date-time) | ✔ | 오프셋 포함 | |
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

오늘(`tz` 기준) 포함 최근 7일 중 수면 데이터가 있고 아직 학습하지 않은 밤을 오래된 순으로 학습하고 설정의 학습 상태에 반영한다. 같은 날짜는 1회만 학습된다.

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

```json
{ "success": true, "data": { "now": "2026-06-01T05:00:00Z", "items": [
  { "type": "CUTOFF", "fireAt": "2026-06-01T10:34:30.675Z", "title": "섭취 마감 30분 전", "body": "23:00 취침 기준, 75mg를 마실 수 있는 마지막 시각은 20:04이에요." }
] }, "error": null, "message": null }
```
