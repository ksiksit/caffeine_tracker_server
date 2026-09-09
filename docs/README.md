# 문서 색인

서버 문서는 용도별로 나눠 둔다. **여기에는 목록만 적는다** — 동작 세부는 각 문서가 원본이다.

| 문서 | 무엇 | 누가 대조하나 |
|---|---|---|
| [`features.md`](features.md) | 화면별 기능 목록. 동작 조건·임계값을 적는 곳 | 사람 (괄호 안 조건이 테스트 케이스 목록) |
| [`api/api.md`](api/api.md) | API 명세(사람용) — 필드 표·요청/응답 예시·에러 코드 | `ApiDocsConsistencyIT` (엔드포인트·필드 이름) |
| [`api/openapi.yaml`](api/openapi.yaml) | API 명세(기계용, OpenAPI 3.0.3) — Swagger Editor·Postman | `ApiDocsConsistencyIT` (paths·schemas·enum) |
| [`db/db-schema.sql`](db/db-schema.sql) | DB 스키마 DDL 원본. 사용자가 직접 실행 | 테스트가 H2 에 적용해 엔티티와 대조 |
| [`db/db-schema.md`](db/db-schema.md) | 스키마 설명·변경 절차·변경 이력(ALTER) | 사람 |
| [`ops/운영-가이드.md`](ops/운영-가이드.md) | 수동 배포 절차·준비 상태 체크리스트 | 사람 |

무엇을 바꿀 때 어떤 문서를 같이 고치는지는 `CLAUDE.md`의 "바꾸면 같이 바꿀 것" 절에 있다.
스택·환경변수·로컬 실행은 루트 [`README.md`](../README.md).
