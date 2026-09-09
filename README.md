# Caffeine Tracker Server

카페인 관리 앱의 백엔드 REST API 서버입니다. 졸업 작품(캡스톤 디자인) 프로젝트입니다.

> **이 프로젝트는 iOS 앱 + Spring 서버로 구성된 풀스택입니다.**
> - iOS 앱: [`../captest1`](../captest1) (SwiftUI · HealthKit)
> - 서버: 이 레포 (Spring Boot · MySQL)
>
> **thin-client 구조** — 앱은 사용자 입력을 전송하고, **서버가 약동학·수면 병합·베이지안 학습을 연산·저장**합니다.
> 앱은 서버가 계산한 결과를 표시만 합니다.
>
> ```
>   iOS 앱(captest1)                 이 서버(server)
>   기록 입력·표시   ── REST(JWT) ──▶   약동학·수면·학습 연산 ──▶ MySQL
>   HealthKit 읽기  ── 원시샘플 업로드 ─▶   수면 병합·SOL·학습
> ```

## 기술 스택

- **Java 21**, **Spring Boot 3.5.14**
- **Spring Data JPA** (Hibernate) + **MySQL 8**
- **Spring Security** + **OAuth2 Resource Server (JWT)** — 인증
- **Gradle** — 빌드

## 요구 사항

- JDK 21
- MySQL 8.x

## 로컬 실행 방법

### 1. MySQL 데이터베이스 준비

```sql
CREATE DATABASE caffeine_tracker
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

CREATE USER 'caffeine'@'localhost' IDENTIFIED BY '<YOUR_PASSWORD>';
GRANT ALL PRIVILEGES ON caffeine_tracker.* TO 'caffeine'@'localhost';
FLUSH PRIVILEGES;
```

테이블은 애플리케이션이 만들지 않습니다. DDL 원본 `docs/db/db-schema.sql`을 직접 실행합니다 (모든 문장이 `CREATE TABLE IF NOT EXISTS`라 재실행해도 안전).

```bash
mysql -u caffeine -p caffeine_tracker < docs/db/db-schema.sql
```

스키마 설명·변경 절차·변경 이력은 `docs/db/db-schema.md` 참조. 스키마가 없거나 엔티티와 다르면 서버가 기동 시 `Schema-validation` 오류로 즉시 실패합니다.

### 2. 환경변수 설정

서버는 다음 4개 환경변수를 읽어 부팅합니다. 시크릿은 코드/저장소에 포함하지 않고 환경변수로 주입합니다 (12-factor app 원칙).

| 변수 | 예시 값 | 비고 |
|---|---|---|
| `DB_URL` | `jdbc:mysql://localhost:3306/caffeine_tracker?serverTimezone=UTC&characterEncoding=UTF-8&useUnicode=true&allowPublicKeyRetrieval=true&useSSL=false` | 쿼리스트링 포함 |
| `DB_USERNAME` | `caffeine` | |
| `DB_PASSWORD` | (위 1번 단계에서 설정한 MySQL 비밀번호) | |
| `JWT_SECRET` | `openssl rand -base64 48` 결과 | UTF-8 32바이트 이상 필수 |

설정 방법은 다음 두 가지 중 하나를 선택합니다.

#### Option A — `~/.zshrc` (간단, 머신 전역)

```bash
# ~/.zshrc 끝에 추가
export DB_URL='jdbc:mysql://localhost:3306/caffeine_tracker?serverTimezone=UTC&characterEncoding=UTF-8&useUnicode=true&allowPublicKeyRetrieval=true&useSSL=false'
export DB_USERNAME='caffeine'
export DB_PASSWORD='your-mysql-password'
export JWT_SECRET="$(openssl rand -base64 48)"

# 적용
source ~/.zshrc
```

> `&`가 포함된 URL은 **반드시 작은따옴표**로 감쌀 것 (백그라운드 실행으로 해석되는 것 방지).

#### Option B — IntelliJ Run Configuration (프로젝트별 격리)

`Run → Edit Configurations → ServerApplication → Environment variables`에 다음을 입력:

```
DB_URL=jdbc:mysql://localhost:3306/caffeine_tracker?serverTimezone=UTC&characterEncoding=UTF-8&useUnicode=true&allowPublicKeyRetrieval=true&useSSL=false;DB_USERNAME=caffeine;DB_PASSWORD=your-mysql-password;JWT_SECRET=your-jwt-secret
```

> IntelliJ를 Finder에서 띄우면 zshrc의 `export`를 못 읽습니다. 터미널에서 `idea .`로 띄우거나, 위 Run Config에 직접 입력하세요.

### 3. 서버 실행

```bash
./gradlew bootRun
```

서버는 `http://localhost:8080`에서 기동됩니다. 기동 시 엔티티와 DB 스키마를 대조(`ddl-auto: validate`)하므로 1번 단계의 DDL이 적용되어 있어야 합니다.

> 환경변수가 빠져 있으면 부팅 시 `Could not resolve placeholder 'DB_URL'` 같은 에러로 즉시 실패합니다. 4개 변수가 모두 설정되어 있는지 확인하세요.

## API 엔드포인트

> 원본은 아래 표가 아니라 [`docs/api/api.md`](docs/api/api.md)입니다 (요청·응답 필드, 예시, 에러 코드) ·
> 기계용은 [`docs/api/openapi.yaml`](docs/api/openapi.yaml) (OpenAPI 3.0.3 — Swagger Editor·Postman에서 열기).
> 엔드포인트 목록·DTO 필드·에러 코드는 `ApiDocsConsistencyIT`가 코드와 대조한다.
>
> **아래 표에는 경로와 기능 이름만 적는다.** 동작 조건·임계값 같은 세부를 여기 옮겨 적으면,
> 이 표는 테스트가 대조하지 않는 유일한 사본이라 조용히 낡는다.

**인증**
| 메서드 | 경로 | 기능 | 인증 |
|---|---|---|---|
| `POST` | `/api/auth/signup` | 회원가입 | 불필요 |
| `POST` | `/api/auth/login` | 로그인 | 불필요 |
| `POST` | `/api/auth/refresh` | 토큰 재발급 | 불필요 |
| `POST` | `/api/auth/logout` | 로그아웃 | Bearer |
| `GET`  | `/api/me` | 내 정보 조회 | Bearer |

**설정 · 카페인 · 알림** (전부 Bearer)
| 메서드 | 경로 | 기능 |
|---|---|---|
| `GET`/`PUT` | `/api/settings` | 설정 조회 · 설정 변경 |
| `POST`/`PUT`/`DELETE` | `/api/caffeine-records[/{id}]` | 카페인 기록 추가 · 수정 · 삭제 |
| `GET` | `/api/caffeine-records?now=&tz=` | 오늘 기록 조회 |
| `GET` | `/api/caffeine/today?now=&tz=` | 오늘의 카페인 현황 |
| `GET` | `/api/notifications/plan?now=&tz=` | 알림 계획 조회 |

**수면 · 학습** (전부 Bearer)
| 메서드 | 경로 | 기능 |
|---|---|---|
| `POST` | `/api/sleep/samples` | 수면 샘플 업로드 |
| `GET` | `/api/sleep/summary?date=&tz=` | 수면 요약 조회 |
| `POST` | `/api/learning/run?tz=` | 반감기 학습 실행 |
| `GET` | `/api/learning/observations` | 학습 관측 이력 조회 |
| `GET` | `/api/learning/dashboard` | 학습 대시보드 |

> 연산 엔드포인트는 기기 로컬 타임존 재현을 위해 `tz`(IANA, 예 `Asia/Seoul`)와 필요 시 `now`(ISO-8601+오프셋)를 받는다.

### 공통 응답 구조

모든 API는 아래 봉투로 응답합니다. 키 4개는 값이 없어도 항상 존재합니다(`null`).

| 키 | 성공 | 실패 |
|---|---|---|
| `success` | `true` | `false` |
| `data` | 페이로드(객체·배열) | `null` |
| `error` | `null` | `{ "code": "...", "fieldErrors": [...] }` — `fieldErrors`는 검증 실패(`VALIDATION_FAILED`)에만 |
| `message` | `null` | 사용자에게 보여줄 문장 |

```json
{ "success": true,  "data": { "accessToken": "...", "refreshToken": "...", "tokenType": "Bearer", "expiresIn": 3600, "refreshExpiresIn": 1209600 }, "error": null, "message": null }
{ "success": false, "data": null, "error": { "code": "INVALID_CREDENTIALS" }, "message": "이메일 또는 비밀번호가 올바르지 않습니다." }
{ "success": false, "data": null, "error": { "code": "VALIDATION_FAILED", "fieldErrors": [ { "field": "email", "message": "..." } ] }, "message": "입력값이 올바르지 않습니다." }
```

- HTTP 상태 코드는 그대로 의미를 가집니다(201 생성, 400/401/404/409 등). 바디 없는 성공(로그아웃·삭제)도 204 대신 `200` + `data: null`.
- `error.code`와 HTTP 상태 매핑의 단일 출처는 `global/error/ErrorCode.java`. 시큐리티 단계의 401(`UNAUTHORIZED`)·403(`FORBIDDEN`)과
  프레임워크가 정하는 400(`INVALID_PARAMETER`: 잘못된 `tz`·필수 파라미터 누락)·404(`NOT_FOUND`)·405(`METHOD_NOT_ALLOWED`)도 같은 봉투입니다.

### 사용 예시

**회원가입**

```bash
curl -X POST http://localhost:8080/api/auth/signup \
  -H "Content-Type: application/json" \
  -d '{"email":"test@example.com","password":"password123","nickname":"테스터"}'
```

**로그인**

```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"test@example.com","password":"password123"}'
```

응답 `data.accessToken`을 `Authorization: Bearer <token>` 헤더로 전송하여 인증된 API를 호출합니다.

## 빌드 & 테스트

```bash
# 컴파일만 빠르게 확인 (DB 불필요)
./gradlew build -x test

# 전체 빌드 (테스트는 H2 임베디드 DB로 동작, 환경변수 불필요)
./gradlew build
```

## 프로젝트 구조

```
src/main/java/com/jongbeom/server/
├── domain/
│   ├── auth/           # 회원가입, 로그인, JWT 발급 (+refresh/ 리프레시 토큰 하위 모듈)
│   ├── user/           # 사용자 정보 조회
│   ├── settings/       # 유저 설정 + 학습 상태 (연산 입력값)
│   ├── caffeine/       # 카페인 기록 CRUD + 잔류량/마감시각 계산 — 레이어 구성 예시:
│   │   ├── controller/ │ service/ │ repository/ │ entity/ │ dto/ │ exception/
│   ├── sleep/          # 수면 원시 샘플 업로드 + 병합/요약
│   ├── learning/       # 베이지안 반감기 학습 + 대시보드
│   ├── notification/   # 로컬 알림 계획 (서버가 종류·시각·문구 계산, iOS 가 예약)
│   └── calc/           # 순수 연산(iOS Swift 포팅): 약동학·수면병합·베이지안·타임존 — 레이어 없음
├── global/
│   ├── config/         # SecurityConfig, JwtConfig(+JwtProperties), ClockConfig
│   ├── error/          # 전역 에러 (BusinessException·ErrorCode·ApiError·GlobalExceptionHandler·JsonSecurityErrorHandler=401/403)
│   ├── web/            # 컨트롤러 공용 (ApiResponse 응답 봉투, CurrentUser)
│   └── entity/         # BaseTimeEntity
└── ServerApplication.java   # 패키지 루트 고정 — 컴포넌트 스캔 베이스

docs/
├── README.md           # 문서 색인 — 어떤 문서가 어디에 있는지
├── features.md         # 화면별 기능 목록 — 동작 조건·임계값의 기준점
├── api/
│   ├── api.md          # API 명세(사람용) — 필드 표 · 예시 · 에러 코드
│   └── openapi.yaml    # API 명세(기계용, OpenAPI 3.0.3) — 테스트가 코드와 대조
├── db/
│   ├── db-schema.sql   # DB 스키마 DDL 원본 — 사용자가 직접 실행, 테스트는 H2 에 적용해 엔티티와 대조
│   └── db-schema.md    # 스키마 설명 · 변경 절차 · 변경 이력
└── ops/
    └── 운영-가이드.md    # 수동 배포 절차 · 준비 상태 체크리스트
```

> 각 도메인 내부는 `controller/`·`service/`·`repository/`·`entity/`·`dto/`·`exception/` 레이어 패키지로 구성한다.
> 레이어가 아닌 도메인 컴포넌트(`domain/auth/JwtTokenProvider`, `domain/learning/LearningSkipReason` 등)와
> 하위 기능 모듈(`auth/refresh/`)은 도메인 루트에 둔다.

> `domain/calc/`의 도메인 수식은 iOS Swift에서 포팅했으며, Swift 단위테스트의 입력→기대값을 **골든 테스트**로 복제해 부동소수 정합을 검증한다.
