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
- **Flyway** — DB 스키마 마이그레이션
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

테이블 스키마는 애플리케이션 기동 시 Flyway가 `src/main/resources/db/migration/`의 마이그레이션(V1~V6)을 순서대로 적용해 자동 생성합니다.

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

서버는 `http://localhost:8080`에서 기동되며 Flyway가 `src/main/resources/db/migration/`의 마이그레이션을 자동 적용합니다.

> 환경변수가 빠져 있으면 부팅 시 `Could not resolve placeholder 'DB_URL'` 같은 에러로 즉시 실패합니다. 4개 변수가 모두 설정되어 있는지 확인하세요.

## API 엔드포인트

**인증**
| 메서드 | 경로 | 설명 | 인증 |
|---|---|---|---|
| `POST` | `/api/auth/signup` | 회원가입 | 불필요 |
| `POST` | `/api/auth/login` | 로그인 (JWT 발급) | 불필요 |
| `POST` | `/api/auth/refresh` | 토큰 갱신(로테이션) | 불필요 |
| `POST` | `/api/auth/logout` | 로그아웃 | Bearer |
| `GET`  | `/api/me` | 내 정보 조회 | Bearer |

**설정 · 카페인** (전부 Bearer)
| 메서드 | 경로 | 설명 |
|---|---|---|
| `GET`/`PUT` | `/api/settings` | 설정·학습 상태 조회/갱신(없으면 기본값 생성) |
| `POST`/`PUT`/`DELETE` | `/api/caffeine-records[/{id}]` | 카페인 기록 CRUD |
| `GET` | `/api/caffeine/today?now=&tz=` | **서버 계산**: 잔류량 차트·현재/취침 잔량·마감시각 |

**수면 · 학습** (전부 Bearer)
| 메서드 | 경로 | 설명 |
|---|---|---|
| `POST` | `/api/sleep/samples` | HealthKit 원시 수면 샘플 업로드(client_uuid 멱등) |
| `GET` | `/api/sleep/summary?date=&tz=` | **서버 계산**: 병합·총수면·효율·SOL·단계별 시간 |
| `POST` | `/api/learning/run?tz=` | **서버 계산**: 미학습 night 베이지안 배치 학습 |
| `GET` | `/api/learning/observations` · `/api/learning/dashboard` | 관측 이력 · 대시보드 통계(CI·R²·RMSE·히스토그램) |

> 연산 엔드포인트는 기기 로컬 타임존 재현을 위해 `tz`(IANA, 예 `Asia/Seoul`)와 필요 시 `now`(ISO-8601+오프셋)를 받는다.

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

응답으로 받은 access token을 `Authorization: Bearer <token>` 헤더로 전송하여 인증된 API를 호출합니다.

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
│   └── calc/           # 순수 연산(iOS Swift 포팅): 약동학·수면병합·베이지안·타임존 — 레이어 없음
├── global/
│   ├── config/         # SecurityConfig, JwtConfig(+JwtProperties), ClockConfig
│   ├── error/          # 전역 예외 처리 (BusinessException·ErrorCode·GlobalExceptionHandler)
│   ├── web/            # 컨트롤러 공용 헬퍼 (CurrentUser)
│   └── entity/         # BaseTimeEntity
└── ServerApplication.java   # 패키지 루트 고정 — 컴포넌트 스캔 베이스

src/main/resources/db/migration/   # Flyway V1(users)~V6(half_life_observations)
```

> 각 도메인 내부는 `controller/`·`service/`·`repository/`·`entity/`·`dto/`·`exception/` 레이어 패키지로 구성한다.
> 레이어가 아닌 도메인 컴포넌트(`domain/auth/JwtTokenProvider`, `domain/learning/LearningSkipReason` 등)와
> 하위 기능 모듈(`auth/refresh/`)은 도메인 루트에 둔다.

> `domain/calc/`의 도메인 수식은 iOS Swift에서 포팅했으며, Swift 단위테스트의 입력→기대값을 **골든 테스트**로 복제해 부동소수 정합을 검증한다.
