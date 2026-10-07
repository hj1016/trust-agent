# TrustAgent 작업 규칙

- 작업 전 `CLAUDE.md`, `docs/development/DEVELOPMENT_RULES.md`와 관련 최신 ADR/Task를 읽습니다.
- 최종 기획서는 제품 기준선으로 사용하고 실제 구현 상태는 README와 ADR에 기록합니다.
- `PUBLIC_KB`, `SYNTHETIC_INTERNAL`, `SYNTHETIC_WORK`, `DERIVED`를 경로, schema, API, 화면, 테스트에서 구분합니다.
- 합성 공문, 규정, 기업, 신청, 상담, 승인 자료를 실제 KB 내부자료처럼 표현하지 않습니다.
- 대출 승인과 거절, 신용등급, 최종 한도, 최종 금리를 결정하는 기능을 만들지 않습니다.
- 2주 MVP에서는 넓고 미완성인 구현보다 하나의 완결된 E2E 흐름을 우선합니다.
- 테스트 evidence가 없는 기능은 완료로 표시하지 않습니다.
- 일반 설명과 프로젝트 문서는 한국어로 작성하고 코드, API, schema, field name과 고유 기술 용어는 영어를 사용할 수 있습니다.
- 제목과 설명에서 중간점 사용을 피합니다.
- 계획과 완료 보고에는 작업의 담당 역할, 필요한 이유, 검토한 대안, 위험과 대응, 테스트 evidence, 다음 작업과의 연결을 기록합니다.

## AI-native SDLC 현재 기준

- 작업 전 `CLAUDE.md`와 `docs/development/DEVELOPMENT_RULES.md`를 먼저 읽고 Source of Truth 우선순위를 적용합니다.
- Task는 `docs/development/TASK_TEMPLATE.md`로 작성하고 완료 전 `docs/development/REVIEW_CHECKLIST.md`를 적용합니다.
- Core 업무 DB는 PostgreSQL을 유지합니다(ADR-009). Core Tool API는 AI 서비스용 읽기 전용 Tool 2개(`applicable_checklist`, `rule_evidence`)와 test/demo 서비스 토큰 인증, 감사 기록 범위로 구현됐습니다(TASK-008, ADR-011). Elasticsearch 검색과 FastAPI AI 서비스, 사용자별 인증·권한은 아직 구현되지 않았습니다. 과거 ADR/evidence는 Pre-SDLC 구현 근거로 보존하며 현재 기준의 검수 통과로 간주하지 않습니다.
- Task/Issue/PR 제목은 한국어 중심, 커밋은 feat/fix/test/refactor/docs/chore 영문 타입 + 한국어 변경 설명입니다. AI 판단 근거는 Task와 ADR에 남깁니다.
