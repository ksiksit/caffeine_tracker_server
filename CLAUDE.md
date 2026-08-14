# CLAUDE.md

캡스톤 카페인 트래커 백엔드 REST API 서버. **Java 21 / Spring Boot 3.5 / MySQL 8**.

> 이 파일은 코드 작업에 필요한 핵심만 담는다. 스택·환경변수·로컬 실행·API 예시 등
> 사람용 온보딩 상세는 `README.md` 참조 (중복 회피).

## 명령어

- `./gradlew build` — 빌드 + 테스트 (테스트는 H2로 동작, 환경변수 불필요)
- `./gradlew build -x test` — 빠른 컴파일 확인
- `./gradlew test` — 테스트만
- `./gradlew bootRun` — 로컬 실행 (환경변수 4개 필요, 상세는 README)

## 구조

`com.jongbeom.server` — 도메인(feature)별 구성, 각 도메인 내부는 Controller→Service→Repository 3계층.
`auth/`(+`auth/refresh/`), `user/`, `settings/`, `caffeine/`, `sleep/`, `learning/`, `calc/`(순수 연산), `config/`, `common/`(+`common/error/`, `common/web/`). 상세 트리는 README 참조.
도메인 예외는 `common/error/BusinessException`(ErrorCode 보유) 상속 — 핸들러 등록 불필요.
컨트롤러의 JWT userId 추출은 `common/web/CurrentUser.id(jwt)` 사용.

## 도메인 개요 (thin-client 서버)

iOS 앱의 도메인 연산을 서버로 이관 완료. 앱은 입력을 보내고 서버가 계산·저장한다(thin-client).
- `settings` — 유저 설정 + 학습 상태(반감기·건강상태·취침·학습 mean/variance). 연산이 읽는 입력값.
- `caffeine` — 카페인 기록 CRUD + `GET /api/caffeine/today`(잔류량 차트·마감시각을 서버가 settings 읽어 계산).
- `sleep` — HealthKit 원시 수면 샘플 업로드 + 병합·SOL·요약(앱은 읽기만, 병합은 서버).
- `learning` — 카페인 잔량 + 수면 SOL로 베이지안 반감기 학습 + 대시보드 통계.
- `calc/` — iOS Swift에서 **포팅한 순수 연산**: `Pharmacokinetics`(약동학)·`SleepMerger`/`SleepSummaryCalc`(수면)·`BayesianHalfLifeUpdater`/`BedtimeExtractor`/`LearningStatsCalc`(학습)·`LocalCalendar`(타임존). 상수(반감기 clamp 3~7h, alpha15/beta0.10/sigmaObs10, trust region 0.5h 등)는 도메인 근거값 — **임의 변경 금지**, iOS 테스트값과 골든 일치 유지.

## 코드 컨벤션 + 새 기능 추가

인증 외에 settings·caffeine·sleep·learning 도메인이 구현되어 있다 — 새 작업은 대개 도메인 확장 또는 `calc` 수정이다.

**새 도메인 추가 순서:**
1. `com.jongbeom.server.<도메인>/` 패키지 생성 — Controller / Service / Repository / Entity /
   `dto/` / `exception/` (기존 `auth/`, `user/` 패턴 그대로)
2. 새 엔드포인트 경로를 `config/SecurityConfig.java`의 `authorizeHttpRequests`에 등록
   (등록 안 하면 기본 `authenticated` 처리)
3. DB 스키마는 `src/main/resources/db/migration/V{n}__{설명}.sql` Flyway 마이그레이션으로 추가
   (`ddl-auto: validate`라 엔티티만 바꾸면 부팅 실패)
4. 테스트 추가 — JUnit 5, 통합 테스트는 `*IT` 접미사 (테스트는 H2로 자동 실행)

**코드 규칙:**
- DTO는 Java `record`, `*Request` / `*Response` 네이밍
- 엔티티: `BaseTimeEntity` 상속, `@NoArgsConstructor(access = PROTECTED)`, `@Data` 금지
- 예외: 도메인별 커스텀 예외를 `*/exception/`에 두고 `GlobalExceptionHandler`가 일괄 처리
  → `ErrorResponse` 응답. 컨트롤러/서비스에서 ad-hoc try/catch 지양
- 검증: DTO에 Jakarta Validation 어노테이션 + 컨트롤러 인자 `@Valid`
- Lombok: `@Getter`, `@RequiredArgsConstructor`, `@Slf4j`

## 주의사항

- 환경변수 `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` / `JWT_SECRET` 4개 없으면 부팅 즉시 실패.
  `JWT_SECRET`은 32바이트 이상
- `application-local.yaml`은 gitignore됨 (로컬 DB/JWT 시크릿 보관처)
- 리프레시 토큰은 DB에 SHA-256 해시로만 저장 (평문 미저장)
- **§타임존 계약**: `timestamp`/`start`/`end`는 UTC `Instant`로 저장. "하루 경계·취침시각·24h 윈도우" 같은
  *로컬 달력 연산*만 요청 `tz`(IANA)로 `LocalCalendar`에서 변환. 연산 엔드포인트는 `tz`(+필요시 `now`) 파라미터 필수.
- **§JSON 날짜**: body/쿼리 날짜는 ISO-8601 + 오프셋(`OffsetDateTime`). 응답 시각은 UTC `Instant`(`...Z`).
- **learning**: 배치는 오래된→최신 순으로 **순차 prior 체이닝**(매 night마다 settings의 갱신 prior 재사용).
  `half_life_observations`는 `UNIQUE(user_id, obs_date)`로 같은 날 1회만 학습. 관측 저장 성공 후에만 settings 반영.
- 시간 의존 로직(학습 후보 날짜)은 주입형 `Clock`(`config/ClockConfig`) 사용 — 테스트에서 `@MockBean Clock`으로 고정.
- Flyway는 V6(`half_life_observations`)까지. 엔티티 변경 시 반드시 `V{n}` 동반(`ddl-auto: validate`).

## 배포

> 현재 방식: **수동 배포** (CI는 GHCR 이미지 push까지만 자동화). 마지막 갱신: 2026-08-14.
> "배포 준비 됐냐"는 질문은 **이 저장소(레포)의 배포 준비 상태만** 확인해 답한다.
> 운영 서버(EC2)·운영 DB(RDS)는 사용자가 직접 준비·관리하는 외부 인프라이므로
> 준비된 것으로 간주하고, 상태를 다시 검증하려 하지 말 것. 진행되면 체크리스트/날짜를 갱신할 것.

- **완료**: Dockerfile · docker-compose(.prod).yml · CI(빌드+테스트→GHCR push) · prod 프로파일 · Flyway
- **인프라(사용자 관리, 준비 완료)**: 운영 서버(EC2) · 운영 DB(RDS)
- **미완료**: HTTPS/리버스 프록시 · GHCR private 인증
- **안 함(범위 외)**: CD 자동화 — 수동 배포 유지. 자동화 제안하지 말 것.

수동 배포 절차는 `docs/운영-가이드.md` 참조 — **EC2에 인터넷이 없어 `docker compose pull` 불가**.
PC에서 이미지 `docker save` → `scp` → EC2에서 `docker load` 후
`docker compose -f docker-compose.prod.yml up -d` → `curl localhost:8080/actuator/health` 로 `UP` 확인.
