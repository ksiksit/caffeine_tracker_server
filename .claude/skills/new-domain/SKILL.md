---
name: new-domain
description: 새 도메인 패키지 추가 절차 — 레이어 배치·SecurityConfig·DDL·API 문서·테스트 체크리스트
---

# 새 도메인 추가 절차

CLAUDE.md의 배치 규칙·동반 갱신 규칙을 전제로, 빠뜨리기 쉬운 순서만 적는다. 기존 `domain/caffeine/`을 견본으로 삼는다.

1. **패키지**: `com.jongbeom.server.domain.<도메인>/` 아래 `controller/`·`service/`·`repository/`·`entity/`·`dto/`·`exception/` 생성.
   테스트도 `src/test/java/...`에 같은 구조로 미러링.
2. **엔티티**: `BaseTimeEntity` 상속, `@NoArgsConstructor(access = PROTECTED)`, `@Data` 금지. 동시에 `docs/db-schema.sql`에
   `CREATE TABLE` 추가, `docs/db-schema.md` 변경 이력에 기존 DB용 `CREATE`/`ALTER` 기록. 테스트가 이 DDL을 H2에 적용해 엔티티와 대조한다.
3. **예외**: `exception/`에 `BusinessException` 상속 + `ErrorCode` 추가. 핸들러 등록 불필요.
4. **DTO**: record, `*Request`/`*Response`, Jakarta Validation. 컨트롤러 인자 `@Valid`.
5. **컨트롤러**: `ApiResponse<T>` 직접 반환, 201은 `@ResponseStatus`. 타임존은 `ZoneId tz`. userId는 `CurrentUser.id(jwt)`.
6. **보안**: `global/config/SecurityConfig.java`의 `authorizeHttpRequests`에 경로 등록 (미등록 = authenticated).
7. **API 문서**: `docs/api.md`와 `docs/openapi.yaml` 둘 다 갱신 (엔드포인트·DTO 필드·ErrorCode).
8. **테스트**: JUnit 5, 통합 테스트는 `*IT`. `./gradlew build`로 `ApiDocsConsistencyIT`와 DDL 대조까지 통과 확인.
