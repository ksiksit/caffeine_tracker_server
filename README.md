# Caffeine Tracker Server

카페인 섭취량을 기록·조회하는 모바일/웹 애플리케이션의 백엔드 REST API 서버입니다. 졸업 작품(캡스톤 디자인) 프로젝트로 진행 중입니다.

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

테이블 스키마는 애플리케이션 기동 시 Flyway가 `src/main/resources/db/migration/V1__init.sql`로 자동 생성합니다.

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

| 메서드 | 경로 | 설명 | 인증 |
|---|---|---|---|
| `POST` | `/api/auth/signup` | 회원가입 | 불필요 |
| `POST` | `/api/auth/login` | 로그인 (JWT 발급) | 불필요 |
| `GET`  | `/api/me`          | 내 정보 조회 | Bearer 토큰 필수 |

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
├── auth/           # 회원가입, 로그인, JWT 발급
├── user/           # User 엔티티 및 사용자 정보 조회
├── config/         # SecurityConfig, JwtConfig
├── common/         # BaseTimeEntity, 전역 예외 처리
└── ServerApplication.java
```
