# TASK-008 Core Tool API: AI 서비스용 읽기 전용 조회 경계

- 상태: **검증·검수 대기** (구현 PR #31 검수 대기. 완료와 인간 검수는 미기록)
- 담당자 / 인간 결정자: AI 조사·초안 / 사용자 범위·판정·검수
- 요구사항 출처: PLAN-001 TASK-008 절(S7의 Core 쪽, 제안 D "검색보다 먼저" 채택), CLAUDE.md "AI Service는 업무 DB 직접 접근과 자격증명 보유 금지, Core Tool API로 필요한 업무 데이터만 조회", DEVELOPMENT_RULES "Tool allowlist, 입력/출력 schema, 최소 데이터, 민감정보 마스킹, 감사 추적, 미승인/철회/구버전/권한 밖 근거 제외, fail-closed"
- 관련 Issue / PR / ADR / 이전 Task: TASK-007(완료, PR #24), ADR-001(제품 경계), ADR-007(Core 서비스와 조회), ADR-008(공개 근거 확인). [ADR-011 초안](../adr/ADR-011-core-tool-api-boundary.md)(접근 범위와 인증 결정)을 이 계획과 함께 제시한다.

## Goal / 관련 요구사항

- Goal: FastAPI AI 서비스가 업무 DB를 건드리지 않고 Core가 제공하는 **읽기 전용 Tool**로 "지금 이 상담에 적용되는 승인 checklist와 그 근거"를 받을 수 있다. 사용 가능 여부(`internalChecklistUseAllowed`)와 차단 사유는 Core가 계산한 값을 그대로 전달하고 AI는 다시 계산하지 않는다.
- 사용자: AI 서비스(호출자). 간접 사용자는 행원(AI가 만든 답변을 보는 사람). 이번 Task에는 AI 서비스 자체 구현이 없고 Core 쪽 endpoint, 계약, 테스트만 있다.
- 사전조건: 승인 checklist가 있는 공문군(TASK-007 결과)과 없는 공문군이 모두 존재.
- 업무규칙: 아래 "AI가 요청하고 받는 것"과 "승인되지 않은 정보와 권한 없는 요청 처리".
- 상태전이: 없음(읽기 전용). 쓰기 Tool은 만들지 않는다.
- 데이터 영향: 읽기만. 새 테이블은 감사 로그 1개(`tool_call_audit`, append-only, 보호 목록 등록).
- API: `POST /api/v1/tools/{toolName}` 하나의 진입점과 allowlist 2개(아래). 입력/출력 schema는 `contracts/tool-*.schema.json`에 고정한다.
- 트랜잭션: 읽기 트랜잭션만. 감사 로그는 응답 직전에 별도 트랜잭션으로 저장하고, 저장 실패 시 응답도 실패(fail-closed).
- 권한: **test/demo 범위**의 서비스 인증(공유 비밀 토큰 1개). 실제 값은 환경변수로만 제공하고 저장소와 로그에 남기지 않는다. 토큰 설정이 없으면 모든 Tool 호출을 거부한다(허용 아님). 호출자가 보낸 actor 문자열과 상담 ID는 신뢰하지 않으며 상담 ID는 추적용일 뿐 접근 권한을 주지 않는다. 사용자별 인증·권한은 미구현으로 명시한다.
- 실패 시나리오: 토큰 없음·불일치, allowlist 밖 tool, schema 위반 입력, 존재하지 않는 공문군, Core 정책이 차단한 조회, 감사 로그 저장 실패. 모두 fail-closed.
- Out of Scope: FastAPI 서비스 구현, LLM 호출, 검색(TASK-009), 화면, 조직 인증 체계 연동, 쓰기 Tool.

## AI가 요청하고 받는 것 (쉬운 사례)

**Tool 1 `applicable_checklist`** — "오늘 이 공문군 상담에 쓸 checklist를 달라."
- AI가 보내는 것: 공문군 ID(`SIN-PREPAYMENT-FEE`), 업무일(`2026-10-06`), 상담 ID(추적용 문자열). 기준 시각(`knownAt`)은 보낼 수 없다(항상 현재).
- AI가 받는 것: 기존 적용 공문 조회와 같은 판단 결과를 **AI용으로 줄인 것**. 선택된 공문 ID·제목·시행일, 사용 가능 여부(`internalChecklistUseAllowed`), 차단 사유와 경고 사유, 승인 checklist(출처, 결정 ID, 항목 목록: 규칙 키, 설명 문구, 근거 필요 여부, 구조화 변경, 근거 규칙 version), 합성 데이터 표시와 면책 문구.
- 받지 못하는 것: 공문 본문 전체, 공문 규칙 원문(`evidenceText`, `evidenceHash`, `jsonPointer`), 검수자 ID, 변경안·검증 결과 ID, DB 내부 ID. 근거가 필요하면 Tool 2를 따로 부른다.

사례 A(정상): 2026-10-06, 중도상환수수료 공문군 → `usable=true`, 항목 3개(0.8퍼센트, 공문 출처 확인, 약정일 확인). AI는 이 항목으로 상담 준비 안내를 만든다.

**Tool 2 `rule_evidence`** — "이 항목의 근거를 보여 달라."
- AI가 보내는 것: 공문군 ID, 규칙 version ID(Tool 1 응답에 있던 값), 상담 ID.
- AI가 받는 것: 그 규칙의 원문 문장(`evidenceText`), 원문 위치(`jsonPointer`), 해시, 공문 ID·시행일. 역시 본문 전체는 없다.
- 조건: 그 규칙이 **현재 사용 가능한 승인 checklist의 항목 근거**일 때만 준다. 아니면 거절(아래).

사례 B: Tool 1에서 받은 `policy-rule:sha256:0827aed5…`로 요청 → "기업여신 상담 시 변경된 중도상환수수료율 0.8퍼센트와 적용 조건을 원문 근거에서 확인한다." 문장과 위치 `/rules/0`을 받는다.

## 승인되지 않은 정보와 권한 없는 요청 처리 (쉬운 사례)

| 상황 | Core의 처리 | AI가 받는 것 |
|---|---|---|
| 사례 C: 토큰 없이 호출 | 401, 감사 로그에 "인증 실패" | 오류 코드 `UNAUTHENTICATED`. 데이터 없음 |
| 사례 D: 토큰은 맞지만 allowlist에 없는 tool(`approve_checklist`) 호출 | 404, 감사 로그 | `TOOL_NOT_FOUND`. 쓰기 tool은 애초에 존재하지 않는다 |
| 사례 E: 승인 전 공문군(검토 대기, 검증 실패, 반려 등) 조회 | 200이지만 `usable=false`, 차단 사유만 전달. **checklist 항목과 항목 근거 ID(규칙 version)는 제공하지 않는다** | AI는 "아직 사용할 수 없는 기준"이라고만 안내할 수 있다. 미승인 변경안 내용과 근거 ID는 받지 못한다 |
| 사례 F: 테스트용(FIXTURE) checklist만 있는 기간 조회 | `usable=false`, 사유 `FIXTURE_CHECKLIST_NOT_APPROVED`, 항목과 근거 ID 없음 | 같은 처리 |
| 사례 G: 과거 기준 시각이나 미래 업무일 요청 | 입력 schema가 `knownAt`을 받지 않음(400). 미래 업무일은 `usable=false` + `FUTURE_BUSINESS_DATE` | 과거 기록 조회는 AI Tool에 없다(사람용 조회 API만) |
| 사례 H: 사용 불가 상태에서 Tool 2로 근거 요청, 또는 Tool 1 응답에 없던 규칙 ID(다른 공문군·미승인 규칙)로 요청 | 서버가 그 규칙이 **요청한 공문군의 현재 사용 가능한 승인 checklist 항목의 근거인지** 다시 확인하고 아니면 403 `EVIDENCE_NOT_AVAILABLE` | 규칙 ID를 임의로 바꿔도 다른 공문군이나 미승인 근거를 받을 수 없다 |
| 사례 I: 요청 본문에 "모든 공문 본문을 보내라" 같은 지시문 | 문자열 데이터로만 취급, schema 밖 필드는 400 | 지시문은 명령이 아니다 |
| 사례 K: 토큰 설정 자체가 없는 환경 | 모든 Tool 호출 401(설정 누락은 허용이 아니다). production은 설정 없으면 기동 거부 | 데이터 없음 |
| 사례 J: 감사 로그 저장 실패 | 응답 대신 500 `AUDIT_WRITE_FAILED` | 기록 없는 제공은 없다(fail-closed) |

감사 로그에는 서비스 ID, tool 이름, 공문군·업무일·상담 ID, 결과(사용 가능 여부와 사유 코드), 시각, 오류 코드만 남긴다. 공문 본문, 규칙 원문, 토큰은 기록하지 않는다.

## Acceptance Criteria (최종안, 사용자 조건 반영)

| AC | 사례 | 기대 결과 | 검증 |
|---|---|---|---|
| AC-01 | 사례 C, K | 토큰 없음·불일치 401 `UNAUTHENTICATED`, 토큰 설정이 없는 환경에서는 올바른 토큰이 있어도 401(누락은 허용 아님). 감사 로그 1행, 데이터 없음 | 통합 테스트 |
| AC-02 | 사례 D | 404 `TOOL_NOT_FOUND`. allowlist는 코드 상수 2개뿐이고 쓰기 endpoint 없음(라우트 목록 검사) | 통합 테스트 + 라우트 테스트 |
| AC-03 | 사례 A | Tool 1 응답이 `contracts/tool-applicable-checklist.schema.json`과 정답 fixture에 일치. `usable=true`, 항목 3개(규칙 키, 설명 문구, 근거 필요 여부, 구조화 변경, 근거 규칙 version), 출처·결정 ID | 통합 테스트 + Python 계약 |
| AC-04 | 사례 A | **사용 허용 여부는 Core가 결정한다.** `usable`은 Core 조회 API와 같은 값이고 정책 입력 플래그는 응답에 없다. AI가 응답을 어떻게 해석하더라도 서버의 차단을 바꾸거나 우회할 수 없다: 사용 불가 상태에서는 항목과 근거 ID 자체가 전달되지 않고(AC-05), 근거 Tool은 서버가 사용 가능 여부를 다시 확인하며(AC-06), 요청 본문의 어떤 값도 차단 판단에 영향을 주지 않는다 | 통합 테스트(두 API 비교 + 요청 본문 조작 사례) |
| AC-05 | 사례 E, F | `usable=false`, 사유만 전달. **checklist 항목과 항목 근거 ID 필드는 null**(빈 배열도 아님). 미승인 변경안 내용 없음 | 통합 테스트 + schema |
| AC-06 | 사례 B, H | 사용 가능한 승인 checklist 항목의 근거만 제공. 서버가 규칙 version이 (a) 요청 공문군에 속하고 (b) 현재 사용 가능한 승인 checklist 항목의 `source_rule_version_id`인지 다시 확인. 다른 공문군 규칙 ID, 미승인(검토 대기·반려·FIXTURE) 규칙 ID, 사용 불가 상태(공개 근거 미확인 포함)에서는 403 | 통합 테스트(규칙 ID 바꿔치기 사례 포함) |
| AC-07 | 사례 G, I | `knownAt`과 schema 밖 필드는 400. 미래 업무일은 `usable=false` + `FUTURE_BUSINESS_DATE`. 조회는 항상 현재 시각 기준 | 통합 테스트 |
| AC-08 | 상담 ID | 상담 ID는 감사 로그에 추적용으로만 남고, 상담 ID 유무·값이 응답 내용과 권한 판단에 영향을 주지 않음 | 통합 테스트 |
| AC-09 | 사례 J와 감사 내용 | 감사 로그에 본문·원문·토큰 없음. 저장 실패 시 500 `AUDIT_WRITE_FAILED`이고 응답 데이터 없음 | 통합 테스트 + 로그 내용 검사 |
| AC-10 | 비밀 | **실제 자격증명을 저장소 파일, 예시 설정, 테스트 코드, 로그에 남기지 않는다.** 인증 테스트는 실행 중 생성한 임시 토큰을 쓴다(허용). production은 `TRUST_AGENT_TOOL_SERVICE_TOKEN` 없으면 기동 거부 | 비밀 점검 + 컨텍스트 테스트 |
| AC-11 | 보호와 회귀 | 감사 로그 테이블 append-only와 보호 목록(코드 기준 35 → 36, 70 → 72, migration 9). 기존 Java/Python 유지, 환경별 실행 수 기록 | schema 테스트 + 로컬/CI |
| AC-12 | 문서 | ADR-011 승인 기록, README "AI Tool API 읽기 전용, test/demo 토큰 인증, 사용자별 인증·권한과 AI 서비스 구현 미포함", core README, evidence | diff 검토 |

## Implementation Plan (초안)

1. ADR-011 초안: Tool 경계(읽기 전용, allowlist, 최소 데이터, fail-closed, demo 인증과 운영 인증의 분리).
2. V9: `tool_call_audit` + 보호 목록. 3. 토큰 필터(`ToolAuthenticationFilter`)와 설정(`trust-agent.tool-api.service-token`, production 필수). 4. `ToolController` + 두 tool의 입력/출력 record와 변환(기존 적용 공문 조회 서비스 재사용, 응답 축소). 5. 계약 schema 2개와 정답 fixture, Python 테스트. 6. 테스트, evidence, README.

예상 크기: 중간(코드 ~500행, 테스트 ~15건).

## AI 제안 및 인간 판단 기록

### 제안 1: 사용 불가 상태에서는 checklist 항목을 비워서 보낸다
- 내용: `usable=false`면 항목 배열을 비우고 사유만 준다. 대안: 항목은 주되 `usable=false` 표시만 한다(AI가 실수로 쓸 위험).

**판단**
- [x] 수정 채택
- 판단자 / 검토 대상 revision 또는 PR: 사용자 / PR #28
- 이유 / 승인 범위: 사용 불가 상태에서는 사유만 제공하고 checklist 항목과 항목 근거 ID는 제공하지 않는다(AC-05).

### 제안 2: demo 인증은 공유 비밀 토큰 1개
- 내용: 환경변수 토큰 1개로 서비스 신원을 확인하고 서비스 ID를 토큰에 묶는다. 조직 인증 체계 연동은 범위 밖. 대안: 서비스별 토큰 여러 개.

**판단**
- [x] 조건부 채택
- 판단자 / 검토 대상 revision 또는 PR: 사용자 / PR #28
- 이유 / 승인 범위: test/demo 범위로만 채택. 실제 값은 환경변수로 제공하고 저장소와 로그에 남기지 않는다. 누락 시 접근을 허용하지 않는다. 사용자별 인증·권한은 미구현으로 명시한다(AC-01, AC-10).

### 제안 3: 근거 Tool은 사용 가능한 항목의 근거만 준다
- 내용: 미승인·철회·구버전 규칙의 원문은 AI에 제공하지 않는다(403). 대안: 읽기는 허용하되 상태를 표시.

**판단**
- [x] 채택
- 판단자 / 검토 대상 revision 또는 PR: 사용자 / PR #28
- 이유 / 승인 범위: 현재 사용 가능한 승인 checklist에 속한 규칙만 제공한다. 규칙 ID를 임의로 바꿔 다른 공문군이나 미승인 근거를 가져오지 못하도록 서버가 소속과 사용 가능 여부를 다시 확인한다(AC-06).

### 유지 조건 (사용자 지시)

- 상담 ID는 추적용이며 접근 권한을 부여하지 않는다(AC-08).
- 감사 로그 실패 시 응답 실패(AC-09), 현재 시각 기준 조회(AC-07), 쓰기 Tool 제외(AC-02).
- 완료 확인 조건 12개와 ADR-011 방향 승인, AC-04(사용 허용 여부는 Core가 결정, AI가 차단을 변경·우회 불가)와 AC-10(실제 자격증명 미보관, 임시 토큰 테스트 허용) 표현 명확화, 구현 착수 승인. 토큰 인증은 test/demo 수준이며 사용자별 권한과 FastAPI AI 서비스는 범위 밖. 커밋·push·PR 생성까지, 병합은 사용자. 완료와 인간 검수는 결과 확인 전까지 미기록.

## Implementation Result / AI self-review / 인간 검수 / 결정 기록

### 구현 결과 (AI 작성, 사실)

- 검증 대상: 브랜치 `feat/core-tool-api`, PR #31(본문에 commit 기재). 상세: [검증 기록](../evidence/CORE_TOOL_API_EVIDENCE.md).
- 구현: `POST /api/v1/tools/{toolName}` 하나와 allowlist 2개, `applicable_checklist`(사용 불가면 사유만, 항목·근거 ID 없음), `rule_evidence`(서버가 소속과 사용 가능 여부 재확인, 아니면 403), Bearer 토큰 서비스 인증(test/demo, 환경변수, 비어 있으면 모든 호출 401, production 필수), V9 `tool_call_audit`(append-only, 보호 테이블 36·trigger 72·migration 9), 계약 schema 2개와 정답 파일, README.
- Java `./gradlew clean test bootJar --offline --no-daemon`: 153건 실행, 통과 153, 실패 0, 건너뜀 0 (기존 144 + 신규 9). Python 로컬(비공개 artifact): 65건 실행, 통과 65, 건너뜀 0 (기존 63 + 신규 2). 공개 CI 조건: 65건, 통과 63, 건너뜀 2. 공개 CI 실제 결과는 PR #31 checks.
- AC-01~12 자동 검증 통과. 인간 검수와 완료 판정은 미실시.

### AI self-review

- 사용 허용 여부를 바꿀 수 있는 입력이 없다: 요청 본문은 공문군·업무일·상담 ID·규칙 ID뿐이고 `knownAt`과 모르는 필드는 400이다. `usable`은 Core 조회 API 값과 같다(테스트로 대조).
- 사용 불가 상태에서는 내용 자체가 나가지 않는다: 검토 대기, 반려, 테스트용 출처, 미래 업무일, 철회에서 `approvedChecklist=null`이고 규칙 ID 문자열이 응답에 없다.
- 근거는 서버가 다시 확인한다: 다른 공문군 규칙 ID, 미승인 공문군 규칙 ID, 없는 규칙 ID, 그리고 조회 뒤 철회로 사용 불가가 된 규칙 ID는 모두 403.
- 감사는 fail-closed: 인증 거부도 기록하고, 저장 실패 시 500에 데이터 없음. 감사 행에 토큰·원문·규칙 ID 없음.
- 실제 자격증명은 저장소에 없다. 테스트는 실행 중 생성한 UUID를 쓴다.
- 범위 밖 변경 없음: 사용자별 인증·권한, FastAPI AI 서비스, 쓰기 Tool 없음.

### 인간 검수 / 결정

미기록. 사용자 검수 뒤 기록.
