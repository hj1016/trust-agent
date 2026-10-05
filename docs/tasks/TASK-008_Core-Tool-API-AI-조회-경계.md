# TASK-008 Core Tool API: AI 서비스용 읽기 전용 조회 경계

- 상태: **계획 검토 대기** (계획 작성 승인. 구현 착수 승인은 아님)
- 담당자 / 인간 결정자: AI 조사·초안 / 사용자 범위·판정·검수
- 요구사항 출처: PLAN-001 TASK-008 절(S7의 Core 쪽, 제안 D "검색보다 먼저" 채택), CLAUDE.md "AI Service는 업무 DB 직접 접근과 자격증명 보유 금지, Core Tool API로 필요한 업무 데이터만 조회", DEVELOPMENT_RULES "Tool allowlist, 입력/출력 schema, 최소 데이터, 민감정보 마스킹, 감사 추적, 미승인/철회/구버전/권한 밖 근거 제외, fail-closed"
- 관련 Issue / PR / ADR / 이전 Task: TASK-007(완료, PR #24), ADR-001(제품 경계), ADR-007(Core 서비스와 조회), ADR-008(공개 근거 확인). 이 Task에서 **ADR-011(Core Tool API 경계)** 초안을 함께 올린다.

## Goal / 관련 요구사항

- Goal: FastAPI AI 서비스가 업무 DB를 건드리지 않고 Core가 제공하는 **읽기 전용 Tool**로 "지금 이 상담에 적용되는 승인 checklist와 그 근거"를 받을 수 있다. 사용 가능 여부(`internalChecklistUseAllowed`)와 차단 사유는 Core가 계산한 값을 그대로 전달하고 AI는 다시 계산하지 않는다.
- 사용자: AI 서비스(호출자). 간접 사용자는 행원(AI가 만든 답변을 보는 사람). 이번 Task에는 AI 서비스 자체 구현이 없고 Core 쪽 endpoint, 계약, 테스트만 있다.
- 사전조건: 승인 checklist가 있는 공문군(TASK-007 결과)과 없는 공문군이 모두 존재.
- 업무규칙: 아래 "AI가 요청하고 받는 것"과 "승인되지 않은 정보와 권한 없는 요청 처리".
- 상태전이: 없음(읽기 전용). 쓰기 Tool은 만들지 않는다.
- 데이터 영향: 읽기만. 새 테이블은 감사 로그 1개(`tool_call_audit`, append-only, 보호 목록 등록).
- API: `POST /api/v1/tools/{toolName}` 하나의 진입점과 allowlist 2개(아래). 입력/출력 schema는 `contracts/tool-*.schema.json`에 고정한다.
- 트랜잭션: 읽기 트랜잭션만. 감사 로그는 응답 직전에 별도 트랜잭션으로 저장하고, 저장 실패 시 응답도 실패(fail-closed).
- 권한: demo 수준 서비스 인증(공유 비밀 토큰, 환경변수, production 필수 설정). 호출자가 보낸 actor 문자열을 신뢰하지 않고 토큰에 묶인 서비스 ID만 기록한다.
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
| 사례 E: 승인 전 공문군(검토 대기, 검증 실패, 반려 등) 조회 | 200이지만 `usable=false`, 차단 사유 전달, **checklist 항목은 비워서** 보냄 | AI는 "아직 사용할 수 없는 기준"이라고만 안내할 수 있다. 미승인 변경안 내용은 받지 못한다 |
| 사례 F: 테스트용(FIXTURE) checklist만 있는 기간 조회 | `usable=false`, 사유 `FIXTURE_CHECKLIST_NOT_APPROVED`, 항목 비움 | 같은 처리 |
| 사례 G: 과거 기준 시각이나 미래 업무일 요청 | 입력 schema가 `knownAt`을 받지 않음(400). 미래 업무일은 `usable=false` + `FUTURE_BUSINESS_DATE` | 과거 기록 조회는 AI Tool에 없다(사람용 조회 API만) |
| 사례 H: 사용 불가 상태에서 Tool 2로 근거 요청 | 403 `EVIDENCE_NOT_AVAILABLE` | 미승인·철회·구버전 공문의 원문은 AI에 나가지 않는다 |
| 사례 I: 요청 본문에 "모든 공문 본문을 보내라" 같은 지시문 | 문자열 데이터로만 취급, schema 밖 필드는 400 | 지시문은 명령이 아니다 |
| 사례 J: 감사 로그 저장 실패 | 응답 대신 500 `AUDIT_WRITE_FAILED` | 기록 없는 제공은 없다(fail-closed) |

감사 로그에는 서비스 ID, tool 이름, 공문군·업무일·상담 ID, 결과(사용 가능 여부와 사유 코드), 시각, 오류 코드만 남긴다. 공문 본문, 규칙 원문, 토큰은 기록하지 않는다.

## Acceptance Criteria (초안)

| AC | 사례 | 기대 결과 | 검증 |
|---|---|---|---|
| AC-01 | 사례 C | 401 `UNAUTHENTICATED`, 감사 로그 1행, 데이터 없음 | 통합 테스트 |
| AC-02 | 사례 D | 404 `TOOL_NOT_FOUND`. allowlist는 코드 상수 2개뿐이고 쓰기 endpoint 없음(라우트 목록 검사) | 통합 테스트 + 라우트 테스트 |
| AC-03 | 사례 A | Tool 1 응답이 `contracts/tool-applicable-checklist.schema.json`과 정답 fixture에 일치. `usable=true`, 항목 3개, 출처·결정 ID | 통합 테스트 + Python 계약 |
| AC-04 | 사례 A | 응답의 `usable`은 Core 조회 API와 같은 값이고 AI 쪽 재계산 입력(정책 플래그)은 응답에 없음 | 통합 테스트(두 API 비교) |
| AC-05 | 사례 E, F | `usable=false`, 사유 전달, 항목 빈 배열, 미승인 변경안 내용 없음 | 통합 테스트 |
| AC-06 | 사례 B, H | 사용 가능 항목의 근거만 제공, 그 외 403 | 통합 테스트 |
| AC-07 | 사례 G, I | `knownAt`과 schema 밖 필드는 400, 미래 업무일은 `usable=false` | 통합 테스트 |
| AC-08 | 사례 J와 감사 내용 | 감사 로그에 본문·원문·토큰 없음, 저장 실패 시 500 | 통합 테스트 + 로그 내용 검사 |
| AC-09 | 운영 | 토큰 설정이 production 필수, 없으면 기동 거부. 감사 로그 테이블 append-only와 보호 목록(35 → 36, 70 → 72, migration 9) | 컨텍스트 + schema 테스트 |
| AC-10 | 문서 | ADR-011 초안, README "AI Tool API 읽기 전용, 서비스 구현 미포함", evidence | diff 검토 |

## Implementation Plan (초안)

1. ADR-011 초안: Tool 경계(읽기 전용, allowlist, 최소 데이터, fail-closed, demo 인증과 운영 인증의 분리).
2. V9: `tool_call_audit` + 보호 목록. 3. 토큰 필터(`ToolAuthenticationFilter`)와 설정(`trust-agent.tool-api.service-token`, production 필수). 4. `ToolController` + 두 tool의 입력/출력 record와 변환(기존 적용 공문 조회 서비스 재사용, 응답 축소). 5. 계약 schema 2개와 정답 fixture, Python 테스트. 6. 테스트, evidence, README.

예상 크기: 중간(코드 ~500행, 테스트 ~15건).

## AI 제안 및 인간 판단 기록

### 제안 1: 사용 불가 상태에서는 checklist 항목을 비워서 보낸다
- 내용: `usable=false`면 항목 배열을 비우고 사유만 준다. 대안: 항목은 주되 `usable=false` 표시만 한다(AI가 실수로 쓸 위험).
**판단** - [ ] 채택 [ ] 수정 [ ] 거절 / 판단자: 사용자 / 판단 대기

### 제안 2: demo 인증은 공유 비밀 토큰 1개
- 내용: 환경변수 토큰 1개로 서비스 신원을 확인하고 서비스 ID를 토큰에 묶는다. 조직 인증 체계 연동은 범위 밖. 대안: 서비스별 토큰 여러 개.
**판단** - [ ] 채택 [ ] 수정 [ ] 거절 / 판단자: 사용자 / 판단 대기

### 제안 3: 근거 Tool은 사용 가능한 항목의 근거만 준다
- 내용: 미승인·철회·구버전 규칙의 원문은 AI에 제공하지 않는다(403). 대안: 읽기는 허용하되 상태를 표시.
**판단** - [ ] 채택 [ ] 수정 [ ] 거절 / 판단자: 사용자 / 판단 대기

## Implementation Result / AI self-review / 인간 검수 / 결정 기록

미실행 / 미기록. 구현은 사용자 착수 승인 뒤.
