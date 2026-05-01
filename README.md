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

### 2. `application-local.yaml` 작성

`src/main/resources/application-local.yaml` 파일은 시크릿 보호를 위해 `.gitignore`에 등록되어 있습니다. 직접 생성해 주세요.

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/caffeine_tracker
    username: caffeine
    password: <YOUR_PASSWORD>
    driver-class-name: com.mysql.cj.jdbc.Driver

app:
  jwt:
    secret: <BASE64_ENCODED_HS256_KEY>
```

JWT secret은 HS256용으로 최소 32바이트(256비트) 이상이어야 하며, 다음 명령어로 생성할 수 있습니다.

```bash
openssl rand -base64 32
```

### 3. 서버 실행

```bash
./gradlew bootRun
```

서버는 `http://localhost:8080`에서 기동됩니다.

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

# 전체 빌드 (테스트 포함, DB 기동과 application-local.yaml 필요)
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
