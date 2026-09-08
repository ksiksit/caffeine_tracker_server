---
name: new-domain
description: 새 도메인 패키지 추가 절차 — 레이어 배치·SecurityConfig·DDL·API 문서·테스트 체크리스트
---

# 새 도메인 추가 절차

CLAUDE.md의 배치 규칙·동반 갱신 규칙을 전제로, 빠뜨리기 쉬운 순서만 적는다. 기존 `domain/caffeine/`을 견본으로 삼는다.

1. **패키지**: `com.jongbeom.server.domain.<도메인>/` 아래 `controller/`·`service/`·`repository/`·`entity/`·`dto/`·`exception/` 생성.
   테스트도 `src/test/java/...`에 같은 구조로 미러링.
2. **엔티티**: `BaseTimeEntity` 상속, `@NoArgsConstructor(access = PROTECTED)`, `@Data` 금지. 동시에 `docs/db/db-schema.sql`에
   `CREATE TABLE` 추가, `docs/db/db-schema.md` 변경 이력에 기존 DB용 `CREATE`/`ALTER` 기록. 테스트가 이 DDL을 H2에 적용해 엔티티와 대조한다.
3. **예외**: `exception/`에 `BusinessException` 상속 + `ErrorCode` 추가. 핸들러 등록 불필요.
4. **DTO**: record, `*Request`/`*Response`, Jakarta Validation. 컨트롤러 인자 `@Valid`.
5. **컨트롤러**: `ApiResponse<T>` 직접 반환, 201은 `@ResponseStatus`. 타임존은 `ZoneId tz`. userId는 `CurrentUser.id(jwt)`.
6. **보안**: `global/config/SecurityConfig.java`의 `authorizeHttpRequests`에 경로 등록 (미등록 = authenticated).
7. **API 문서**: `docs/api/api.md`와 `docs/api/openapi.yaml` 둘 다 갱신 (엔드포인트·DTO 필드·ErrorCode).
   `api.md`의 필드 표 표기는 그 문서 "필드 표 표기" 절을 따른다 — 파서가 읽는다.
8. **사람용 문서**: `ApiDocsConsistencyIT`가 대조하지 않는 사본이라 빠뜨려도 빌드가 통과한다. 잊지 말 것:
   `docs/features.md` 표에 기능 한 줄(동작 조건·임계값은 **여기** 적는다 — 그 괄호가 테스트 케이스 목록이 된다),
   `README.md` API 표에 **경로와 그 기능 이름만** 한 줄(동작 세부는 옮기지 않는다).
9. **테스트**: JUnit 5, 통합 테스트는 `*IT`. `./gradlew build`로 `ApiDocsConsistencyIT`와 DDL 대조까지 통과 확인.
