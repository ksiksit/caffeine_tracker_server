# CLAUDE.md

캡스톤 카페인 트래커 백엔드 REST API. Java 21 / Spring Boot 3.5 / MySQL 8.
iOS 앱은 thin-client, 계산·저장은 서버가 한다. 사람용 온보딩(스택·환경변수·실행·API 예시)과
패키지 트리는 README.md 참조.

## 명령어
- `./gradlew build` — 빌드+테스트 (테스트는 H2, 환경변수 불필요)
- `./gradlew build -x test` — 컴파일만
- `./gradlew bootRun` — 로컬 실행 (환경변수 4개 필요, README 참조)

## 배치 규칙
- `com.jongbeom.server.domain.<도메인>/` 아래 `controller/`·`service/`·`repository/`·`entity/`·`dto/`·`exception/`.
  테스트도 같은 구조로 미러링, 통합 테스트는 `*IT` 접미사.
- 레이어가 아닌 컴포넌트(`JwtTokenProvider`, `LearningSkipReason`)와 하위 모듈(`auth/refresh/`)은 도메인 루트.
- 인프라는 `global/`(config·error·web·entity). `ServerApplication`은 패키지 루트 고정(컴포넌트 스캔 베이스).
- 새 엔드포인트는 `SecurityConfig.authorizeHttpRequests`에 등록 (미등록 = authenticated).

## 코드 규칙
- DTO는 record, `*Request`/`*Response`, Jakarta Validation + 컨트롤러 `@Valid`.
- 엔티티: `BaseTimeEntity` 상속, `@NoArgsConstructor(access = PROTECTED)`, `@Data` 금지.
- 컨트롤러는 `ApiResponse<T>` 직접 반환(`ok(data)`/`ok()`, 201은 `@ResponseStatus`). `ResponseEntity`·DTO 직접 반환 금지.
  타임존 파라미터는 `ZoneId tz`로 바인딩. JWT userId는 `CurrentUser.id(jwt)`.
- 예외는 `*/exception/`에 `BusinessException` 상속으로 두면 `GlobalExceptionHandler`가 봉투로 변환.
  실패 봉투를 만드는 곳은 그 핸들러와 `JsonSecurityErrorHandler`(401/403) 둘뿐 — 컨트롤러/서비스 ad-hoc try/catch 금지.
- Lombok: `@Getter`, `@RequiredArgsConstructor`, `@Slf4j`.

## 바꾸면 같이 바꿀 것
- 엔티티/테이블 → `docs/db-schema.sql`의 CREATE 갱신 + `docs/db-schema.md` 변경 이력에 ALTER 기록.
  앱은 스키마를 만들지 않고(`ddl-auto: validate`) 테스트가 같은 DDL을 H2에 적용해 대조하므로 안 고치면 빌드 실패.
- 엔드포인트/DTO/ErrorCode → `docs/api.md` + `docs/openapi.yaml` 둘 다. `ApiDocsConsistencyIT`가 누락을 잡는다(설명 문구는 못 잡음).

## 절대 규칙
- `domain/calc/` 상수(반감기 clamp 3~7h, alpha 0.15/beta 0.10/sigmaObs 10, trust region 0.5h 등)는 도메인 근거값 — 변경 금지.
  iOS Swift 포팅이라 골든 테스트와 일치해야 한다.
- Flyway/Liquibase/부팅 시 자동 스키마 적용 재도입 제안 금지 — 수동 적용이 결정 사항.
- CD 자동화 제안 금지 — 수동 배포 유지가 결정 사항.
- 리프레시 토큰은 DB에 SHA-256 해시로만 저장, 평문 금지.

## 도메인 계약 (코드만 보고는 의도인지 알 수 없는 것)
- 타임존: `timestamp`/`start`/`end`는 UTC `Instant`로 저장. 하루 경계·취침시각·24h 윈도우 같은 로컬 달력 연산만
  요청 `tz`(IANA)로 `LocalCalendar`에서 변환. 연산 엔드포인트는 `tz`(+필요시 `now`) 파라미터 필수.
- JSON 날짜: 입력은 ISO-8601+오프셋(`OffsetDateTime`), 응답은 UTC `Instant`(`...Z`).
- learning: 오래된→최신 순 순차 prior 체이닝(매 night마다 settings의 갱신 prior 재사용).
  `half_life_observations`는 `UNIQUE(user_id, obs_date)`. 관측 저장 성공 후에만 settings 반영.
- 알림: 서버는 푸시를 보내지 않는다(운영 EC2 외부 인터넷 불가 → APNs 미사용). `GET /api/notifications/plan`이 `now` 이후에
  울릴 항목(종류·시각·문구)만 계산해 내려주고 iOS가 로컬 알림으로 예약한다. 설정 `notifications.*`가 꺼진 종류는 생략.
- 시간 의존 로직은 주입 `Clock`(`ClockConfig`) 사용, 테스트는 `@MockBean Clock`으로 고정.

## 환경
- `DB_URL`/`DB_USERNAME`/`DB_PASSWORD`/`JWT_SECRET` 없으면 부팅 즉시 실패. `JWT_SECRET`은 32바이트 이상.
  `application-local.yaml`은 gitignore(로컬 시크릿 보관처).

## 배포
- 수동 배포. "배포 준비 됐냐"는 이 레포의 준비 상태만 답한다. EC2·RDS는 사용자 관리 인프라 — 준비된 것으로 간주, 재검증 금지.
- 절차·체크리스트·현재 상태: `docs/운영-가이드.md`.
