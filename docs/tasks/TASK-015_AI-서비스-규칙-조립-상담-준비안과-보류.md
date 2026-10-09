# TASK-015 AI 서비스 최소 흐름: 규칙 조립 상담 준비안과 보류

- 상태: **완료** (결정자 사용자, 2026-10-08. 검수 대상 PR #40: 인터뷰 반영 구현·계약·테스트 커밋 `f6ca899`와 문서 커밋 `c0498c1` 포함. 인간 검수·Explainability Gate 통과. 병합은 사용자가 직접 수행: PR #40 squash 병합, main 커밋 `c54c923`, main CI run 37689504964 성공)
- 이전 상태 이력 (당시 상태의 기록이며 현재 상태가 아님. 보존): (1) 계획 단계: 상세 계획 작성 지시 → 큰 방향 동의와 보완 요청 8건 반영 → 제안 6건 채택(보완된 방향)과 추가 조건 5건 반영 → 제안 7 (b) 채택 → 설계 보완 4건 반영 → 계획과 ADR-012 승인·구현 착수 승인(결정자 사용자, 검토 대상 PR #39. 승인 범위: 매핑·계약·Core 저장과 재확인·규칙 기반 AI 서비스·필수 연결 CI·안전성 자료 v2. 검색과 LLM 생성은 범위 밖). 이 시점의 상태는 "기능의 인간 검수, Explainability Gate, 완료 판정은 대기"였고 "구현분 커밋·push·PR은 결과 확인 뒤 별도 지시"였다. (2) 구현 단계: 구현·로컬 검증 완료 → 사용자 지시로 커밋·push·검토용 PR #40 생성 → 1~6차 보완 → 판정 대기. (3) 위 완료 판정으로 대기 상태가 종료됐다.
- 담당자 / 인간 결정자: AI 조사·초안·구현·검증 / 사용자 범위·판정·검수
- 요구사항 출처: PLAN-002 TASK-015 절(제안 1 수정 채택: LLM 없는 규칙 조립 준비안은 첫 연결 단계, "AI 생성 완료"로 표현하지 않음. 제안 2 채택: 준비안·보류 기록은 Tool이 아니라 별도 쓰기 경로, Core가 저장 전 재확인), CLAUDE.md 기술 경계(AI Service는 Python + FastAPI, 업무 DB 직접 접근과 자격증명 보유 금지, Core Tool API로만 조회. AI는 승인·거절·금리·한도·신용등급 결정 주체가 아님), ADR-011(읽기 전용 Tool 2개, 서비스 토큰, 감사, 쓰기 Tool 없음), README MVP 7단계(상담 준비안)와 9단계(AI 중단 시 수기 checklist, AI 확정 경로 차단), TASK-014 안전성 참조 3건(`pass_criteria_owner: TASK-015`), 사용자 보완 요청 8건(부분 준비 표시, 보류 기록, 재실행, Core 직접 확인, 재현 정보, 메모와 안전성, 기록 권한, 검증 환경과 변경 범위)
- 관련 Task / ADR: TASK-008(완료), TASK-011a(완료), TASK-014(완료. 검색 목표 수치는 TASK-016 시작 전에 정하고, 실제 관련성 보류 기준값은 TASK-016에서 초기 점검 자료로 조정한 뒤 최종 평가 전에 고정한다), TASK-016·017·019(이 Task 뒤). **ADR-012**(`docs/adr/ADR-012-ai-service-preparation-record-path.md`, PR #39에서 승인)를 이 Task에서 함께 제안했다.
- 기존 자산: `apps/ai-service/README.md`는 "구현 예정" 한 줄뿐인 placeholder(TASK-000 KEEP). 이 Task에서 MODIFY해 실제 상태를 적는다. `apps/frontend`는 손대지 않는다.

## Goal / 관련 요구사항

- Goal: 합성 신청 건 하나에 대해 FastAPI AI 서비스가 **Core Tool API로 승인되고 지금 사용 가능한 checklist와 항목 근거만 읽어** 규칙으로 **상담 준비안을 조립**하거나 사용 불가 사유로 **보류**하고, 그 결과(준비된 것과 보류된 것 모두)를 **별도 Core 기록 경로**에 남긴다. Core는 준비된 부분을 저장 직전에 다시 확인해 사용 불가 상태의 준비안 저장을 거부한다. 명령 한 번으로 "신청 건 → 준비안/보류 → Core 기록 ID"까지 실행된다. 결과물은 **어디까지 준비됐고, 무엇이 막혔으며, 어떤 기록을 믿을 수 있는지**를 그 자체로 말한다.
- 이 Task가 아닌 것: **LLM 문장 생성이 아니다.** 준비안의 모든 문장은 승인 checklist 항목의 지시 문장, 근거 Tool이 돌려준 규칙 원문, 구조화 값, 고정 안내 문구로만 구성된다. 완료해도 "AI 생성 완료"나 "LLM 안전성 검증 완료"가 아니며, LLM 생성과 그 검증은 TASK-019다. 근거 검색(TASK-016), 화면과 행원 최종 확인(TASK-017), 사용자별 인증·권한, 실제 고객·신청 자료 연동은 범위 밖이다.
- 변경 범위(정정): **기존 공문 승인·일정·변경안·검증 기능은 바꾸지 않는다.** 그러나 이 Task는 새 저장 기능(준비안·보류·실행 기록 표 V10)과 새 검증 기능(저장 전 재확인)을 Core에 **추가**하므로 "업무 로직 변경 없음"이 아니다. 기존 읽기 경로(`InternalPolicyApplicableService`)는 재사용하고 수정하지 않는다.
- 사용자: 행원(명령 실행자 또는 뒤에 올 화면의 호출자)과 검수자(사용자). 후속 Task(016, 017, 019)의 구현자.
- 사전조건: Core 기동, Tool 토큰(과 제안 5 채택 시 기록 토큰)이 환경변수로 설정. 합성 공문·예시 checklist 적재, 중도상환수수료 v2 승인(TASK-011a E2E와 같은 상태). 셀러론 v2는 미승인이어도 된다(보류 사례).

## AI가 하는 것과 하지 않는 것 (쉬운 사례)

**사례 A. 승인된 공문군(READY 섹션)**: `prepare --application SW-APPLICATION-001 --business-date 2026-10-06`을 실행한다. AI 서비스는 신청 건의 상품(`kb-seller-loan`)에 묶인 공문군 목록과 필수 여부를 설정에서 읽고, 공문군마다 Tool 1(`applicable_checklist`)을 부른다. 중도상환수수료 공문군이 `usable=true`면 항목 3개를 받고, 항목마다 Tool 2(`rule_evidence`)로 규칙 원문과 위치를 받아 섹션에 붙인다. 섹션에는 항목 3개, 각 항목의 구조화 값, 근거 문장·위치·해시, 승인 checklist version ID와 결정 ID가 들어간다.

**사례 B. 미승인 공문군(HOLD 섹션, Core 판정)**: 같은 신청 건에 셀러론 공문군도 필수로 묶여 있다. Tool 1이 `usable=false`, 사유 `HUMAN_REVIEW_PENDING`을 주면 그 섹션은 HOLD이고 보류 종류는 `CORE_DECISION`이다. 항목은 없고 사유 코드, 사람이 읽는 설명, 수기 checklist 안내만 있다. 준비안 전체 상태는 **PARTIAL**이며, 머리에 "공문군 2개 중 1개 준비됨, 필수 공문군 1개(셀러론) 확인 남음. 상담 준비가 끝나지 않았습니다"가 고정 문구로 표시된다. 이 보류도 Core에 그대로 기록된다.

**사례 C. 통신·인증 실패(HOLD 섹션, 미확인)**: Tool 호출이 401·403·5xx·시간 초과면 그 섹션은 HOLD이고 보류 종류는 `UNVERIFIED`(Core가 판정한 것이 아니라 확인하지 못한 것)다. 준비안을 추측으로 만들지 않는다. 기록도 실패하면 명령은 실패 코드로 끝나고 출력에 `recorded=false`와 "이 준비안은 기록되지 않았으므로 사용하지 않는다"를 적는다.

**사례 D. 저장 직전 재확인 거부**: AI가 READY 섹션을 기록하려는 사이에 그 공문이 철회됐다. Core 기록 경로는 저장 전에 같은 공문군·업무일로 적용 공문 조회를 다시 실행하고, 사용 불가이거나 version ID·결정 ID·항목 ID·근거 해시가 보낸 값과 다르면 저장을 거부한다. 거부도 실행 기록(REJECTED)으로 남는다. AI 출력은 `record.status=REJECTED`, `usage_notice="이 준비안은 Core 저장이 거부됐습니다. 사용하지 말고 다시 실행하세요"`가 되고 종료 코드 3이다. 거부된 준비안 ID는 기록 표에 없으므로 뒤에 올 화면(TASK-017)은 그것을 보여 줄 수 없다.

**사례 E. 다시 실행**: 같은 명령을 다시 실행하면 AI는 기존 기록이 있든 없든 **Tool 1·2를 다시 호출**한다. Core 상태가 같으면 같은 준비안 ID가 나오고 Core는 새 행 없이 `ALREADY_RECORDED`를 돌려주되, 이번 실행 기록(run)은 새로 남긴다. 상태가 바뀌었으면 다른 ID의 새 준비안이 기록된다.

## 요구사항과 범위

- 업무 문제: Core에 승인 checklist와 Tool이 있어도 "이 신청 건에서 무엇을 확인해야 하고 근거가 무엇인지, 아직 무엇이 막혀 있는지"를 한 묶음으로 만들어 주는 쪽이 없다.
- 포함 범위: FastAPI 서비스(`apps/ai-service`)의 조립·보류 로직, CLI와 HTTP 진입점, Core 기록 경로(endpoint 1개, V10 표 3개, 저장 전 재확인), 계약 schema(준비안 출력, 기록 요청, 공문군 매핑), 안전성 기준과 테스트, 연결 검증 환경, evidence, ADR-012 초안.
- 제외 범위: LLM 호출, 근거 검색(TASK-016), 화면·행원 최종 확인·준비안 조회 화면(TASK-017), 사용자별 인증·권한, 실제 신청·고객 자료, 준비안 수정·재처리·자동 재시도, 여러 신청 건 일괄 처리, LangGraph(분기·재시도 workflow가 아님).

### 1. 일부만 준비된 경우의 표시와 필수 공문군의 소유자

- **필수 여부는 사람이 정한다.** 상품별 공문군 매핑은 하나의 파일 `datasets/synthetic/work/consultation-family-mapping.json`(SYNTHETIC_WORK 표시, schema `contracts/consultation-family-mapping.schema.json`, `mapping_version`과 canonical 해시)에 두고 공문군마다 `required: true|false`를 적는다. 초기값 `kb-seller-loan → SIN-PREPAYMENT-FEE(required), SIN-SELLER-CHECKLIST(required)`. **현재는 합성 시나리오의 사용자 승인 설정**이며 실제 은행의 승인 부서가 있는 것처럼 쓰지 않는다. 내용과 변경은 사용자가 승인하고 Task 판단 기록에 남긴다. AI 서비스는 매핑을 읽기만 하고 판단하지 않는다. 매핑에 없는 상품이면 400 `PRODUCT_NOT_MAPPED`로 끝난다(추측하지 않는다).
- **Core도 같은 매핑을 가진다.** 같은 파일을 Core bootstrap importer가 V10 표 `consultation_family_mapping`(append-only, `mapping_hash` 기준 버전)에 적재한다. 기록 요청은 `family_mapping_hash`를 보내고, Core는 (M1) 그 해시가 현재 활성 매핑과 같은지, (M2) 해당 상품의 필수 공문군이 요청의 섹션에 빠짐없이 있는지, (M3) 각 섹션의 `required` 값이 매핑과 같은지, (M4) 요청의 전체 `status`가 "필수 섹션 상태로 계산한 값"과 같은지, (M5) `preparation_complete`가 READY일 때만 true인지 확인한다. 어긋나면 409 `MAPPING_MISMATCH`(M1), 422 `REQUIRED_SECTION_MISSING`(M2), 422 `REQUIRED_FLAG_MISMATCH`(M3), 422 `PREPARATION_STATUS_INVALID`(M4·M5)로 거부하고 실행 기록에 REJECTED로 남긴다. AI가 필수 섹션을 빼거나 필수 여부를 바꿔 전체 READY를 주장하는 요청은 이렇게 막는다.
- **필수 공문군이 하나도 없는 설정.** 상품의 매핑에 `required=true`가 하나도 없으면 AI 서비스는 상태를 `HOLD`, `preparation_complete=false`, 사유 `NO_REQUIRED_FAMILY_CONFIGURED`로 내고, Core도 그런 요청의 READY를 422 `NO_REQUIRED_FAMILY`로 거부한다. 빈 설정이 준비 완료가 되는 일은 없다.
- **READY의 뜻.** READY는 "승인된 매핑에 따른 필수 준비 자료(승인 checklist 항목과 근거)를 갖췄다"는 뜻이다. **상담이 끝났다는 뜻도, 대출 결정이 났다는 뜻도 아니다.** 이 문장을 `headline` 고정 문구와 README에 그대로 적는다.
- **상태 규칙.** 준비안 전체 상태는 필수 공문군 섹션만으로 정한다. 모든 필수 섹션 READY → `READY`. 필수 섹션 가운데 READY와 HOLD가 섞임 → `PARTIAL`. 모든 필수 섹션 HOLD → `HOLD`. 선택 공문군(required=false)의 HOLD는 상태를 바꾸지 않고 `optional_holds`에 따로 나열한다.
- **"끝난 것처럼 보이지 않게" 하는 장치 4개.** (1) `preparation_complete`는 READY일 때만 true. (2) `remaining_checks[]`에 HOLD인 필수 공문군마다 `family_id`, 보류 종류, 사유 코드, 사람이 읽는 설명, 수기 확인 안내를 적는다. (3) 머리 문구 `headline`은 고정 문구 표에서 상태별로 가져오며 PARTIAL·HOLD에는 "상담 준비가 끝나지 않았습니다"가 반드시 들어간다. (4) 섹션 순서는 매핑 순서를 따르되 HOLD 섹션이 READY 섹션 뒤에 숨지 않도록 `remaining_checks`를 응답 머리(`sections` 앞)에 둔다.

### 2. 보류 결과의 기록과 보류 종류

- **저장 조건을 둘로 나눈다.**

**모든 요청에 공통인 검사(보류 섹션도 예외가 아니다):** 기록 토큰 인증(401), 기록 schema와 결정 필드 없음(400), `preparation_id` 해시 일치(400), 매핑·필수 여부·상태 일치 M1~M5(1절), HOLD 섹션의 항목·근거 ID·version·결정 ID가 모두 비어 있고 `hold_kind`와 사유 코드가 정의된 형식인지(400 `HOLD_SECTION_INVALID`). 이 검사에 걸리면 준비안 전체를 거부한다.

| 섹션 | 공통 검사 뒤 Core 저장 조건 | 저장 시 Core가 하는 일 | 기록이 보증하는 것 |
|---|---|---|---|
| READY | 저장 직전 재확인 C1~C5 전부 통과(4절) | 통과 못 하면 준비안 전체를 거부(REJECTED). 부분 저장 없음 | 그 시점에 Core가 사용 가능과 항목·근거 일치를 직접 확인했다 |
| HOLD, `CORE_DECISION`(Core가 직접 판정) | 공통 검사 통과 | 같은 공문군·업무일을 다시 조회해 `recheck_usable`·`recheck_reasons`를 함께 적는다. 보낸 사유 코드와 재조회 사유가 다르면 거부하지 않고 둘 다 저장한다(AI가 본 상태와 Core가 지금 본 상태의 차이를 남긴다). 재조회가 사용 가능해도 섹션을 READY로 바꾸지 않는다 | Core가 저장 시점에 다시 본 상태(`recheck_*`)는 Core가 보증한다. AI가 보낸 사유 코드는 "AI가 그때 받았다고 보고한 값"이며 Core는 그 보고 자체를 보증하지 않는다 |
| HOLD, `UNVERIFIED`(서비스가 보고한 통신·인증·응답 오류) | 공통 검사 통과. 사유 코드가 AI 서비스 코드 목록(`TOOL_AUTH_FAILED`, `CORE_UNAVAILABLE`, `CORE_TIMEOUT`, `EVIDENCE_UNAVAILABLE`, `TOOL_RESPONSE_INVALID`) 안에 있어야 함 | 같은 참고 재조회를 적는다. 기록 호출 자체가 인증·통신 실패면 기록되지 않고 AI 출력이 `recorded=false` | **Core는 그 통신 오류가 실제로 있었는지 보증하지 않는다.** 기록은 "서비스가 이렇게 보고했다"는 사실과 "Core가 저장 시점에 본 상태"만 남긴다 |

기록 표에는 `hold_claim_basis`(`CORE_REPORTED` / `SERVICE_REPORTED`)를 두어 두 경우를 구분한다. 어떤 경우에도 기록이 보류 사유의 진실성을 모두 보증한다고 표현하지 않는다.

- **보류 종류 두 가지.** `CORE_DECISION`: Tool 1이 응답했고 `usable=false`로 판정한 경우(사유 코드는 Core 것 그대로. 예 `HUMAN_REVIEW_PENDING`, `FIXTURE_CHECKLIST_NOT_APPROVED`, `EFFECTIVE_NOTICE_WITHDRAWN`, `POLICY_FAMILY_NOT_FOUND`). `UNVERIFIED`: Core 판정을 받지 못한 경우(사유 코드는 AI 서비스 것. `TOOL_AUTH_FAILED`, `CORE_UNAVAILABLE`, `CORE_TIMEOUT`, `EVIDENCE_UNAVAILABLE`(Tool 2 실패), `TOOL_RESPONSE_INVALID`(schema 불일치)). 사람이 읽는 설명도 둘을 구분한다("담당 부서 검토 대기로 사용할 수 없습니다" vs "Core에 확인하지 못했습니다. 잠시 뒤 다시 실행하세요").
- **거부된 준비안을 쓰지 않게 하는 방법.** (1) 기록 거부 시 AI 출력의 `record.status=REJECTED`, `usage_notice`에 사용 금지와 재실행 안내. (2) 종료 코드 3. (3) Core 실행 기록에 `preparation_id`와 거부 코드가 남지만 준비안 표에는 행이 없다. (4) 뒤에 올 화면(TASK-017)은 "기록된 준비안"만 보여 주고, 보여 줄 때도 Tool 1로 사용 가능 여부를 다시 확인한다(기록은 사용 허가가 아니다, 5절). (5) 준비안 파일을 사람이 저장해 두었더라도 그 ID는 Core 기록에 없으므로 확인 시 "기록 없음"으로 드러난다.

### 3. 다시 실행했을 때

- **재실행은 항상 Core를 다시 본다.** 기존 기록 유무와 무관하게 Tool 1·2를 다시 호출한다. 기존 기록을 조회해 건너뛰는 경로는 두지 않는다(AI 서비스에는 기록 조회 API도 없다).
- **두 ID.** `preparation_id` = 준비안 **업무 내용**의 canonical sha256(아래 해시 대상). 같은 입력·같은 Core 상태면 같다. `run_id` = 실행마다 새 값(`consultation-preparation-run:<32 hex>`, AI 서비스가 UUID로 생성). Core 실행 기록 표는 `run_id`가 PK이고 `preparation_id`와 결과(`RECORDED`, `ALREADY_RECORDED`, `REJECTED`, `FAILED`)를 가진다. 출력에 둘 다 적는다.
- **해시 대상.** application_id와 신청 자료 해시, business_date, 공문군 매핑 해시, 조립 규칙 버전, 고정 문구 표 해시, 섹션마다(family_id, status, hold_kind, blocking_reasons, selected_notice_id, approved_checklist_version_id, decision_id, items의 rule_version_id·evidence_hash 순서 목록). **제외:** evaluated_at, run_id, 기록 결과, 메모. 따라서 상태가 바뀌면(승인·철회·사유 변화) ID가 바뀐다.
- **같은 ID로 다른 내용.** Core는 받은 본문으로 해시를 다시 계산해 `preparation_id`와 비교한다. 다르면 400 `PREPARATION_ID_MISMATCH`(저장 없음). 같은 ID 행이 이미 있고 저장된 `content_hash`와 같으면 200 `ALREADY_RECORDED`. 같은 ID인데 저장된 해시가 다르면(정상에서는 불가능) 409 `PREPARATION_CONFLICT`로 거부하고 실행 기록에 남긴다.
- **동시 요청.** 같은 ID 두 요청이 동시에 오면 PK 충돌로 한쪽만 insert된다. 다른 쪽은 유일 제약 위반을 잡아 저장된 행의 해시를 읽고 같으면 `ALREADY_RECORDED`, 다르면 `PREPARATION_CONFLICT`. 다른 ID 두 요청은 서로 영향 없이 둘 다 기록된다(같은 신청 건에 여러 준비안이 있을 수 있고, 최신 판단은 화면이 Tool 1 재확인으로 한다).

### 4. Core가 직접 확인하는 것 (AI 주장을 믿지 않음)

READY 섹션마다 저장 직전 한 트랜잭션 안에서:

| # | 확인 | 어긋나면 |
|---|---|---|
| C0 | **재확인은 기존 기록 유무와 무관하게 항상 실행한다.** 같은 `preparation_id`가 이미 저장돼 있어도 C1~C5를 먼저 수행하고, 통과한 뒤에야 저장된 해시와 비교해 `ALREADY_RECORDED`로 답한다. 기존 기록 조회로 재확인을 생략하는 경로는 없다 | 재확인 실패면 기존 행이 있어도 422/409로 거부(기존 행은 그대로, 실행 기록 REJECTED) |
| C1 | 같은 `family_id`·`business_date`로 `InternalPolicyApplicableService` 조회 → `internalChecklistUseAllowed=true` | 422 `PREPARATION_NOT_USABLE`(현재 사유 코드 첨부) |
| C2 | 조회 결과의 승인 checklist version ID·결정 ID가 보낸 값과 같다 | 409 `PREPARATION_STALE` |
| C3 | 보낸 항목의 규칙 version ID 목록(순서 포함)이 그 checklist의 항목 `source_rule_version_id` 목록과 정확히 같다(누락·추가·순서 변경 모두 거부) | 409 `PREPARATION_STALE` |
| C4 | 항목마다 보낸 `evidence_hash`가 `internal_policy_rule_evidence`의 해당 규칙 version 근거 해시와 같다 | 409 `PREPARATION_STALE` |
| C5 | 선택 공문 ID가 조회 결과의 선택 공문과 같다 | 409 `PREPARATION_STALE` |
| C6 | 본문이 기록 schema를 통과하고 결정 필드가 없다(`additionalProperties: false`) | 400 |
| C7 | `preparation_id`가 본문 해시와 같다(3절) | 400 `PREPARATION_ID_MISMATCH` |
| C8 | 서비스 ID는 토큰에 묶인 값만 쓴다. 본문의 actor·service 문자열은 무시 | — |

- **저장 구조와 AI 기록 경로의 범위를 구분한다.** V10 전체 저장 구조는 **네 표**(준비안 `consultation_preparation`, 섹션 `consultation_preparation_section`, 실행 기록 `consultation_preparation_run`, 매핑 `consultation_family_mapping`)다. 이 가운데 **AI 기록 경로(기록 토큰)가 쓸 수 있는 것은 준비안·섹션·실행 기록 세 표뿐**이다. 매핑 표는 별도 경로(사용자가 승인한 매핑 파일을 Core bootstrap importer가 적재)로만 등록·갱신되며, 기록 endpoint에는 매핑을 쓰는 코드가 없고 AI 기록 토큰으로는 매핑을 변경할 수 없다(통합 테스트로 확인, AC-10).
- **기록 기능이 바꿀 수 없는 것(확인 항목으로 둔다).** 기록 endpoint의 저장소 코드는 준비안·섹션·실행 기록 세 표에만 INSERT한다. 통합 테스트가 기록 호출 전후로 `human_review_decision`, `approved_checklist_version`, `approved_checklist_schedule_revision`·`entry`, `checklist_change_proposal`, `automated_validation_result`, `internal_notice_lifecycle_event` 행 수가 변하지 않음을 확인한다(AC-10). 제안 5의 선택지에 따라 별도 DB 역할로 권한 자체를 막을 수도 있다.
- **확인과 저장 사이의 변화(보장 범위를 정확히).** C1~C5(와 M1~M5)와 INSERT를 **하나의 DB 트랜잭션**에서 `SERIALIZABLE` 격리로 실행한다. 이 격리가 보장하는 것은 "이 트랜잭션이 읽은 승인·일정·공문 사건 행과, 같은 시간에 그 행을 바꾸는 다른 트랜잭션(철회·반려·새 승인)이 **직렬로 실행된 것과 같은 결과**"다. 즉 재확인이 본 상태와 저장된 기록이 서로 어긋나는 일은 막는다. 충돌하면 PostgreSQL이 둘 중 하나를 직렬화 실패(SQLSTATE 40001)로 끝낸다. **보장하지 않는 것:** 기록 트랜잭션이 커밋된 **뒤**에 일어나는 철회·반려는 어떤 격리로도 막을 수 없다. 그래서 **READY 섹션의 기록은 "그 시점에 Core가 사용 가능과 항목·근거 일치를 직접 확인했다"는 증거**이고, **HOLD 섹션의 기록은 "서비스가 보고한 보류"와 "저장 시 Core가 직접 확인한 상태"를 구분해 남긴 기록**이다. 어느 쪽도 사용 허가가 아니며, 사용 허가는 보여 주는 순간 Tool 1 재확인으로 다시 얻는다(TASK-017).
- **1회 재시도의 대상.** 재시도는 **직렬화 실패(40001)가 난 그 DB 트랜잭션에 한정**해 1회만 한다. 재시도는 재확인부터 다시 수행한다(바뀐 상태를 보고 거부할 수 있다). AI 서비스의 Tool 호출, 기록 HTTP 요청 자체, 다른 오류(제약 위반, 연결 끊김, 422·409 거부)는 재시도하지 않는다. 재시도도 실패하면 500과 실행 기록 FAILED(`SERIALIZATION_FAILED`)를 남긴다.
- **실패·중복 실행 기록.** 실행 기록은 요청 하나에 하나다. 재시도 두 번은 실행 기록 두 개가 아니며 최종 결과 하나만 REQUIRES_NEW로 남긴다. `run_id`는 AI 서비스가 요청마다 새로 만들고 Core 실행 기록 표의 PK다. 같은 `run_id`가 다시 오면(클라이언트의 재전송) 409 `RUN_ID_CONFLICT`로 거부하고 저장하지 않는다. 같은 준비안을 다시 기록하려면 새 `run_id`로 보내야 하며 그때 ALREADY_RECORDED가 된다. 이 한계와 규칙을 ADR-012와 README에 적는다.
- Core가 확인할 수 없는 것: 신청 자료(`SW-APPLICATION-001`)는 Core에 없는 파일이다. Core는 형식만 검사하고 AI가 보낸 신청 자료 해시를 그대로 저장한다(5절의 한계).

### 5. 나중에 기록을 다시 확인하기

- **저장하는 재현 정보(근거 원문은 저장하지 않음).** 신청 자료 식별(`application_id`, `company_id`, `product_key`)과 신청·기업 파일의 canonical 해시, 업무일, Core 평가 시각, 공문군 매핑 파일 해시, 조립 규칙 버전(`assembler_version`, 예 `preparation-assembler-v1`), 고정 문구 표 해시(`messages_hash`), 섹션마다 선택 공문 ID·버전, 승인 checklist version ID·결정 ID, 항목 규칙 version ID 순서 목록과 근거 해시, 보류 종류·사유 코드, 참고 재조회 결과, 그리고 Tool 1 응답 본문의 canonical 해시(`tool_response_hash`).
- **"당시 무엇을 보여 줬는지" 확인.** 규칙 version과 승인 checklist version은 append-only라 ID로 그때의 지시 문장·구조화 값·근거 문장을 다시 읽을 수 있고, 근거 해시로 변조 여부를 확인할 수 있다. 조립 규칙 버전과 문구 표 해시로 같은 규칙·같은 문구였는지 알 수 있다. Tool 응답 해시로 "그때 Core가 준 응답"과 재구성 결과를 대조할 수 있다.
- **"지금 다시 사용해도 되는지" 확인.** 기록으로는 알 수 없다. 반드시 Tool 1을 지금 다시 호출해 사용 가능 여부를 얻는다. 기록 표의 어떤 필드도 사용 허가를 뜻하지 않는다.
- **이번 단계의 한계.** (1) 렌더링된 준비안 JSON 전체는 저장하지 않으므로 바이트 단위 재현은 불가능하고 재구성만 가능하다. (2) 재구성 API는 이 Task에 없다(TASK-017 조회 화면에서 검토). (3) 신청 자료는 Core 밖 파일이라 해시만 있고 내용 보증은 파일 저장소에 달려 있다. (4) 고정 문구 표가 바뀌면 옛 문구는 Git 이력으로만 추적된다.

### 6. 행원 메모와 안전성 검사

- **메모 입력의 의미를 바로 적는다.** "메모를 넣어도 결과가 같다"는 검사는 메모가 처리에 영향을 주지 않는다는 것만 보장한다. 질문을 이해하고 안전하게 답하는 능력은 검증하지 않으며, 그것은 TASK-019(LLM)의 몫이다.
- **포함 vs 제외 비교.**

| | 메모 입력 포함 | 메모 입력 제외(이번 단계 권장) |
|---|---|---|
| 사용자 이득(지금) | 거의 없음. 준비안이 메모를 쓰지 않고 출력에도 없다 | 없음 |
| 안전성 검사 | SAFE-C(메모 무영향)를 실제 입력 경로로 시험 가능. 단 "질문 이해" 검증은 아님 | 자유 문장 입력 경로가 없으므로 안전성 참조 3건(S10·E22·E23)은 **입력될 수 없음**. 구조 검사(SAFE-A·B·D·E)로 "결정 필드·문구가 생길 수 없음"을 보장 |
| 위험 | 자유 문장이 쓰이는 것처럼 보임. 저장하지 않아도 입력 경로 자체가 공격면 | TASK-019에서 입력 경로를 새로 설계해야 함 |
| 복잡도 | 필드·검사·해시 기록 추가 | 최소 |

- **채택(제안 4 수정안, 결정자 사용자): 이번 단계에서는 메모 입력을 뺀다.** 안전성 평가 책임은 다음과 같이 나눈다. **TASK-015 = 규칙 조립 출력의 구조 검증**(결정 필드·문구가 존재할 수 없음, 보류 섹션에 근거 없음, 사람 판단 안내 필수, 자유 문장 입력 경로 없음). **TASK-019 = 질문 기반 사례(S10·E22·E23)와 LLM 출력 검증.** 기존 안전성 참조 3건은 그대로 유지한다. 파일·schema·계약 테스트의 책임 표시는 제안 7의 (b)로 채택됐다. 안전성 참조 자료 v2에서 책임을 객체로 구분하고, 기존 v1과 TASK-014의 완료 기록은 보존하며 "후속 변경" 관계만 덧붙인다. 검색 골든셋 버전과 안전성 질문 3건·참조 ID는 유지하고, 실제 변경은 구현 단계에서 한다.
- 메모를 포함하기로 판단한다면: 결과 동일성은 준비안의 **업무 내용**(1절 상태, 섹션, 항목, 보류 사유, 안내 문구)으로 비교하고 `evaluated_at`, `run_id`, 기록 결과는 비교에서 뺀다. 메모 본문은 출력·기록 어디에도 없고 길이와 해시만 기록한다.

### 7. 기록 권한

| | 같은 토큰(읽기+기록) | 기록용 토큰 분리(`TRUST_AGENT_PREPARATION_RECORD_TOKEN`) |
|---|---|---|
| 권한 범위 | 토큰 하나가 Tool 읽기와 기록을 모두 연다. 유출 시 둘 다 노출 | 최소 권한. 읽기 토큰만으로는 기록 불가, 기록 토큰만으로는 읽기 불가 |
| 운영 | 환경변수 1개, 필터 설정 1개 | 환경변수 2개, 필터 또는 설정 2개. 회수·교체를 따로 할 수 있음 |
| 감사 | 서비스 ID 하나 | 서비스 ID는 같고 토큰 종류(`scope`)를 실행 기록에 남김 |
| 실패 처리 | 토큰 없으면 읽기·기록 모두 401 | 기록 토큰만 없으면 읽기는 되고 기록만 401 → 준비안은 나오되 `recorded=false` |
| 구현 비용 | 필터 경로 확장만 | 필터 설정 확장과 테스트 2건 추가 |

- **권장(제안 5 수정안): 기록용 토큰을 분리한다.** 비용이 작고 "읽기용 토큰으로 기록까지 허용"하는 권한 확대를 피한다. 두 토큰 모두 환경변수로만 제공하며 production은 둘 중 하나라도 없으면 기동을 거부한다(ADR-011 9항과 같은 방식). 별도 DB 역할(기록 표에만 INSERT)은 선택지로 ADR-012에 적되 이번 단계 기본안은 코드 범위 + 통합 테스트(AC-10)다.
- ADR-012 초안(미승인): 누가(기록 토큰을 가진 `ai-service`) 무엇을(준비안·섹션·실행 기록 세 표에 INSERT) 할 수 있고, 무엇을 못 하는지(승인·일정·변경안·검증·공문 상태·감사 표 변경, **매핑 표 등록·변경**(별도 bootstrap 경로 전용), 기록 수정·삭제, 사용 허가 부여), 인증 실패(401, 저장 없음, 실행 기록 없음, AI 출력 `recorded=false`)와 기록 실패(500, 롤백, 실행 기록 FAILED를 REQUIRES_NEW로 남김, 그것마저 실패하면 `FAILURE_AUDIT_WRITE_FAILED`) 처리를 적었다. 승인 전까지 "초안"이다.

### 8. 검증 방법과 변경 범위

- **연결 검증 환경(Core + Python 동시 실행 자동 테스트).**
  - 위치: Java 통합 테스트 `AiServicePreparationIntegrationTest`(Testcontainers PostgreSQL, Core를 임의 포트 web 컨텍스트로 기동, TASK-011a 상태 적재와 승인 재현).
  - Python 준비: `python3 -m venv build/ai-venv && pip install -r apps/ai-service/requirements-ai.txt`를 Gradle 작업 `prepareAiServiceEnv`가 수행(의존성 lock 파일 `requirements-ai.lock`로 고정).
  - **필수 CI에서 실제 실행, 로컬은 명시적 건너뜀.** 두 모드를 구분한다. (a) 로컬 기본: Gradle 속성 `-PaiServiceIntegration` 없음 → 테스트가 `assumeTrue`로 건너뛰고 보고서에 "명시적 skip(로컬 기본)"을 남긴다. (b) 필수 CI: workflow가 `TRUST_AGENT_REQUIRE_AI_INTEGRATION=1`을 설정하고 Python 설치 단계를 먼저 실행한다. 이 변수가 있으면 건너뜀이 허용되지 않으며, Python·venv·의존성·Core 기동 중 하나라도 없으면 **환경 누락 실패**(`AI_INTEGRATION_ENV_MISSING`, 어떤 항목이 없는지 메시지)로 테스트가 실패한다. 즉 CI에서는 "환경이 없어서 조용히 건너뜀"이 불가능하다. 이 테스트는 기존 Gradle tests 작업(필수 체크)에 포함한다.
  - 실행: `ProcessBuilder`로 `build/ai-venv/bin/python -m ai_service prepare …`를 환경변수(URL, 토큰, 시간 초과)와 함께 실행. 프로세스 시간 제한 60초, Tool 호출 시간 초과 5초. 제한 초과 시 프로세스를 `destroyForcibly`하고 실패.
  - 출력 수집: stdout(준비안 JSON)과 stderr를 파일(`build/reports/ai-service/<test>.{out,err}.txt`)로 저장하고 실패 시 테스트 메시지에 마지막 50줄을 포함한다. Core 로그는 기존 Gradle 테스트 로그에 남는다.
  - 종료: `@AfterAll`에서 프로세스 종료 확인, Core 컨텍스트 close, 컨테이너 정지. 포트는 `server.port=0`.
  - CI: Python contracts 작업에 AI 서비스 단위·계약 테스트를 추가하고, Gradle 작업에 Python 설치 단계와 `-PaiServiceIntegration=true`를 추가한다(CI 설정 변경은 이 Task 범위에 포함, diff로 검토).
  - 대안: 사람이 두 프로세스를 띄워 실행하고 출력을 evidence에 붙인다. 자동 테스트가 어려울 때의 보조이며 대체가 아니다.
- **기존 테스트 수 유지(Java 158, Python 75)**와 새 테스트 추가. 기존 공문 승인·일정·변경안·검증 코드의 diff가 없음을 검토로 확인한다.

### 입력과 출력

| 구분 | 항목 | 설명 |
|---|---|---|
| 입력 | `application_id` | 합성 신청 ID(`datasets/synthetic/work/`). 없으면 400 |
| 입력 | `business_date` | 선택. 생략 시 서울 오늘. Tool 1에 그대로 전달 |
| 입력 | `consultation_id` | 선택, 64자 이내, 추적용. 권한에 영향 없음 |
| 입력 | (`consultation_note`) | 제안 4 판단에 따름. 권장안에서는 없음 |
| 설정 | `TRUST_AGENT_CORE_BASE_URL`, `TRUST_AGENT_TOOL_SERVICE_TOKEN`, (`TRUST_AGENT_PREPARATION_RECORD_TOKEN`), `TRUST_AGENT_CORE_TIMEOUT_SECONDS`(기본 5) | 환경변수만. 토큰은 파일·로그·출력에 남기지 않는다. 업무 DB 자격증명 설정 항목은 없다 |
| 설정 | 공문군 매핑 | `datasets/synthetic/work/consultation-family-mapping.json`(1절. Core와 같은 파일, 요청에 해시를 보냄) |
| 출력 | 준비안 JSON | 아래 계약 |
| 출력 | 기록 결과 | `record.status` RECORDED / ALREADY_RECORDED / REJECTED / FAILED, `preparation_id`, `run_id`, 오류 코드·설명, `usage_notice` |
| 종료 코드 | CLI | 0 기록 성공(RECORDED·ALREADY_RECORDED, 상태 READY·PARTIAL·HOLD 모두). 2 입력 오류. 3 기록 거부·실패(준비안은 출력됨, 사용 금지 안내). 4 설정 누락 |

준비안 계약 `contracts/consultation-preparation.schema.json`(초안, `additionalProperties: false`):

```text
preparation_id, run_id, assembler_version, messages_hash, family_mapping_hash
dataset_class "SYNTHETIC_WORK", synthetic true, disclaimer
application { application_id, company_id, legal_name, product_key, requested_amount_krw, purpose, source_hash }
business_date, evaluated_at, consultation_id?
status READY | PARTIAL | HOLD,  preparation_complete (READY일 때만 true),  headline (고정 문구)
remaining_checks[] { family_id, required true, hold_kind, blocking_reasons[], hold_message, manual_checklist_notice }
optional_holds[]   { 같은 구조, required false }
sections[] { family_id, required, status READY|HOLD, hold_kind? CORE_DECISION|UNVERIFIED,
             selected_notice{…}|null, approved_checklist{version_id, decision_id, effective_from, effective_to}|null,
             items[] { order, rule_key, instruction, evidence_required, structured_change, source_rule_version_id,
                       evidence { notice_id, evidence_text, json_pointer, evidence_hash } },
             blocking_reasons[], warning_reasons[], hold_message|null, manual_checklist_notice|null, tool_response_hash }
notices { human_decision_notice, source_notice, usage_notice }
record { status, core_preparation_id?, recorded_at?, error_code?, error_message? }
```

허용하지 않는 것: 결정 필드(`decision`, `approved`, `rejected`, `limit`, `credit_grade`, `rate_decision` 등 금지 이름 목록), 자유 생성 문장, 토큰, 미승인 변경안·검증 결과·검수자 ID.

### 업무규칙

1. 사용 가능 여부는 Core가 정한다. 섹션 상태는 Tool 1의 `usable`을 그대로 따르며 AI가 사유를 완화하거나 FIXTURE·미승인 항목을 넣지 않는다.
2. 근거 없는 항목은 제공하지 않는다. READY 섹션의 모든 항목은 Tool 2 근거가 있어야 하며 하나라도 실패면 섹션 전체를 HOLD(`UNVERIFIED`, `EVIDENCE_UNAVAILABLE`)로 바꾼다.
3. 보류 사유는 그대로 전달하고 보류 종류(`CORE_DECISION`/`UNVERIFIED`)를 구분한다. 사람이 읽는 설명은 고정 문구 표에서 가져오고, 표에 없는 코드는 "Core가 사용 불가로 판정했습니다(코드)"로 쓰며 HOLD를 유지한다.
4. 준비안 전체 상태는 필수 공문군만으로 정하고(1절), PARTIAL·HOLD에는 "상담 준비가 끝나지 않았습니다"가 반드시 표시된다.
5. 결정하지 않는다. 준비안은 "확인할 것"과 근거만 담고, 결정 주체가 사람임을 고정 안내로 적는다.
6. 기록 없는 준비안은 완료가 아니다. 거부·실패는 숨기지 않고 사용 금지 안내를 붙인다.
7. 재실행은 항상 Core를 다시 보며, 저장만 멱등이다(3절).

### 상태전이

| 현재 | 사건 | 다음 | 금지 |
|---|---|---|---|
| (없음) | Tool 1 usable=true, 모든 항목 Tool 2 성공 | 섹션 READY | Tool 2 하나라도 실패인데 READY |
| (없음) | Tool 1 usable=false | 섹션 HOLD `CORE_DECISION` | 사유 삭제·완화 |
| (없음) | Tool 401/403/404/5xx/시간 초과/응답 schema 위반 | 섹션 HOLD `UNVERIFIED` | 추측 준비안 |
| 준비안 작성 | Core 기록 요청 | RECORDED / ALREADY_RECORDED / REJECTED / FAILED | 거부를 성공으로 출력 |
| 기록됨 | 같은 ID·같은 내용 재기록 | ALREADY_RECORDED, 새 run 기록 | 중복 준비안 행 |
| 기록됨 | 수정·삭제 | (없음) | append-only. 수정은 새 준비안 |
| 기록됨 | 화면에서 사용 | Tool 1 재확인 뒤 사용 가능일 때만 | 기록만 보고 사용 |

### 데이터 영향 (Core, V10)

- `consultation_preparation`(append-only, 보호 목록 추가): `preparation_id`(PK), `service_id`, `token_scope`, `application_id`, `company_id`, `product_key`, `application_source_hash`, `business_date`, `evaluated_at`, `status`(READY/PARTIAL/HOLD), `preparation_complete`, `assembler_version`, `messages_hash`, `family_mapping_hash`, `section_count`, `required_hold_count`, `consultation_id`(nullable), `content_hash`, `recorded_at`.
- `consultation_preparation_section`(append-only): `preparation_id`, `family_id`, `required`, `status`, `hold_kind`(nullable), `selected_notice_id`(nullable), `approved_checklist_version_id`(nullable), `decision_id`(nullable), `item_rule_version_ids` jsonb(순서 목록), `item_evidence_hashes` jsonb, `blocking_reasons` jsonb, `recheck_usable`(nullable, HOLD 참고 재조회), `recheck_reasons` jsonb, `tool_response_hash`. PK(`preparation_id`, `family_id`). CHECK: READY면 version·결정 ID NOT NULL이고 항목 목록 비어 있지 않음; HOLD면 둘 다 NULL, 항목 비어 있음, `hold_kind` NOT NULL, `blocking_reasons` 1개 이상.
- `consultation_preparation_run`(append-only): `run_id`(PK, 같은 값 재전송은 409), `preparation_id`(nullable), `service_id`, `token_scope`, `outcome`(RECORDED/ALREADY_RECORDED/REJECTED/FAILED), `error_code`(nullable), `trace_id`, `started_at`, `finished_at`. 요청당 1건이며 거부·실패는 REQUIRES_NEW로 남긴다.
- `consultation_family_mapping`(append-only): `mapping_hash`(PK), `mapping_version`, `product_key`, `family_id`, `required`, `order`, `loaded_at`. 같은 파일을 bootstrap importer가 적재하며 활성 매핑은 최신 `mapping_version` 하나다. 섹션 행에는 `hold_claim_basis`(`CORE_REPORTED`/`SERVICE_REPORTED`)도 둔다.
- **근거 원문 문장, 메모 본문, 토큰은 저장하지 않는다.**
- 권한: `trust_agent_runtime` SELECT·INSERT. 보호 목록 함수에 세 표 추가, `AppendOnlyBootstrapChecks` 검사.

### API

- AI 서비스: `POST /api/v1/ai/consultation-preparations` 본문 `{applicationId, businessDate?, consultationId?}` → 준비안 JSON. 로컬·demo 전용(기본 127.0.0.1), 사용자별 인증은 후속 ADR. CLI `python -m ai_service prepare --application … [--business-date …] [--consultation-id …]`는 같은 함수.
- Core 기록 경로: `POST /api/v1/consultation-preparations`, 헤더 `Authorization: Bearer <기록 토큰>`(제안 5), 본문 `contracts/consultation-preparation-record.schema.json`(준비안에서 근거 원문을 뺀 기록용). 응답 201 RECORDED / 200 ALREADY_RECORDED / 400 schema·ID 불일치 / 401 / 409 STALE·CONFLICT / 422 NOT_USABLE / 500. `/api/v1/tools/` 밖이며 Tool allowlist에 넣지 않는다(ADR-011 유지).

### 트랜잭션

Core 기록은 트랜잭션 하나(SERIALIZABLE, 직렬화 실패 1회 재시도): READY 섹션 재확인 C1~C5 → HOLD 섹션 참고 재조회 → 준비안·섹션 INSERT → 실행 기록 RECORDED. 실패·거부는 롤백 뒤 실행 기록만 REQUIRES_NEW. 외부 호출은 트랜잭션 안에 없다. AI 서비스 쪽에는 트랜잭션이 없고 Tool 호출 N번과 기록 1번은 순차다.

### 실패 시나리오

| 상황 | 동작 | 기록 |
|---|---|---|
| Tool 1 401/403 | 섹션 HOLD `UNVERIFIED`/`TOOL_AUTH_FAILED` | 기록 토큰이 별도면 기록은 가능(HOLD로). 같은 토큰이면 기록도 401, `recorded=false`, 종료 3 |
| Tool 1 404(공문군 없음) | 섹션 HOLD `CORE_DECISION`/`POLICY_FAMILY_NOT_FOUND` | 기록됨 |
| Tool 1 5xx/시간 초과/연결 실패/응답 schema 위반 | 섹션 HOLD `UNVERIFIED`/`CORE_UNAVAILABLE`·`CORE_TIMEOUT`·`TOOL_RESPONSE_INVALID` | 기록 시도, 실패면 `recorded=false` |
| Tool 2 한 항목 403·오류 | 섹션 전체 HOLD `UNVERIFIED`/`EVIDENCE_UNAVAILABLE` | 기록됨 |
| 기록 재확인 거부(422/409) | `record.status=REJECTED`, 사용 금지 안내, 종료 3 | 실행 기록 REJECTED, 준비안 행 없음 |
| 같은 ID 같은 내용 재기록 | ALREADY_RECORDED | 새 run 행, 준비안 행 그대로 |
| 같은 ID 다른 내용 | 400 ID 불일치 또는 409 CONFLICT | 실행 기록 REJECTED |
| 동시 같은 ID | 한쪽 RECORDED, 다른 쪽 ALREADY_RECORDED | 준비안 행 1개, run 행 2개 |
| 매핑에 없는 상품 / 신청 ID 없음 / schema 위반 | 400, 종료 2 | 없음 |
| 설정 누락 | 시작 거부, 종료 4 | 없음 |
| 자동 재시도 | 없음(직렬화 실패 1회 제외). 사람이 재실행, 멱등 | — |

### 안전성 기준 (TASK-014 안전성 참조 3건의 TASK-015 적용, 결정자 사용자 확정 필요)

규칙 조립 출력에 대한 기준이다. LLM 출력에는 적용하지 않으며 LLM 안전성은 TASK-019에서 별도 검증한다. 통과해도 "LLM 안전성 검증 완료"가 아니다.

| ID | 기준 | 검증 |
|---|---|---|
| SAFE-A | 준비안·기록 계약에 결정 필드가 없고 `additionalProperties: false`. 금지 필드명 목록을 계약 테스트가 schema에서 대조 | Python 계약 테스트 |
| SAFE-B | 모든 자유 문장은 고정 문구 표·항목 지시 문장·규칙 원문·구조화 값에서만 오고, 고정 문구 표에 결정 표현(금지 표현 목록)이 없다 | Python 단위 테스트 |
| SAFE-D | `human_decision_notice`가 모든 상태의 준비안에 있다 | Python 계약 테스트 |
| SAFE-E | HOLD 섹션에 항목·근거 ID·규칙 원문이 없다 | Python 단위 + Core 통합(422) |
| SAFE-F | 자유 문장 입력 경로가 없다(권장안). 메모를 포함하면 SAFE-C(업무 내용 동일성, 본문 미저장)로 대체 | 계약·단위 테스트 |
| SAFE-G | 안전성 참조 자료 v2에서 책임이 객체로 구분됨(`structural: TASK-015`, `question_based: TASK-019`). v1 파일과 TASK-014 완료 기록 보존, 검색 골든셋 버전 v1 유지, 질문 3건과 참조 ID 유지. 질문 기반 사례(S10·E22·E23)는 TASK-019에서 검증 | Python 계약 테스트(v2) + diff 검토 |

### 테스트 (각 테스트가 보장하는 것)

- Python 단위(가짜 Core fixture, `contracts/fixtures/tool-applicable-checklist.expected.json` 재사용): READY 조립, CORE_DECISION 보류, UNVERIFIED 보류(401·5xx·시간 초과·schema 위반), Tool 2 부분 실패 시 섹션 HOLD, PARTIAL 상태와 `remaining_checks`·`headline`, 선택 공문군 HOLD는 상태 불변, 매핑 없는 상품 400, 준비안 ID 해시 대상(evaluated_at 제외), 고정 문구 금지 표현 없음, 설정 누락 시작 거부, DB 자격증명 설정 없음, 사유 코드 고정 문구 표가 Core 코드 목록과 대조.
- Python 계약: 준비안·기록 요청·매핑 schema, 금지 필드, `human_decision_notice` 필수.
- Core 통합(Testcontainers): READY 기록 성공과 세 표 행 구조·재현 정보, C1~C7 각각의 거부와 실행 기록 REJECTED, HOLD 섹션 그대로 기록과 참고 재조회 값, ALREADY_RECORDED와 새 run, 동시 같은 ID 1행, ID 불일치 400, 토큰 없음·읽기 토큰으로 기록 시도 401(제안 5), append-only·보호 목록·권한, CHECK 제약, 기록 전후 승인·일정·변경안·검증·사건 표 행 수 불변, 실행 기록 REQUIRES_NEW.
- 연결 검증(8절): CLI 실행으로 PARTIAL 준비안(중도상환수수료 READY, 셀러론 HOLD)과 기록 ID, 재실행 ALREADY_RECORDED, 철회 뒤 재실행 시 새 ID와 HOLD.

## Acceptance Criteria (초안, 구현 전 고정)

| ID | 입력/상황 | 기대 결과 | 검증 방법 | 결과/evidence |
|---|---|---|---|---|
| AC-01 | 승인된 중도상환수수료 공문군, 업무일 2026-10-06, `SW-APPLICATION-001` | 섹션 READY. 항목 3개가 Tool 1 응답과 같고 항목마다 Tool 2 근거(문장·위치·해시), version ID·결정 ID 포함 | Python 단위 + 연결 검증 | **통과**(로컬). Python `test_partial_preparation_has_ready_section_with_evidence_and_core_decision_hold`, 연결 검증 `prepareCommandAssemblesPartialPreparationAndRecordsIt`(항목 3개, 규칙 원문 "0.8퍼센트", version·결정 ID) |
| AC-02 | 셀러론 공문군(필수, 미승인) 같은 신청 건 | 섹션 HOLD `CORE_DECISION`/`HUMAN_REVIEW_PENDING`, 항목·근거 없음, 설명·수기 안내. 전체 PARTIAL, `preparation_complete=false`, `remaining_checks`에 셀러론, headline에 "끝나지 않았습니다" | Python 단위 + 연결 검증 | **통과**(로컬). 같은 테스트. 사실: Core는 셀러론 사유로 `HUMAN_REVIEW_PENDING`과 함께 `APPROVED_CHECKLIST_NOTICE_MISMATCH`(예시 checklist 일정 불일치)도 주며 둘 다 전달·저장됨 |
| AC-03 | 모든 필수 공문군 사용 불가(업무일 2026-09-30) | 전체 HOLD, 추측 항목 없음, HOLD 섹션이 Core에 기록됨 | Python 단위 + Core 통합 | **통과**(로컬). Python `test_all_required_hold_gives_hold_without_guessed_items`, Core `holdSectionsAreCheckedAndStoredWithClaimBasis`(전체 HOLD 201 기록), `tamperedReadyClaimsAreRejectedWithoutRows`(업무일 2026-09-30 READY 주장 422) |
| AC-04 | Tool 2가 한 항목에 403 | 섹션 전체 HOLD `UNVERIFIED`/`EVIDENCE_UNAVAILABLE`, 다른 항목 근거도 제공하지 않음 | Python 단위 | **통과**(로컬). Python `test_single_evidence_failure_holds_whole_section` |
| AC-05 | Tool 401/403/5xx/시간 초과/응답 schema 위반 | 섹션 HOLD `UNVERIFIED`(코드 구분), 추측 없음. 기록 실패 시 `recorded=false`·사용 금지 안내·종료 3 | Python 단위 | **통과**(로컬). Python `test_unverified_holds_for_auth_server_timeout_and_invalid_responses`, `test_record_outcomes_map_to_status_and_usage_notice`(REJECTED·FAILED 모두 `record.recorded=false`), CLI 종료 코드 테스트, 연결 검증 `wrongRecordTokenLeavesPreparationUnrecorded`(종료 3, `recorded=false`). 표현: `recorded=false`는 계약의 `record.recorded`(boolean, 필수)와 `record.status`·`usage_notice`·종료 코드 3으로 구현(보완 3절 대조표) |
| AC-06 | Core 기록: READY 준비안 | 201, 세 표 행과 재현 정보(5절), 근거 원문 미저장 | Core 통합 | **통과**(로컬). PARTIAL 준비안의 READY 섹션: `partialPreparationIsRecordedWithRecheckValuesAndRunRecord`(201, 세 표 행, 재현 정보, 원문 미저장). 전체 READY 준비안: 별도 환경 `ConsultationPreparationReadyIntegrationTest`에서 셀러론까지 승인한 뒤 `fullyReadyPreparationIsRecorded`(201, status READY·complete true·필수 보류 0·섹션 2개 READY·재확인 usable 2건)와 CLI `prepareCommandProducesFullyReadyPreparation`(보완 1절) |
| AC-07 | Core 기록: C1~C5 각각 어긋남(철회·반려·항목 누락·순서 변경·근거 해시 변조·선택 공문 변경) | 422/409 해당 코드, 준비안 행 없음, 실행 기록 REJECTED | Core 통합 | **통과**(로컬). `tamperedReadyClaimsAreRejectedWithoutRows`(결정 ID·항목 순서·근거 해시·선택 공문·항목 누락 → 409, FIXTURE 기간 → 422), `withdrawalKnownAfterRecordingRejectsTheSameClaim`(철회 → 422). 반려는 승인된 checklist에 일어나는 전이가 아니라 재확인 사례에서 제외 |
| AC-08 | 같은 ID 같은 내용 재기록, 동시 2회, 같은 ID 다른 내용 | ALREADY_RECORDED(새 run), 행 1개, 400/409 거부. **같은 ID 재요청에서도 C1~C5 재확인이 실행됨**(재확인 전에 철회하면 기존 행이 있어도 422 거부, 재확인 조회 호출이 기록됨) | Core 통합 | **통과**(로컬). `sameContentIsRecordedOnceAndRerunStillRechecksCurrentState`(평가 시각·Tool 응답 해시를 바꿔도 같은 ID로 ALREADY_RECORDED 새 run, 승인 전 시각 재요청 422, 실행별 추적 값 저장), `concurrentSameIdRecordsOnce`(201+200, 행 1), 같은 ID 다른 내용은 ID가 내용 해시라 400 `PREPARATION_ID_MISMATCH`(`idHashRunIdAndSchemaRulesAreEnforced`). 409 `PREPARATION_CONFLICT`는 코드상 정상 경로로 만들 수 없는 방어선(보완 4절) |
| AC-09 | 토큰 없음·불일치, 읽기 토큰으로 기록, schema 밖 필드, 본문 actor | 401 / 400, 서비스 ID·scope는 토큰 기준 | Core 통합 | **통과**(로컬). `recordAndToolTokensAreNotInterchangeable`, 모르는 필드(actor 포함) 400, 서비스 ID·scope는 필터가 토큰으로 정함 |
| AC-10 | 기록 호출 전후 | 승인·일정·변경안·검증·공문 사건 표 행 수 불변. 세 표 append-only·보호 목록·CHECK | Core 통합 | **통과**(로컬). `businessTablesAreUntouchedAndRecordTablesAreAppendOnly`(행 수 불변, 42501, 권한, 보호 목록 4, CHECK 23514) |
| AC-11 | 안전성 SAFE-A·B·D·E·F·G | 전부 통과 | Python 계약·단위 + Core 통합 + diff | **통과**(구조 검증, 로컬). Python `SafetyTest` 4건(결정 필드 없음·금지 표현 없음·사유 표 대조·허용 목록), Core HOLD 섹션 항목 금지(400·CHECK), `human_decision_notice` 모든 출력에 포함, diff 검토. 질문 기반·LLM 출력 검증은 TASK-019 |
| AC-12 | AI 서비스 설정·의존성 | 업무 DB 자격증명 항목 없음, DB 드라이버 의존성 없음 | Python 단위 + 검토 | **통과**(로컬). `test_no_database_credential_settings_or_drivers`, `requirements-ai.txt` 검토(fastapi·uvicorn·httpx만 추가) |
| AC-13 | 재실행 | 기존 기록이 있어도 Tool 1·2를 다시 호출함(가짜 Core 호출 횟수), 상태 불변 시 ALREADY_RECORDED, 철회 뒤 새 ID HOLD | Python 단위 + 연결 검증 | **통과**(로컬). Python `test_rerun_calls_core_tools_again_even_when_already_recorded`, 연결 검증 `rerunRechecksCoreAndRecordsOnlyOnce`·`stateChangeProducesNewHoldPreparation`·`withdrawalAfterRecordingProducesNewHoldPreparation` |
| AC-14 | 연결 검증 자동 테스트 | 필수 CI(Gradle tests 체크)에서 실제 실행되고 통과. 로컬 기본은 명시적 skip으로 보고서에 남고, CI에서는 환경 누락이 실패(`AI_INTEGRATION_ENV_MISSING`)로 드러남. 시간 제한, 출력 파일 수집, 실패 시 프로세스 종료 | Java 통합 + CI 로그 + 의도적 환경 누락 실패 1회 확인 | **통과**. 필수 CI(PR #40 2차 실행) 로그에서 연결 검증 5건과 전체 READY CLI 1건 `TEST SUCCESS`, skip 0건, 합계 181/181 확인. 로컬 기본 실행은 skip 6건이 보고서에 남음, 의도적 환경 누락 1회 `AI_INTEGRATION_ENV_MISSING` 실패 확인, 60초 제한·출력 파일·강제 종료 구현 |
| AC-15 | 기존 테스트 수·결과 유지, 기존 승인·일정·변경안·검증 코드 diff 없음, README·AI 서비스 README·ADR-012가 실제 상태 기술, "AI 생성 완료"·"LLM 안전성 검증 완료" 표현 없음, "기록은 사용 허가가 아님"과 "READY는 상담·대출 결정의 완료가 아님" 명시 | 로컬·CI + diff 검토 | **통과**(로컬, diff 검토). 기존 테스트는 기대값 갱신(version 10, 표 40·trigger 80, 기록 토큰 필수)만 변경. README 3곳·ADR-012 상태 기술, "기록은 사용 허가가 아님"·"READY는 기준 자료 준비 완료이며 고객별 적용 조건·제출서류 확인이나 상담·대출 결정의 완료가 아님" 명시. "AI 생성 완료"·"LLM 안전성 검증 완료"는 부정문(아니다)으로만 등장 |
| AC-16 | 매핑 불일치 요청: 필수 섹션 누락, `required` 값 변경, 섹션 상태와 다른 전체 READY 주장, 다른 `family_mapping_hash` | 409 `MAPPING_MISMATCH` / 422 `REQUIRED_SECTION_MISSING`·`REQUIRED_FLAG_MISMATCH`·`PREPARATION_STATUS_INVALID`, 준비안 행 없음, 실행 기록 REJECTED | Core 통합 | **통과**(로컬). `mappingAndStatusClaimsAreRejected` |
| AC-17 | 필수 공문군이 없는 매핑 | AI 서비스는 HOLD·`preparation_complete=false`·`NO_REQUIRED_FAMILY_CONFIGURED`, Core는 READY 요청을 422 `NO_REQUIRED_FAMILY`로 거부 | Python 단위 + Core 통합 | **통과**(로컬). Python `test_mapping_without_required_family_never_becomes_ready`, Core `mappingWithoutRequiredFamilyNeverAllowsReady` |
| AC-18 | HOLD 섹션 기록의 공통 검사와 보증 범위 | 항목·근거가 있는 HOLD 섹션은 400 `HOLD_SECTION_INVALID`. 저장된 HOLD 행에 `hold_claim_basis`와 `recheck_*`가 있고, AI가 보낸 사유와 재조회 사유가 달라도 둘 다 저장됨 | Core 통합 | **통과**(로컬). `holdSectionsAreCheckedAndStoredWithClaimBasis`(항목 있는 HOLD 400, SERVICE_REPORTED 보류에 `recheck_usable=true`가 함께 저장됨), `partialPreparationIsRecordedWithRecheckValuesAndRunRecord` |
| AC-19 | 직렬화 실패와 실행 기록 | 동시 철회와 기록이 충돌하면 재확인부터 1회 재시도, 재시도 실패는 FAILED(`SERIALIZATION_FAILED`) 1건. 같은 `run_id` 재전송은 409 `RUN_ID_CONFLICT`. 요청당 실행 기록 1건 | Core 통합 | **통과**(로컬). `serializationFailureIsRetriedOnceAndSecondFailureIsRecordedAsFailed`(commit 단계 40001 주입: 1회 재시도 성공, 2회 실패 → FAILED 1건), `idHashRunIdAndSchemaRulesAreEnforced`(RUN_ID_CONFLICT, 실행 기록 1건) |
| AC-20 (4차 보완 추가, 사용자 요청) | 읽기 토큰과 기록 토큰이 같은 값으로 설정됨 | 모든 profile에서 기동 거부(`SERVICE_TOKEN_NOT_SEPARATED`), 오류·로그에 토큰 값 없음. 다른 값이면 정상 기동 | Core 단위·컨텍스트 테스트 | **통과**(로컬). `ServiceTokenSeparationTest` 4건(같은 값 거부·값 미노출, 다른 값·빈 값 통과, 컨텍스트 거부·정상) |
| AC-21 (5차 보완 추가, 제안 9 수정 채택) | HTTP 진입점에서 기록 성공 / Core 422 / Core 409 / Core 401·토큰 미설정 / Core 400 / 연결 실패·Core 5xx / 시간 초과 | 200 / 422 / 409 / 503 / 500 / 502 / 504. 실패 본문에 `recorded=false`·원인 코드·사용 금지 안내 유지, 헤더 `X-Preparation-Recorded`와 CLI 종료 코드(0/3) 일관, 토큰·내부 오류 상세 비노출 | Python 단위(HTTP·조립·CLI) | **통과**(로컬). `test_http_entry_point_returns_preparation_and_maps_errors`(8사례), `test_record_outcomes_map_to_status_and_usage_notice`, CLI 종료 코드 테스트 |

### 완료 기준 변경 이력

기준을 낮춘 변경은 없다. 추가 2건: 4차 보완에서 사용자 요청으로 **AC-20(읽기·기록 토큰 같은 값이면 기동 거부)**을, 5차 보완에서 제안 9 수정 채택으로 **AC-21(HTTP 진입점의 기록 실패 상태 구분: 200/422/409/503/500/502/504와 본문·헤더·CLI 종료 코드 일관)**을 추가했다. 6차 보완(업무 인터뷰 반영)에서 READY의 뜻을 "기준 자료 준비 완료"로 고정하고 READY 출력에 직원 확인 안내(`notices.staff_check_notice`: 고객별 적용 조건과 제출서류는 직원 확인 필요)를 필수로 더했다. PARTIAL·HOLD는 같은 자리에 준비 미완료 안내가 온다. AC-01·02·06·15의 READY 표현이 이에 맞게 더 엄격해졌고 계약(`consultation-preparation.schema.json`)이 READY 머리 문구에 "기준 자료 준비 완료", 직원 확인 안내에 "직원 확인이 필요합니다"를 요구한다. 또 AC-02·13의 "승인 전" HOLD 사례는 업무일을 평가 당일로 맞춰 미래 업무일 차단이 섞이지 않게 하고 `FUTURE_BUSINESS_DATE` 없음·`HUMAN_REVIEW_PENDING` 있음 단언을 더했다(조건이 더 엄격해짐). 구현 중 세부 설계 확정 2건을 기록한다: (1) 준비안 ID 해시 대상에서 `sections[].evaluated_at`에 더해 `sections[].tool_response_hash`도 제외(제안 8, 사용자 채택. 실행별 값은 실행 기록 `section_evaluations`로 추적). (2) AC-05의 "기록 실패 시 `recorded=false`"는 출력 계약의 `record.recorded`(boolean, 필수. RECORDED·ALREADY_RECORDED일 때만 true)와 `record.status`(REJECTED/FAILED/NOT_ATTEMPTED)·`usage_notice`·CLI 종료 코드 3·HTTP 헤더 `X-Preparation-Recorded`로 구현했다. 사용자 확인 요청(보완 3절) 뒤 `recorded` 필드를 계약에 추가해 계획 표현과 맞췄다.

## Implementation Plan (초안)

1. ADR-012 초안 검토·승인(기록 경로, 토큰 분리, 할 수 있는 것과 없는 것, 실패 처리, 기록 ≠ 사용 허가).
2. 계약·설정: `contracts/consultation-preparation.schema.json`, `contracts/consultation-preparation-record.schema.json`, `contracts/consultation-family-mapping.schema.json`, `datasets/synthetic/work/consultation-family-mapping.json`(합성 시나리오의 사용자 승인 설정. Core와 AI 서비스가 같은 파일을 쓴다).
3. Core: V10 migration(준비안·섹션·실행 기록·매핑 네 표, CHECK, 보호 목록, 권한), 매핑 bootstrap importer, 기록 토큰 설정과 필터, `ConsultationPreparationController`/`Service`(공통 검사, M1~M5, C1~C8, SERIALIZABLE 1회 재시도)/`Repository`, 통합 테스트.
4. AI 서비스: `apps/ai-service/ai_service/`(`config.py`, `core_client.py`, `assembler.py`, `messages.py`, `ids.py`(해시·run_id), `cli.py`, `app.py`), `requirements-ai.txt`·lock, 테스트 `tests/ai_service/`.
5. 연결 검증: Gradle 작업·Java 통합 테스트·CI 단계, evidence `docs/evidence/CONSULTATION_PREPARATION_EVIDENCE.md`, README 3곳 갱신, 안전성 참조 자료 v2(책임 객체 구분)와 schema·계약 테스트 변경, TASK-014 문서 "후속 변경" 절 추가(제안 7, 구현 단계).

예상 크기: Core Java 약 8파일 + migration 1 + 테스트 2클래스, Python 약 8파일 + 테스트 5파일, 계약 3, 설정 1, CI·Gradle 변경, 문서 4. **기존 승인·일정·변경안·검증 코드 변경 없음, 새 저장·재확인 기능 추가.**

대안 검토: (a) 쓰기 Tool로 추가 → ADR-011 위반, 거절. (b) AI 서비스 자체 파일 기록 → 재확인과 "기록 없는 제공 없음"을 잃어 거절. (c) 공문군 하나만 처리 → 보류 사례를 한 명령에서 못 보여 줘 보류. (d) LangGraph → 불필요. (e) 기록 생략하고 출력만 → MVP 7단계의 기록 요구와 어긋나 거절.

위험: "AI가 Core에 쓴다"는 인상 → 업무 상태를 바꾸지 않고 산출물만 기록함을 ADR-012·README에 명시, AC-10으로 검증. 고정 문구 표와 Core 사유 코드 불일치 → 테스트 대조. SERIALIZABLE 재시도 → 1회로 제한, 실패는 FAILED 기록. Python 환경이 CI에 없음 → Gradle 작업으로 준비, 속성 없으면 건너뜀을 보고.

인간의 계획 판단 / 승인 범위: **승인**(결정자 사용자, PR #39). 사용자가 확인한 설계와 한계: 준비된 부분과 보류된 부분의 구분, Core의 저장 전 확인, 기록이 사용 허가를 뜻하지 않음. 승인 범위: 매핑·계약·Core 저장과 재확인·규칙 기반 AI 서비스·필수 연결 CI·안전성 자료 v2. 검색과 LLM 생성은 범위 밖. 구현은 PR #39 병합 뒤 착수하며, 계획과 다른 변경이 필요하면 이유와 영향을 먼저 보고하고 완료 기준을 낮추지 않는다.

## AI 제안 및 인간 판단 기록

### 제안 1: 공문군 매핑(필수 여부 포함)과 READY/PARTIAL/HOLD 상태
- 내용: 상품별 공문군과 필수 여부를 사람이 승인하는 설정 파일로 두고, 전체 상태는 필수 공문군만으로 정한다. PARTIAL·HOLD에는 `preparation_complete=false`, `remaining_checks`, "끝나지 않았습니다" headline이 반드시 붙는다. 대안: 공문군 하나만 입력받아 처리.
- 보완 설명(1절): 필수 여부 소유자는 공문 담당 부서 검수자(사용자)이고 변경은 Task에 기록한다. 선택 공문군 HOLD는 상태를 바꾸지 않고 따로 나열한다.
**판단** - [x] 채택(보완된 방향) / 결정자 사용자 / 검토 대상: 이 계획 PR. 구현 착수는 별도 승인

### 제안 2: 준비안 ID는 업무 내용 해시, 실행 ID는 별도, 저장만 멱등
- 내용: `preparation_id`는 업무 내용 해시(evaluated_at·run·기록 결과 제외), `run_id`는 실행마다 새로. 재실행은 항상 Tool을 다시 호출하고 저장만 멱등. 같은 ID 다른 내용은 Core가 해시 재계산으로 거부. 대안: Core가 ID 발급.
- 보완 설명(3절): 기존 기록이 있다고 현재 상태 확인을 생략하지 않는다. 동시 요청은 PK로 한쪽만 insert되고 다른 쪽은 해시 비교로 ALREADY_RECORDED 또는 CONFLICT.
**판단** - [x] 채택(보완된 방향) / 결정자 사용자 / 검토 대상: 이 계획 PR. 구현 착수는 별도 승인

### 제안 3: 근거 원문은 응답에만, Core 기록에는 재현 정보(ID·해시·버전)만
- 내용: 기록에는 규칙 version ID·근거 해시·checklist version·결정 ID·선택 공문·신청 자료 해시·매핑 해시·조립 규칙 버전·문구 표 해시·Tool 응답 해시를 남긴다. 대안: 원문도 저장.
- 보완 설명(5절): "당시 무엇을 보여 줬는지"는 append-only 버전을 ID로 다시 읽어 재구성하고 해시로 대조한다. "지금 써도 되는지"는 기록으로 알 수 없고 Tool 1 재확인으로만 안다. 바이트 단위 재현, 재구성 API, 신청 파일 내용 보증은 이번 단계의 한계다.
**판단** - [x] 채택(보완된 방향) / 결정자 사용자 / 검토 대상: 이 계획 PR. 구현 착수는 별도 승인

### 제안 4 (수정안): 이번 단계에서는 행원 메모 입력을 뺀다
- 내용: 자유 문장 입력 경로를 두지 않는다. TASK-015의 안전성 기준은 구조 검사(SAFE-A·B·D·E·F)이고, 질문 기반 사례(S10·E22·E23)는 자유 문장 입력이 생기는 TASK-019에서 검증한다. TASK-014 안전성 파일의 `pass_criteria_owner`를 TASK-015(구조)·TASK-019(질문)로 나누는 갱신을 제안한다. 대안: 메모를 포함하되 업무 내용 동일성으로만 비교(SAFE-C).
- 보완 설명(6절): 메모 무영향 검사는 처리에 영향이 없다는 것만 보장하고 질문 이해·안전한 응답 능력은 검증하지 않는다. 지금 메모는 사용자에게 주는 이득이 거의 없고 입력 경로가 공격면만 늘린다.
**판단** - [x] 채택(보완된 방향) / 결정자 사용자 / 검토 대상: 이 계획 PR. 구현 착수는 별도 승인

### 제안 5 (수정안): 기록용 토큰을 분리하고 ADR-012 초안으로 기록 경로를 정한다
- 내용: `TRUST_AGENT_PREPARATION_RECORD_TOKEN`을 따로 두고 기록 경로는 이 토큰만 받는다. 읽기 토큰으로 기록 시도는 401. 둘 다 환경변수만, production은 누락 시 기동 거부. 별도 DB 역할은 선택지로 적되 기본안은 코드 범위 + AC-10. ADR-012는 승인 전까지 초안.
- 보완 설명(7절): 같은 토큰은 권한 확대와 유출 시 범위 확대 문제가 있고, 분리 비용은 환경변수·필터 설정·테스트 2건이다.
**판단** - [x] 채택(보완된 방향) / 결정자 사용자 / 검토 대상: 이 계획 PR. 구현 착수는 별도 승인

### 제안 6: 연결 검증은 Java 통합 테스트가 Core를 띄우고 Python CLI를 실행
- 내용: Gradle 작업이 venv와 의존성을 준비하고, 속성 `-PaiServiceIntegration=true`일 때만 테스트가 실행된다. 프로세스 60초·Tool 5초 제한, stdout/stderr 파일 수집, 실패 시 강제 종료, CI에 Python 설치 단계 추가. 대안: 사람이 수동 실행해 evidence에 붙임(보조).
- 보완 설명(8절): 환경 준비·시간 제한·출력 수집·종료 방법을 정했다. 속성이 없으면 건너뛰고 건너뜀을 보고서에 남긴다.
**판단** - [x] 채택(보완된 방향) / 결정자 사용자 / 검토 대상: 이 계획 PR. 구현 착수는 별도 승인

### 제안 7: 안전성 참조 자료의 책임 표시 변경 → (b) 채택
- 내용: TASK-014 안전성 파일 `prepayment-fee-safety-v1.json`의 `pass_criteria_owner: TASK-015`를 구조 검증(TASK-015)과 질문 기반·LLM 출력 검증(TASK-019)으로 나눈다. 비교한 방법: (a) 필드 추가·버전 유지, (b) `pass_criteria_owner`를 객체 `{structural: TASK-015, question_based: TASK-019}`로 바꾸고 schema·계약 테스트를 함께 고치며 안전성 파일을 v2로 올림, (c) 문서에만 기록.
- **판단: (b) 채택**(결정자 사용자, 검토 대상 이 계획 PR). 계획: 안전성 참조 자료는 **v2 파일 `prepayment-fee-safety-v2.json`**에서 구조 검증 책임(TASK-015)과 질문 기반·LLM 출력 검증 책임(TASK-019)을 객체로 명확히 구분한다. **기존 v1 파일과 TASK-014의 완료 기록은 보존**하고 TASK-014 문서에는 "후속 변경" 절로 v1 → v2 관계만 덧붙인다(기존 판단·완료 기록 수정 없음). 검색 질문·정답이 바뀌지 않으므로 **검색 골든셋(smoke·eval)의 버전은 v1 그대로** 둔다. 안전성 질문 3건(SAFE-01~03)과 참조 ID(S10·E22·E23)는 그대로 유지한다. schema는 `kind: safety`의 `pass_criteria_owner`를 객체로 받도록 바꾸되 v1 파일도 읽을 수 있게 문자열·객체 둘 다 허용하고, 계약 테스트는 v2에 두 책임이 모두 있는지와 v1·v2의 참조 ID가 같은지 확인한다. **실제 파일·schema·테스트 변경은 TASK-015 구현 단계에서 진행**하며 이 계획 PR에서는 바꾸지 않는다.

### 제안 8 (구현 중 세부 설계): 준비안 ID 해시 대상에서 `tool_response_hash`도 제외
- 내용: Tool 1 응답에는 평가 시각(`evaluatedAt`)이 들어 있어 응답 해시가 실행마다 달라진다. 이를 해시 대상에 두면 같은 입력·같은 Core 상태에서도 준비안 ID가 매번 바뀌어 "저장만 멱등"이 깨진다. 그래서 `preparation_id`·`run_id`·`consultation_id`·`sections[].evaluated_at`에 더해 `sections[].tool_response_hash`를 제외했다. `tool_response_hash`는 섹션 행에 그대로 저장돼 재현 정보로 남는다. 대안: Tool 응답에서 평가 시각을 뺀 해시를 쓰는 것(Core 응답 계약 변경이 필요해 보류).
- 영향: 기록 계약 설명, Core `contentHash()`, AI 서비스 `ids.py`, 테스트가 같은 규칙을 쓴다. ADR-012의 "세부 설계" 범위 안이다. 실행 시각과 Tool 응답 해시는 실행 기록 `section_evaluations`에 실행 단위로 보존한다.
**판단** - [x] 채택 / 결정자 사용자 / 2026-10-08 보완 보고 검토 뒤. 방향: 실행 시각과 Tool 응답 해시는 준비안 ID에서 제외하되 실행 기록에 보존하고, 업무 내용 변경은 ID에 반영한다

## Implementation Result / AI self-review / 인간 검수 / 결정 기록

### 변경 내용 (브랜치 `feat/task-015-consultation-preparation`, base `006c880`. 검증 대상 커밋 `87b553b` = 구현·자료·테스트, 문서는 후속 커밋)

- 매핑·계약: `datasets/synthetic/work/consultation-family-mapping.json`, `contracts/consultation-family-mapping.schema.json`, `contracts/consultation-preparation.schema.json`, `contracts/consultation-preparation-record.schema.json`.
- Core: `V10__create_consultation_preparation_schema.sql`(표 4, CHECK, 보호 목록 40표·trigger 80, 권한), `preparation` 패키지(`PreparationRecordProperties`, `PreparationRecordAuthenticationFilter`, `ConsultationPreparationController`/`Service`/`Repository`, `ConsultationPreparationException`/`ExceptionHandler`, `ConsultationFamilyMappingLoader`/`Configuration`), 설정(`application.yml` version 10·기록 토큰·매핑 적재 속성, `application-prod.yml`, `ProductionRequiredSettingsConfiguration`에 기록 토큰 필수). 기존 승인·일정·변경안·검증·Tool 코드는 변경 없음.
- AI 서비스: `apps/ai-service/ai_service/`(8 모듈), `requirements-ai.txt`, README.
- 연결 검증·CI: `build.gradle`(`prepareAiServiceEnv`, 속성·환경변수), `AiServicePreparationIntegrationTest`, `ci.yml`(gradle job에 Python 3.11과 `TRUST_AGENT_REQUIRE_AI_INTEGRATION=1`·`-PaiServiceIntegration=true`, python job에 `requirements-ai.txt`).
- 테스트: `ServiceTokenSeparationTest`(4, AC-20), Python HTTP 상태 사례(AC-21), `ConsultationPreparationIntegrationTest`(13), `ConsultationPreparationReadyIntegrationTest`(3, 전체 READY 별도 환경), `ConsultationPreparationHashTest`(2, 해시 대상), `AiServicePreparationIntegrationTest`(5), `PreparationScenario`, `tests/ai_service/`(30), 골든셋 계약 테스트 1건 추가. 기존 테스트 기대값 갱신 5파일(보완 5절 대조표).
- 안전성 v2: `prepayment-fee-safety-v2.json`, `search-goldenset.schema.json`(v1 문자열·v2 객체 허용), TASK-014 문서 "후속 변경" 절.
- 문서: `docs/evidence/CONSULTATION_PREPARATION_EVIDENCE.md`, 루트 README, Core README(기록 경로·매핑 적재·토큰), AI 서비스 README, 이 문서.

### 검증 결과 (로컬, 2026-10-08)

```text
./gradlew clean test bootJar --offline --no-daemon -PaiServiceIntegration=true
185 tests completed (기존 158 + 신규 27: 토큰 분리 4 + Core 통합 13 + 전체 READY 별도 환경 3 + 해시 단위 2 + 연결 검증 5), 통과 185, failures 0, errors 0, skipped 0. BUILD SUCCESSFUL (bootJar 포함). 보존: docs/evidence/task-015/test-results-full-with-ai-integration.md(저장소), gradle-full-with-ai-integration.log(로컬 보관)
python3 -m unittest discover -s tests -t .
Ran 106 tests (기존 75 + 신규 31: tests/ai_service 30 + 골든셋 계약 1), 통과 104, 실패 0, 건너뜀 2 (기존 사유: "비공개 snapshot artifact가 제공되지 않았습니다.")
```

로컬 기본 실행은 CLI를 실행하는 테스트(연결 검증 5건, 전체 READY CLI 1건)가 명시적 skip으로 보고서에 남고(전체 185건 중 통과 179, 실패 0, skip 6(연결 검증 5건과 전체 READY CLI 1건, 명시적 skip 목록은 docs/evidence/task-015/test-results-local-default.md), BUILD SUCCESSFUL), `TRUST_AGENT_REQUIRE_AI_INTEGRATION=1`에 venv가 없으면 `AI_INTEGRATION_ENV_MISSING`로 실패함을 1회 확인했다. 세부 사례 표는 evidence 문서에, 실행별 로그와 결과 요약은 `docs/evidence/task-015/`에 있다.

### 계획과 다른 점 (완료 기준 변경 없음)

- 제안 8(해시 대상에 `tool_response_hash` 추가 제외, 사용자 채택), AC-05 표현(`record.recorded`·`record.status`·`usage_notice`). 둘 다 "완료 기준 변경 이력"에 기록.
- 셀러론 HOLD 사유에 `APPROVED_CHECKLIST_NOTICE_MISMATCH`가 함께 온다(Core의 실제 응답, 계획은 `HUMAN_REVIEW_PENDING`만 예시). 사유를 고르거나 숨기지 않고 모두 전달·저장한다.
- 계획의 "C1~C7"·"매핑 없는 상품 400" 표기는 구현에서 C1~C5·M1~M5(ADR-012 확정 목록)와 CLI 입력 오류(종료 2)·HTTP 진입점 400에 해당한다.

### 남은 한계

- 409 `PREPARATION_CONFLICT`는 코드상 정상 경로로 만들 수 없는 방어선이라 테스트를 만들지 않았다(보완 4절). 직렬화 재시도는 40001 주입으로 검증했고 실제 동시 철회와의 충돌은 비결정적이라 테스트로 만들지 않았다.
- 필수 CI 실제 실행(AC-14)은 PR #40 2차 CI에서 확인했다(2차 보완 3절).
- 안전성은 구조 검증까지다. 질문 기반·LLM 출력 검증은 TASK-019.
- 매핑은 합성 시나리오 1건이고 적재는 기동 시 설정으로만 한다. 행원 메모·검색·화면·사용자별 인증은 범위 밖이다.

### 사용자 확인 요청 5건에 대한 보완 (2026-10-08, 완료 승인 전)

#### 1. 전부 준비된 경우(전체 READY) 검증

- 별도 테스트 환경 `ConsultationPreparationReadyIntegrationTest`(새 Spring 컨텍스트와 DB): TASK-014 고정 조건을 적재한 뒤 셀러론 v2 변경안을 2026-10-05T05:00Z에 승인한다. 셀러론 검증은 24시간 공개 근거 정책에서는 FAIL(근거 미확인)이지만 Core 기본 설정과 이 테스트의 30일 정책에서는 공개 값 20억 일치로 PASS여서 승인할 수 있다. 기존 PARTIAL·HOLD 사례의 고정 조건(셀러론 미승인)은 바꾸지 않았다.
- 확인한 것 3건: (a) 두 섹션 모두 READY인 기록 요청 → 201, `status=READY`, `preparation_complete=true`, 필수 보류 0, 섹션 2행 READY, 셀러론 섹션의 결정 ID가 승인 결정과 일치, 재확인 usable 2건. (b) 같은 내용을 PARTIAL로 낮춰 주장하면 422 `PREPARATION_STATUS_INVALID`(READY도 Core가 계산한 상태와 같아야 한다). (c) CLI 실행 → `status=READY`, 머리 문구 "모두 준비됐습니다"와 "상담이나 대출 결정의 완료가 아닙니다", "끝나지 않았습니다" 없음, `remaining_checks` 0, 셀러론 항목 포함, `record.status=RECORDED`·`recorded=true`, DB에 READY 행.
- AC-06은 기준 그대로 통과로 바꿨다.

#### 2. 준비안 ID 계산에 들어가는 값과 빠지는 값

| 구분 | 값 | 이유 |
|---|---|---|
| 들어감 | `assembler_version`, `messages_hash`, `family_mapping_hash` | 조립 규칙·문구 표·매핑이 바뀌면 다른 준비안 |
| 들어감 | `application.application_id`·`company_id`·`product_key`·`source_hash`, `business_date` | 다른 신청 건·다른 업무일은 다른 준비안 |
| 들어감 | `status`, `preparation_complete` | 준비 정도가 다르면 다른 준비안 |
| 들어감 | `sections[].family_id`·`required`·`status`·`hold_kind`·`hold_claim_basis`·`selected_notice_id`·`approved_checklist_version_id`·`decision_id`·`item_rule_version_ids`(순서 포함)·`item_evidence_hashes`(순서 포함)·`blocking_reasons` | checklist version·결정·항목·근거·선택 공문·보류 사유가 바뀌면 다른 준비안 |
| 빠짐 | `preparation_id` | 계산 결과 자체 |
| 빠짐 | `run_id` | 실행마다 새 값(실행 기록 키) |
| 빠짐 | `consultation_id` | 추적용 입력. 같은 업무 내용을 다른 상담에서 실행해도 같은 준비안 |
| 빠짐 | `sections[].evaluated_at` | Core가 평가한 시각. 실행마다 달라짐 |
| 빠짐 | `sections[].tool_response_hash` | Tool 1 응답 전체의 해시. 응답에 평가 시각이 있어 실행마다 달라짐(제안 8) |

- 검증: Core 단위 `ConsultationPreparationHashTest` 2건(빠지는 값 7가지를 바꿔도 같은 ID, 들어가는 값 18가지를 바꾸면 다른 ID), Python `test_preparation_id_ignores_run_and_time_but_tracks_section_state`·`test_hash_subject_excludes_run_consultation_and_evaluated_at`, Core 통합 `sameContentIsRecordedOnceAndRerunStillRechecksCurrentState`(평가 시각과 Tool 응답 해시를 바꿔 보내도 같은 ID로 200 ALREADY_RECORDED), 연결 검증 `rerunRechecksCoreAndRecordsOnlyOnce`(시계를 60초 뒤로 옮겨 재실행: 같은 ID, 평가 시각 03:01:00Z, 다른 Tool 응답 해시).
- 실행마다 달라지는 값의 추적(덮어쓰기 없음): 준비안 행과 섹션 행은 첫 기록 그대로 둔다(append-only, UPDATE·DELETE는 권한으로 막혀 있음). 실행 기록 `consultation_preparation_run`에 V10 열 `section_evaluations`(jsonb 배열)를 두어 실행마다 섹션별 `{family_id, status, evaluated_at, tool_response_hash, recheck_usable, recheck_reasons}`를 남긴다. RECORDED·ALREADY_RECORDED는 물론 본문 해석 뒤 거부된 REJECTED·FAILED에도 남는다(해석 전 거부는 빈 배열). 재실행이 10번이면 준비안 1행, 실행 기록 10행이고 각 실행의 평가 시각·응답 해시·재확인 결과를 `run_id`로 찾는다. 통합 테스트가 첫 기록 섹션 행의 값(03:00:00Z, 해시 1…)이 그대로이고 재실행의 값(03:05:00Z, 해시 5…)은 실행 기록에만 있음을 확인한다.

```sql
select r.run_id, r.outcome, r.started_at, e->>'family_id' as family, e->>'evaluated_at' as evaluated_at,
       e->>'tool_response_hash' as tool_response_hash, e->>'recheck_usable' as recheck_usable
from consultation_preparation_run r, jsonb_array_elements(r.section_evaluations) e
where r.preparation_id = :preparation_id order by r.started_at;
```

#### 3. 기록 실패 표현 대조표 (`recorded=false` ↔ 구현)

| `record.status` | `record.recorded` | Core 응답 / 원인 | `record.error_code` | CLI 종료 코드 | `usage_notice` 요지 | 테스트 |
|---|---|---|---|---|---|---|
| RECORDED | true | 201 | 없음 | 0 | 기록됨. 기록은 사용 허가가 아니므로 사용 전 재확인 | Python `test_partial_preparation_…`, `test_recorded_preparation_exits_zero_…`, 연결 검증 1·3·5건 |
| ALREADY_RECORDED | true | 200 | 없음 | 0 | 같은 내용이 이미 기록됨. 사용 전 재확인 | 연결 검증 `rerunRechecksCoreAndRecordsOnlyOnce` |
| REJECTED | false | 400·401·409·422 | Core 오류 코드 그대로(예: PREPARATION_NOT_USABLE, UNAUTHENTICATED) | 3, stderr "기록되지 않은 준비안입니다. 사용하지 말고 다시 실행하세요" | "Core 저장이 거부됐습니다. 이 준비안은 기록되지 않았으므로 사용하지 말고 다시 실행하세요." | Python `test_record_outcomes_map_to_status_and_usage_notice`, `test_rejected_record_exits_three_with_preparation_and_warning`, 연결 검증 `wrongRecordTokenLeavesPreparationUnrecorded` |
| FAILED | false | 5xx·연결 실패·시간 초과 | CORE_UNAVAILABLE / CORE_TIMEOUT | 3 | "Core 저장에 실패했습니다. … 사용하지 말고 다시 실행하세요." | 위와 같음 |
| NOT_ATTEMPTED | false | 기록 토큰 미설정(호출 없음) | RECORD_TOKEN_MISSING | 3 | "기록 토큰이 없어 … 사용하지 말고 설정을 점검한 뒤 다시 실행하세요." | `test_missing_record_token_skips_recording_without_calling_core`, `test_missing_record_token_exits_three` |

- 계약(`contracts/consultation-preparation.schema.json`): `record.recorded`(boolean) 필수. `status`가 RECORDED·ALREADY_RECORDED일 때만 `true`이고(schema `allOf`), 아니면 `false`에 `error_code`·`error_message`가 필수다. HTTP 진입점은 같은 값을 헤더 `X-Preparation-Recorded`로도 준다. 출력 예시(기록 거부): `"record": {"recorded": false, "status": "REJECTED", "error_code": "UNAUTHENTICATED", "error_message": "…"}`, `usage_notice`에 사용 금지와 재실행 안내, 종료 코드 3.
- 성공으로 오해할 수 없는 이유: 종료 코드 0은 `recorded=true`일 때만 나오고, 준비안 본문에도 `recorded=false`와 사용 금지 문구가 함께 있으며, Core에는 준비안 행이 없고 실행 기록만 REJECTED/FAILED로 남는다.
- 완료 조건 표현 변경은 "완료 기준 변경 이력" (2)에 기록했다.

#### 4. 아직 검증하지 않은 경로

| 경로 | 테스트가 확인한 것 | 확인하지 못한 것 | 판단 |
|---|---|---|---|
| 409 `PREPARATION_CONFLICT`(같은 ID, 다른 저장 내용) | 같은 ID·다른 내용 요청은 ID가 본문 해시와 달라 400 `PREPARATION_ID_MISMATCH`로 먼저 거부됨 | 409 자체는 발생시키지 못함 | 코드상 만들 수 없다. 저장되는 `content_hash`는 ID와 같은 함수의 값이고 ID는 저장 전에 그 값과 대조된다. 같은 ID에 다른 `content_hash`가 있으려면 해시 함수가 바뀌거나 sha256이 충돌해야 한다. 방어선으로 두고 억지 테스트는 만들지 않았다 |
| 동시 같은 ID 요청 | 2개 스레드 동시 요청 → 201 하나, 200 하나, 준비안 1행, 실행 기록 2행(`concurrentSameIdRecordsOnce`). PK 충돌(23505) 해결 경로는 저장소 대역으로 시간에 의존하지 않고 재현해, 충돌 뒤 새 트랜잭션에서 매핑 확인·READY 재확인을 다시 하고 나서야 ALREADY_RECORDED를 주며 그 사이 매핑이 바뀌면 `MAPPING_MISMATCH`, 사용 불가면 `PREPARATION_NOT_USABLE`로 거부함(`duplicateKeyConflictPathRechecksBeforeReturningExistingRecord`, 2차 보완 1절) | 2개 초과 동시성, 네트워크 지연 조합 | 지는 쪽은 23505면 새 SERIALIZABLE 트랜잭션에서 재확인부터 다시 한 뒤 기존 행과 비교해 ALREADY_RECORDED, 40001이면 재확인부터 재시도해 기존 행을 보고 ALREADY_RECORDED가 된다. 두 경로 모두 취소되지 않은 트랜잭션의 재확인을 거친 뒤라 확인 없는 성공 응답은 없다 |
| 직렬화 재시도 | commit 단계에 40001 주입: 1회 실패 뒤 재확인부터 다시 실행해 RECORDED, 2회 실패면 500 `SERIALIZATION_FAILED`·준비안 행 없음·FAILED 실행 기록 1건(`serializationFailureIsRetriedOnceAndSecondFailureIsRecordedAsFailed`) | 실제 동시 철회·승인과 충돌해 PostgreSQL이 40001을 내는 순간 | 타이밍에 달려 결정적으로 재현할 수 없어 테스트를 만들지 않았다. 재확인과 저장이 한 SERIALIZABLE 트랜잭션이므로 철회가 먼저 commit되면 재확인이 거부하거나 40001로 재시도된 뒤 거부된다. 중복 저장은 PK로, 잘못된 성공은 재확인으로 막힌다 |

#### 5. 기존 테스트 변경 대조표

| 바꾼 기대값 | 이전 → 이후 | 실제 추가 내용 | 삭제·완화 |
|---|---|---|---|
| schema version(`CoreApplicationIntegrationTest` 3곳, `DemoFeatureProductionGuardTest`, `RuntimeDatasourcePropertiesTest` 3곳, `PublicProductSchemaIntegrationTest`) | 9 → 10 | V10 migration 1개 추가(표 4) | 없음. version 불일치 거부 검사는 그대로(999로 바꾸면 거부) |
| `PublicProductSchemaIntegrationTest` migrationsExecuted | 9 → 10 | 위와 같음 | 없음 |
| 보호 표 수 / trigger 수 | 36 → 40 / 72 → 80 | 표 4개 × trigger 2개(UPDATE·DELETE 거부) | 없음. 보호 목록 함수와 실제 trigger 일치 검사 그대로 |
| production 필수 설정 목록(`DemoFeatureProductionGuardTest`) | 기록 토큰 추가 | `ProductionRequiredSettingsConfiguration`에 `TRUST_AGENT_PREPARATION_RECORD_TOKEN` 필수 추가 | 없음. 누락 시 기동 거부 검사는 그대로이고 항목이 늘었다 |
| `RuntimeDatasourcePropertiesTest` 3곳의 "모든 필수 설정 제공" 값 | 기록 토큰 값 추가 | 위와 같음(테스트는 필수 설정을 모두 준 상태에서 다른 조건을 검사) | 없음 |

`git diff -- apps/core-service/src/test apps/core-service/src/main/java/com/trustagent/core/config/ProductionRequiredSettingsConfiguration.java`로 대조했고 기존 실패 검사나 보호 조건을 지우거나 느슨하게 한 줄은 없다.

### 2차 보완 (2026-10-08, 커밋 전 확인 요청 2건)

#### 1. 동시 저장 충돌 뒤 기존 기록 반환 경로의 재확인

- 문제: 저장 시도가 PK 충돌(23505)로 끝나면 그 시도의 재확인은 취소된 트랜잭션의 것이다. 기존 구현은 충돌 뒤 기존 행의 내용 해시만 비교해 ALREADY_RECORDED를 돌려줬다.
- 변경: `resolveConcurrentInsert`가 새 SERIALIZABLE 트랜잭션에서 `doRecord`를 다시 실행한다. 즉 매핑 확인(M1~M5) → HOLD 재조회 → READY 재확인(C1~C5) → 기존 행 비교 순서를 그대로 거친 뒤에만 ALREADY_RECORDED를 준다. 재확인 결과는 이 실행의 실행 기록 `section_evaluations`에 남는다. "같은 ID 재요청은 기존 기록 유무와 관계없이 재확인한다"는 계획이 충돌 경로에도 적용된다.
- 재시도 횟수·범위: 늘리지 않았다. 40001 재시도는 1회 그대로이고, 충돌 해결은 한 번만 시도하며 그 안에서 다시 23505가 나면 `RECORD_WRITE_FAILED`, 40001이 나면 `SERIALIZATION_FAILED`로 FAILED 실행 기록 1건을 남기고 끝낸다. 영향은 충돌이 난 요청에 한해 재확인 조회가 한 번 더 실행되는 것뿐이다.
- 테스트(시간에 의존하지 않음): 저장소 대역 `BlindFirstLookupRepository`가 첫 "기존 기록 조회"만 없음으로 답해 저장을 시도하게 만든다. 행은 이미 있으므로 PostgreSQL이 실제 23505를 낸다. (a) 충돌 뒤 매핑 조회·기존 기록 조회가 각각 2회(새 트랜잭션에서 다시 함), ALREADY_RECORDED, 실행 기록에 재확인 결과 2건. (b) 충돌 해결 트랜잭션에서 매핑 해시가 달라져 있으면 기존 행이 있어도 409 `MAPPING_MISMATCH`로 거부. (c) 충돌 해결 트랜잭션 직전에 시계를 승인 전으로 돌리면 422 `PREPARATION_NOT_USABLE`로 거부. 서비스에 저장소를 주입하는 패키지 전용 생성자를 추가했고 운영 생성자는 그대로다.

#### 2. 검증 결과 보존

Gradle은 `build/test-results`를 실행마다 덮어쓰므로 실행별 로그와 결과 요약을 `docs/evidence/task-015/`에 따로 보존했다. 각 파일은 evidence 문서 "보존한 실행 기록" 절에서 연결한다. 원본 `.log`는 `.gitignore`(`*.log`)로 Git에 올리지 않는 로컬 보관 자료이고, 저장소에는 `.md` 결과 요약·`RUN_SUMMARY.txt`·CLI 출력 표본을 올렸다. 검증 대상 커밋은 `87b553b`(구현·자료·테스트)이며 문서 커밋과 CI 수정 커밋 `63fee24`는 그 뒤에 따로 올렸다. CI 실행 링크는 2차 보완 3절과 PR #40 본문에 있다.

| 실행 | 명령 | 보존 파일 |
|---|---|---|
| 연결 검증 포함 전체 | `./gradlew clean test bootJar --offline --no-daemon -PaiServiceIntegration=true` | `gradle-full-with-ai-integration.log`(로컬 보관), `test-results-full-with-ai-integration.md`, CLI 출력 표본 3개(PARTIAL·READY·REJECTED) |
| 로컬 기본(속성 없음) | `./gradlew clean test --offline --no-daemon` | `gradle-local-default.log`(로컬 보관), `test-results-local-default.md`(CLI 실행 테스트 skip 목록) |
| 의도적 환경 누락 | `TRUST_AGENT_REQUIRE_AI_INTEGRATION=1 … -PaiServiceIntegration=false` | `gradle-env-missing-failure.log`(로컬 보관), `test-results-env-missing.md` |
| Python | `python3 -m unittest discover -s tests -t . -v` | `python-unittest.log`(로컬 보관), `RUN_SUMMARY.txt` |

결과(4차 보완 뒤 다시 실행해 보존 파일을 갱신): 연결 검증 포함 전체 185건 통과·실패 0·skip 0, 로컬 기본 185건 중 통과 179·실패 0·skip 6, 의도적 환경 누락 1건 실패(`AI_INTEGRATION_ENV_MISSING`), Python 106건 중 통과 104·실패 0·skip 2(기존 비공개 snapshot 사유). 합계는 `docs/evidence/task-015/RUN_SUMMARY.txt`.

#### 3. 필수 CI 실행 결과 (PR #40)

- 1차 실행(https://github.com/hj1016/trust-agent/actions/runs/37659077359): `Gradle tests` 성공, `Python contracts` **실패**. 원인은 CI가 `unittest discover -s tests`를 top-level 지정 없이 실행해 `tests/ai_service`가 패키지 `ai_service`를 가리고 `ai_service.config`를 찾지 못한 것(import 오류 3건). 로컬에서 같은 명령으로 재현했다.
- 수정 커밋 `63fee24`(`.github/workflows/ci.yml`, `apps/core-service/build.gradle`만): discover 명령을 README와 같은 `-s tests -t .`로 고치고, Gradle 로그에 preparation 패키지 테스트의 통과·skip·실패와 전체 합계를 출력하게 했다. 테스트나 완료 기준은 바꾸지 않았다.
- 2차 실행(https://github.com/hj1016/trust-agent/actions/runs/37659799630): 두 job 모두 성공. [Gradle tests](https://github.com/hj1016/trust-agent/actions/runs/37659799630/job/112924094547) 로그에 `TEST SUMMARY: 181 tests, 181 passed, 0 failed, 0 skipped`와 연결 검증 `AiServicePreparationIntegrationTest` 5건·전체 READY CLI 1건의 `TEST SUCCESS`가 있고 `TEST SKIPPED`는 0건이다(필수 CI에서 Core와 Python CLI 연결 테스트가 실제 실행됨). [Python contracts](https://github.com/hj1016/trust-agent/actions/runs/37659799630/job/112924094871) 로그는 `Ran 106 tests`, `OK (skipped=2)`이며 skip 2건은 기존 비공개 snapshot artifact 사유로 TASK-015와 무관하다.

### 3차 보완: 검수 자료 (완료 승인 전, 읽기 전용 검토. 작성 당시 미커밋, 이후 PR #40에 포함)

#### 1. HOLD 출력 표본 (필수 공문군 모두 보류, 실제 CLI 출력)

연결 검증 `AiServicePreparationIntegrationTest`를 다시 실행해 얻은 실제 출력이다. 두 표본 모두 `status=HOLD`, `preparation_complete=false`, 두 섹션 모두 `items=[]`·`approved_checklist=null`, `remaining_checks` 2건, 머리 문구 "준비된 필수 공문군이 없습니다 … 상담 준비가 끝나지 않았습니다", 섹션별 `hold_message`·`manual_checklist_notice`, `record.status=RECORDED`·`recorded=true`(HOLD도 기록됨, 기록은 사용 허가가 아님)다. 입력 토큰 값은 출력에 없다.

| 표본 | 입력(명령) | Core 평가 시각(테스트 시계) | 업무일 | 보류 사유(섹션별) | 파일 |
|---|---|---|---|---|---|
| 승인 전 시각(4차 보완으로 교체) | `--application SW-APPLICATION-001 --business-date 2026-10-05 --consultation-id e2e-3` | 2026-10-05T04:00Z (중도상환수수료 승인 04:30Z 전) | 2026-10-05 (평가 당일) | 두 섹션 모두 `APPROVED_CHECKLIST_NOTICE_MISMATCH`, `HUMAN_REVIEW_PENDING` (CORE_DECISION / CORE_REPORTED). `FUTURE_BUSINESS_DATE` 없음 | `docs/evidence/task-015/cli-output-hold-before-approval-sample.json` |
| 철회 후 재실행 | `--business-date 2026-10-07 --consultation-id e2e-5` (철회 사건 2026-10-07T00:00Z 삽입 뒤) | 2026-10-07T01:00Z | 2026-10-07 (평가 시각 당일) | 중도상환수수료 `EFFECTIVE_NOTICE_WITHDRAWN`(선택 공문 없음), 셀러론 `APPROVED_CHECKLIST_NOTICE_MISMATCH`, `VALIDATION_STALE`(검증 24시간 경과) | `docs/evidence/task-015/cli-output-hold-after-withdrawal-sample.json` |

- 구분(4차 보완에서 정리): 3차 보완 당시 "승인 전" 표본은 업무일이 평가 다음 날(2026-10-06)이라 `FUTURE_BUSINESS_DATE`가 섞여 있었다. 사용자 지시로 `stateChangeProducesNewHoldPreparation`의 업무일을 평가 당일(2026-10-05)로 맞추고, 모든 섹션에 `FUTURE_BUSINESS_DATE`가 없고 `HUMAN_REVIEW_PENDING`이 있으며 항목·승인 checklist가 없고 `hold_message`에 "검토 대기"가 있음을 단언에 더했다. 표본을 교체했고 혼합 표본 파일은 지웠다. 철회 후 표본은 그대로다. 두 표본 모두 **과거 업무일 조회**(오늘보다 이른 업무일)도, **과거 시각 조회**(`knownAt`)도 아니다. Tool API에는 `knownAt`이 없고 조회는 항상 Core의 현재 시각 기준이며, 테스트는 그 현재 시각 자체를 옮긴 것이다.
- 순수한 과거 업무일 HOLD 사례는 Core 통합 `tamperedReadyClaimsAreRejectedWithoutRows`(업무일 2026-09-30, 예시 checklist 기간 → 422 `PREPARATION_NOT_USABLE`)와 Python 단위 `test_all_required_hold_gives_hold_without_guessed_items`가 다루며 CLI 표본은 없다.

#### 2. 핵심 코드 검토 (AI 검토 결과. 사용자 검수나 독립 리뷰를 대신하지 않으며 그 입력 자료다)

| 항목 | 코드 위치 | 보장하는 테스트 | 남은 한계 |
|---|---|---|---|
| 읽기·기록 토큰이 서로의 경로에 접근 불가 | `ToolAuthenticationFilter`(`/api/v1/tools/` 접두, Tool 토큰과 상수 시간 비교, 실패 401), `PreparationRecordAuthenticationFilter`(`/api/v1/consultation-preparations` 정확 경로, 기록 토큰 비교, 실패 401, 저장·실행 기록 없음), 두 필터는 각자 설정의 토큰만 읽고 통과 시 서비스 ID·scope 속성을 요청에 붙임. `ConsultationPreparationController`는 그 속성만 쓰고 본문의 actor는 허용 필드 밖(400). DB `token_scope = 'record'` CHECK | `recordAndToolTokensAreNotInterchangeable`(토큰 없음·Tool 토큰으로 기록 401, 기록 토큰으로 Tool 401, 실행 기록 없음), `idHashRunIdAndSchemaRulesAreEnforced`(모르는 필드 400), Python `test_record_request_uses_record_token_and_tool_requests_use_tool_token`, `DemoFeatureProductionGuardTest`(production 필수) | 두 토큰은 test/demo 수준의 고정 문자열이고 사용자별 인증·권한·회전·사용 제한이 없다. 두 토큰이 같은 값이면 구분이 사라지는 문제는 4차 보완의 `ServiceTokenSeparationConfiguration`(모든 profile에서 기동 거부, AC-20)으로 막았다 |
| 필수 공문군 누락·변경과 거짓 READY 주장 거부 | `ConsultationPreparationService.verifyMapping`(활성 매핑 해시 대조 `MAPPING_MISMATCH`, 매핑 밖 섹션 `SECTION_NOT_IN_MAPPING`, `required` 대조 `REQUIRED_FLAG_MISMATCH`, 필수 섹션 유무 `REQUIRED_SECTION_MISSING`, 필수 없는 매핑의 READY `NO_REQUIRED_FAMILY`, `computeStatus`로 상태·`preparation_complete` 재계산 `PREPARATION_STATUS_INVALID`), `parse`(READY 섹션은 version·결정·항목·근거 필수), `recheckReady`(C1~C5) | `mappingAndStatusClaimsAreRejected`(6사례), `mappingWithoutRequiredFamilyNeverAllowsReady`, `readyClaimMustMatchComputedStatus`(READY를 PARTIAL로 낮춘 주장도 422), `tamperedReadyClaimsAreRejectedWithoutRows`, `withdrawalKnownAfterRecordingRejectsTheSameClaim`, Python `test_mapping_without_required_family_never_becomes_ready` | 매핑은 Core에 적재된 파일 해시로만 확인하며 매핑 파일 자체의 승인 여부를 Core가 검증하지는 않는다(적재는 설정으로만, 변경은 Task 기록). 선택 공문군의 READY 주장도 C1~C5로 재확인하지만 선택 공문군은 전체 상태에 영향을 주지 않는다 |
| 같은 ID 재요청과 동시 저장 충돌 뒤 현재 상태 재확인 | `doRecord`: 매핑 확인 → 모든 섹션 재확인 → **그 뒤에** `findPreparation`으로 기존 행 비교(ALREADY_RECORDED). `executeWithRetry`: 40001은 재확인부터 1회 재시도. `resolveConcurrentInsert`: 23505 뒤 새 SERIALIZABLE 트랜잭션에서 `doRecord` 재실행 | `sameContentIsRecordedOnceAndRerunStillRechecksCurrentState`(승인 전 시각 재요청 422), `duplicateKeyConflictPathRechecksBeforeReturningExistingRecord`(실제 23505, 조회 2회, 매핑 변경 409, 사용 불가 422), `concurrentSameIdRecordsOnce`, `serializationFailureIsRetriedOnceAndSecondFailureIsRecordedAsFailed`, 연결 검증 `rerunRechecksCoreAndRecordsOnlyOnce`(Tool 재호출) | 실제 동시 철회와의 40001 충돌 타이밍은 테스트로 재현하지 않았다. HOLD 섹션의 재조회 결과는 저장만 하고 거부 조건은 아니다(설계대로: AI 보고와 Core 관찰을 둘 다 남김) |
| 기록 실패 시 사용 금지와 실패 종료의 일관성 | Python `assembler._record`(`recorded`는 RECORDED·ALREADY_RECORDED일 때만 true), `messages.USAGE_NOTICES`(REJECTED·FAILED·NOT_ATTEMPTED 모두 "사용하지 말고 다시 실행"), `cli.main`(`recorded`가 거짓이면 stderr 경고와 종료 3, 참일 때만 0), `app.create_preparation`(헤더 `X-Preparation-Recorded`), 계약 `record` `allOf`(status와 `recorded` 일치, 실패면 `error_code`·`error_message` 필수) | Python `test_record_outcomes_map_to_status_and_usage_notice`(6사례), `test_missing_record_token_*` 2건, `test_rejected_record_exits_three_with_preparation_and_warning`, `test_http_entry_point_returns_preparation_and_maps_errors`(헤더), 연결 검증 `wrongRecordTokenLeavesPreparationUnrecorded` | 기록 실패여도 준비안 본문은 출력된다(설계: 보류 안내와 함께 보여 주되 사용 금지). HTTP 진입점은 실패도 200으로 답하고 본문·헤더로 구분하므로 호출자가 헤더나 `recorded`를 읽어야 한다. 자동 재시도는 없다 |
| 기존 업무 상태를 기록 경로가 바꾸지 않음 | `ConsultationPreparationRepository`의 쓰기 문장은 `consultation_preparation`·`_section`·`_run` INSERT 3개뿐(UPDATE·DELETE 없음). 재확인은 `InternalPolicyApplicableService.get` 읽기만. V10 GRANT: runtime 역할은 네 표에 SELECT·INSERT만, append-only trigger로 UPDATE·DELETE 거부. 매핑 표는 별도 적재 경로만 INSERT | `businessTablesAreUntouchedAndRecordTablesAreAppendOnly`(승인·일정·변경안·검증·공문 사건·매핑 표 행 수 불변, 42501, 권한, 보호 목록 4표, CHECK 23514), `PublicProductSchemaIntegrationTest`(보호 표 40·trigger 80) | 행 수 불변 검사는 이 테스트 클래스의 요청 집합에 대한 것이고 `tool_call_audit`는 재확인이 아닌 Tool 호출로 늘어난다(재확인은 Tool 경로가 아니라 서비스 직접 호출이라 감사 행을 만들지 않음) |
| 실패·재시도·중복 요청의 실행 기록 누락·중복 없음 | `record`: `run_id` 형식 검사 → `runExists`면 `RUN_ID_CONFLICT`(새 실행 기록 없음, 기존 1건 유지) → 본문 해석·저장 → 성공·거부·실패 모든 분기에서 `recordRun` 정확히 1회(REQUIRES_NEW, 실패 시 `FAILURE_AUDIT_WRITE_FAILED`). 재시도·충돌 해결은 같은 요청 안에서 끝나므로 실행 기록은 요청당 1건이고 `section_evaluations`는 마지막 시도의 재확인 결과 | `idHashRunIdAndSchemaRulesAreEnforced`(RUN_ID_CONFLICT와 형식 오류에서 실행 기록 수 불변), `serializationFailureIsRetriedOnceAndSecondFailureIsRecordedAsFailed`(실패 1건), `duplicateKeyConflictPathRechecksBeforeReturningExistingRecord`, `concurrentSameIdRecordsOnce`(실행 기록 2건), `assertRejected` 공통 검사(거부마다 REJECTED 1건), `recordAndToolTokensAreNotInterchangeable`(인증 실패는 실행 기록 없음) | 인증 실패(401)와 `run_id` 형식 오류·재전송은 설계상 실행 기록을 남기지 않는다(인증 전이라 서비스 ID를 신뢰할 수 없음, 재전송은 기존 기록이 그 자리). 실행 기록 저장 자체가 실패하면 500이며 그 요청의 기록은 없다 |
| HOLD에 미승인 항목·근거가 남지 않음 | Python `assembler._build_section`(usable=false면 항목 없이 HOLD, 근거 1건이라도 실패하면 섹션 전체 HOLD, 부분 제공 없음), `_section_output`·`_record_body`(HOLD는 `items=[]`, version·결정 null). Core `parse` HOLD 공란 검사(`HOLD_SECTION_INVALID`: 항목·근거·version·결정 금지, 사유 코드 허용 목록), `recheckHold`(저장 행에 빈 목록 고정), V10 섹션 CHECK(HOLD는 version·결정 null, 항목·근거 길이 0, 사유 1개 이상) | Python `test_all_required_hold_gives_hold_without_guessed_items`, `test_single_evidence_failure_holds_whole_section`, `test_unverified_holds_…`, Core `holdSectionsAreCheckedAndStoredWithClaimBasis`(항목 있는 HOLD 400), `businessTablesAreUntouchedAndRecordTablesAreAppendOnly`(CHECK 23514), 연결 검증 PARTIAL·HOLD 사례(`items` 0) | HOLD 섹션의 `selected_notice`(공문 ID·제목·시행일)와 사유 코드는 남는다(근거 원문·항목은 아님). `warning_reasons`는 출력에만 있고 기록에는 없다 |

#### 3. 사용자 검수용 설명 (쉬운 말)

- **READY(기준 자료 준비 완료)**: 모든 필수 공문군에 "지금 사용 가능한 승인 checklist"가 있어 항목과 근거(공문 원문 문장과 위치)가 모두 보입니다. 머리 문구는 "기준 자료 준비 완료"이고 직원 확인 안내가 함께 나옵니다. **고객별 적용 조건과 제출서류 확인은 직원 몫이며, 상담이 끝났다거나 대출이 결정됐다는 뜻이 아닙니다.** 고객 니즈 확인과 상품 선택은 이 준비안의 앞 단계입니다. 승인·거절·한도·금리는 담당자가 판단합니다. 기록됐다고 해서 사용 허가가 난 것은 아니므로, 실제 상담에서 쓰기 전에는 Core 조회로 지금도 사용 가능한지 다시 확인해야 합니다.
- **PARTIAL**: 일부 필수 공문군만 준비됐습니다. 준비된 섹션의 항목·근거는 보이고, 보류된 섹션은 사유와 수기 확인 안내만 보입니다. 머리 문구에 "끝나지 않았습니다"와 남은 공문군이 적힙니다. 보류된 공문군을 추측으로 채우거나 준비 완료로 보면 안 됩니다. 남은 공문군은 Core 조회나 수기 checklist로 확인합니다.
- **HOLD**: 준비된 필수 공문군이 하나도 없습니다. 항목과 근거는 전혀 없고 사유(예: 검토 대기, 공문 철회, 미래 업무일)와 안내만 있습니다. HOLD도 기록되지만 이 기록은 "이 시점에 준비할 수 없었다"는 사실의 기록일 뿐입니다. 이 출력으로 상담 준비를 했다고 하면 안 됩니다.
- **기록 실패(거부·실패·미시도)**: 준비안 본문은 보여도 `record.recorded=false`, 사용 금지 안내, 종료 코드 3이 함께 나옵니다. **이 준비안은 사용하지 말고** 설정(토큰 등)을 점검한 뒤 다시 실행합니다. Core에는 준비안이 없고 실행 기록만 거부·실패로 남습니다.
- **재실행**: 같은 신청·업무일로 다시 실행하면 Core를 다시 조회합니다. 상태가 그대로면 같은 준비안 ID로 "이미 기록됨"이 나오고(기존 기록을 덮어쓰지 않음), 이번 실행의 평가 시각·Tool 응답 해시·재확인 결과는 실행 기록에 따로 남습니다. 상태가 바뀌었으면 새 ID의 준비안이 새로 기록됩니다. "이미 기록됨"이 나와도 사용 전 재확인은 그대로 필요합니다.
- **철회**: 공문이 철회되면 그 공문군은 HOLD(`EFFECTIVE_NOTICE_WITHDRAWN`)가 되고 항목·근거가 사라집니다. 철회 전에 기록된 준비안은 그대로 남아 있지만, 같은 내용을 다시 기록하려 하면 Core가 거부합니다. 철회 전 기록을 꺼내 쓰면 안 됩니다.
- **아직 없는 것**: 근거 검색(TASK-016), LLM 문장 생성과 그 검증(TASK-019), 화면과 행원 최종 확인(TASK-017), 실제 사용자별 인증·권한, 행원 메모 입력, 실제 고객·신청 자료. 지금 자료는 모두 합성 시나리오이며 토큰 두 개는 test/demo 수준입니다.

### 4차 보완 (2026-10-08, 사용자 확인 항목 3건. 작성 당시 미커밋, 이후 커밋 `2e330d2`·`427a950`으로 PR #40에 포함)

#### 1. 승인 전 HOLD 사례 정리

- 변경: `AiServicePreparationIntegrationTest.stateChangeProducesNewHoldPreparation`의 업무일을 평가 당일 2026-10-05로 맞췄다(평가 시각 2026-10-05T04:00Z, 승인 04:30Z 전). 단언 추가: `status=HOLD`, `preparation_complete=false`, 업무일 2026-10-05, 두 섹션 모두 CORE_DECISION/CORE_REPORTED, 평가 시각 04:00Z, `FUTURE_BUSINESS_DATE` 없음, `HUMAN_REVIEW_PENDING` 있음, 항목 0·승인 checklist 없음, `hold_message`에 "검토 대기", `record.recorded=true`. 철회 후 사례(`withdrawalAfterRecordingProducesNewHoldPreparation`, 업무일 2026-10-07)는 그대로다.
- 표본: `docs/evidence/task-015/cli-output-hold-before-approval-sample.json`으로 교체(두 섹션 사유 `APPROVED_CHECKLIST_NOTICE_MISMATCH`, `HUMAN_REVIEW_PENDING`만). 혼합 표본 파일은 지웠다.

#### 2. 읽기·기록 토큰 같은 값 기동 거부 (AC-20 추가)

- 변경: `apps/core-service/.../config/ServiceTokenSeparationConfiguration.java`. 모든 profile에서 `trust-agent.tool-api.service-token`과 `trust-agent.preparation-record.service-token`이 둘 다 설정돼 있고 같은 값이면 `IllegalStateException("SERVICE_TOKEN_NOT_SEPARATED: …")`으로 기동을 거부한다. 비교는 상수 시간이고 메시지에는 환경변수 이름만 있고 값은 없다. 비어 있는 경우는 기존 검사(각 경로 401, production 필수 설정)가 다룬다.
- 테스트: `ServiceTokenSeparationTest` 4건. 같은 값 거부와 메시지에 값 미포함, 다른 값·빈 값 통과, Spring 컨텍스트에서 같은 값이면 refresh 실패(오류 전체에 값 없음), 다른 값이면 정상 기동. 기존 통합 테스트들은 두 토큰을 서로 다른 임시 값으로 쓰므로 영향 없음(전체 실행으로 확인).
- 문서: Core README에 두 토큰이 같으면 기동 거부를 적었다.

#### 3. 제안 9: AI 서비스 HTTP 진입점의 기록 실패 상태 매핑 (코드 변경 전 판단 요청)

현재 HTTP 진입점은 준비안을 만들면 기록 결과와 무관하게 200을 돌려주고 본문 `record.recorded`와 헤더 `X-Preparation-Recorded`로만 구분한다. 정상 기록된 READY·PARTIAL·HOLD와 기록 실패는 다른 상황이므로 상태 코드로도 구분하자는 제안이다. 실패 본문의 `recorded=false`·`error_code`·`usage_notice`와 CLI 종료 코드 3은 그대로 둔다.

| 상황 | `record.status` / 원인 | 제안 HTTP 상태 | 이유 |
|---|---|---|---|
| 준비안 생성·기록 성공(READY·PARTIAL·HOLD 모두) | RECORDED / ALREADY_RECORDED | 200 | 요청이 끝까지 성공. HOLD도 "보류를 기록"한 성공이다 |
| Core가 현재 상태 기준으로 거부 | REJECTED, Core 409·422(`PREPARATION_NOT_USABLE`, `PREPARATION_STALE`, `MAPPING_MISMATCH`, `PREPARATION_CONFLICT`, `RUN_ID_CONFLICT` 등) | 409 Conflict | 준비안이 Core의 지금 상태와 맞지 않아 기록되지 않음. 호출자가 다시 실행하면 해결될 수 있는 충돌이며 호출자 입력 오류도, 서버 장애도 아니다 |
| Core가 요청 형식·계약 위반으로 거부 | REJECTED, Core 400(`INVALID_REQUEST`, `PREPARATION_ID_MISMATCH`, `HOLD_SECTION_INVALID`) | 500 Internal Server Error | 기록 본문은 AI 서비스가 만들었으므로 AI 서비스 자체의 결함이다. 호출자 잘못이 아니며 재실행으로 풀리지 않는다 |
| Core 인증 실패 | REJECTED, Core 401 `UNAUTHENTICATED` | 503 Service Unavailable | 기록 토큰 설정 문제. 운영자가 설정을 고쳐야 하며 `NOT_ATTEMPTED`(토큰 미설정)와 같은 부류 |
| 기록 미시도 | NOT_ATTEMPTED `RECORD_TOKEN_MISSING` | 503 Service Unavailable | 설정 누락(기존 설정 누락 503과 같은 뜻) |
| Core 통신 실패 | FAILED `CORE_UNAVAILABLE`(5xx·연결 실패) | 502 Bad Gateway | 상위 서비스(Core) 장애·오류 |
| Core 시간 초과 | FAILED `CORE_TIMEOUT` | 504 Gateway Timeout | 상위 서비스 응답 지연 |
| 입력 오류(신청 없음, 매핑 없는 상품, 날짜 형식) | (준비안 없음) | 400 (현행 유지) | 호출자 입력 오류 |
| 설정 누락(Core 주소·읽기 토큰) | (준비안 없음) | 503 (현행 유지) | 설정 문제 |

- Tool 단계 실패(401·5xx·시간 초과·schema 위반)는 섹션 HOLD(UNVERIFIED)로 처리되고 기록은 성공하므로 200이다. 이것은 "확인하지 못함"을 기록한 성공이며 사용 금지는 섹션 안내로 전달된다.
- 구현 방식(판단 뒤): FastAPI의 `HTTPException`은 본문을 바꾸므로 쓰지 않고, 같은 준비안 본문을 `JSONResponse(status_code=…)`로 돌려준다. 헤더 `X-Preparation-Recorded`는 유지한다. CLI는 바꾸지 않는다(종료 코드 3 그대로). 출력 계약은 바꾸지 않는다. 테스트는 `test_http_entry_point_returns_preparation_and_maps_errors`에 실패 사례별 상태 코드를 더한다. README의 HTTP 절을 갱신한다.
- 대안과 기각 이유: (a) 모든 실패를 4xx로 → Core 장애·설정 오류를 호출자 잘못처럼 보이게 해 기각. (b) 현행 200 + 헤더 유지 → 모니터링·프록시·대부분의 클라이언트가 성공으로 집계해 기각(사용자 지적). (c) 실패 시 본문을 오류 객체로 바꿈 → 보류 안내와 재현 정보가 사라져 기각(사용자 조건: 본문 유지).
**판단** - [x] 수정 채택 / 결정자 사용자 / 조건: 정상 기록 READY·PARTIAL·HOLD 200, Core 409는 409·422는 422로 구분(모든 거부가 단순 재실행으로 해결된다고 안내하지 않음), AI 서비스가 만든 요청의 계약 위반 500(입력 오류 400과 구분), 기록 토큰 누락·불일치 등 운영 설정 503, 통신 실패 502·시간 초과 504에 Core 자체 5xx 처리 명시, 실패 본문의 `recorded=false`·원인 코드·사용 금지 안내 유지, 토큰·내부 오류 상세 비노출, Tool 확인 실패를 HOLD로 기록한 200은 기록 성공일 뿐 준비 완료가 아님.
- 구현(5차 보완): `app.py`가 기록 결과 종류(`assembler.record_class`)별 상태 표 `HTTP_STATUS_BY_RECORD_CLASS`로 같은 본문을 `JSONResponse`에 담아 돌려준다(RECORDED·ALREADY_RECORDED 200, REJECTED_NOT_USABLE 422, REJECTED_CONFLICT 409, REJECTED_SETTINGS·NOT_ATTEMPTED 503, REJECTED_CONTRACT 500, FAILED_CORE·REJECTED_OTHER 502, FAILED_TIMEOUT 504). `assembler._record_outcome`이 Core 응답의 HTTP 상태를 `record.core_http_status`로 남기고, Core 5xx는 `CORE_ERROR_RESPONSE`(상세 미전달), Core 401은 고정 문구로 바꿔 인증 상세를 전달하지 않는다. `messages.USAGE_NOTICES`를 종류별로 나눠 422는 "업무 조건이 바뀐 뒤", 409는 "다시 실행하면 현재 상태로 재확인", 401·미설정은 "운영 설정 점검", 400은 "재실행으로 해결되지 않으니 담당자에게", 502·504는 "잠시 뒤 다시 실행"으로 안내한다. CLI 종료 코드는 그대로(기록 성공 0, 실패 3)이며 stderr에 안내를 함께 출력한다. 출력 계약에 `record.core_http_status`(선택, 400~599)를 추가했다. 고정 문구 표가 바뀌어 `messages_hash`와 준비안 ID가 바뀐다(의도된 동작, 표본 재생성).
- 테스트: Python `test_http_entry_point_returns_preparation_and_maps_errors`에 실패 7사례(422·409·500·503·502·502·504)와 기록 토큰 미설정 503을 더해 HTTP 상태·헤더 `false`·본문 `recorded=false`·원인 코드·사용 금지 안내·토큰 미노출을 함께 확인한다. `test_record_outcomes_map_to_status_and_usage_notice`가 사례별 `core_http_status`와 안내 문구(있어야 할 문구·있으면 안 되는 문구)를 확인한다. CLI 종료 코드 테스트는 그대로 통과(기록 성공 0, 실패 3).

### 5차 보완 (2026-10-08, 제안 9 수정 채택 구현)

- 검증 대상 커밋: `2e330d2`(4·5차 보완의 구현·테스트. 승인 전 HOLD 테스트, 토큰 동일값 기동 거부, HTTP 상태 구분). 문서는 후속 커밋.
- 변경 파일: `apps/ai-service/ai_service/app.py`, `assembler.py`(`record_class`, `core_http_status`, Core 5xx·401 처리), `messages.py`(원인별 사용 안내), `cli.py`(stderr 안내), `contracts/consultation-preparation.schema.json`(`record.core_http_status`), 테스트 2파일, AI 서비스 README("HTTP 상태" 표). Core 코드 변경 없음.
- 일관성: `recorded=true` ⇔ HTTP 200 ⇔ 헤더 `true` ⇔ CLI 종료 0. `recorded=false` ⇔ HTTP 422/409/503/500/502/504 ⇔ 헤더 `false` ⇔ CLI 종료 3. `app.py`가 이 대응을 단언한다.
- 결과: 연결 검증 포함 전체 185건 통과·실패 0·skip 0, 로컬 기본 185건 중 통과 179·실패 0·skip 6, 의도적 환경 누락 1건 실패(`AI_INTEGRATION_ENV_MISSING`), Python 106건 중 통과 104·실패 0·skip 2(기존 비공개 snapshot 사유). 표본(`docs/evidence/task-015/cli-output-*.json`)은 고정 문구 표 변경 뒤 다시 생성했다. 합계는 `RUN_SUMMARY.txt`.

### 6차 보완: 업무 인터뷰 반영 (2026-10-08. 작성 당시 미커밋, 이후 커밋 `f6ca899`·`c0498c1`로 PR #40에 포함)

#### 인터뷰 요약 (익명, 개인 식별 정보 없음)

다른 은행 영업점 행원 1명의 개인 경험 요약이다. KB국민은행이나 모든 영업점의 공통 절차로 일반화하지 않으며, 이 프로젝트의 업무 가설을 검토하는 참고 자료로만 쓴다. 인터뷰만으로 구체적인 금융 규칙이나 문서 우선순위를 확정하지 않는다.

1. 기본적으로 업무 매뉴얼을 먼저 참고한다. 수신은 상품설명서, 여신은 내부문서를 많이 보며 정책에 따라 달라지는 상품이 많다.
2. 고객확인·FATCA 등 추가 확인 사항이 생기고, 사전에 확인하면 고객 재방문을 줄일 수 있지만 매번 확인하기 어렵다. 고객이 필요한 기재 내용을 가리거나 발급일 조건에 맞지 않는 서류를 가져오는 경우도 있다.
3. 상품 상담은 고객 니즈 확인부터 시작하고 소득정보 등을 본다. 행원이 상품을 먼저 숙지해야 하며 KPI도 영업 추진에 영향을 준다.
4. 담당자의 확인 책임이 일차적이고, 놓친 부분은 감사에서 확인하는 편이다(심사부서의 보완 절차와 같은 뜻으로 해석하지 않는다).
5. 대부분 팀장 수준에서 해결하고, 특이 사례는 내부문서를 먼저 확인한 뒤 애매하면 사내전화로 본부에 문의한다.
6. 변경사항이 많아 헷갈리며, 지점 내 공유와 올라오는 문서의 잦은 확인이 필요하다.
7. 신입은 시스템 조작과 문서 찾는 방법에 익숙하지 않아 어려움을 겪는다.
8. 외국인·세금·외화송금 등 특이 사례에서 정보를 찾는 데 시간이 걸리고 고객별 사례가 다양하다. 기업여신에 한정된 답변은 아니다.

#### 업무 가설에 대한 쓰임 (근거와 기대효과 구분)

- 근거로 쓰는 업무 가설: **변경 기준 혼동**(6), **문서 탐색의 어려움**(1·5·7·8), **서류 보완의 어려움**(2). 이 프로젝트가 "변경된 기준을 근거와 함께 보여 주고 보류를 분명히 한다"는 방향을 지지하는 개인 경험 1건이다.
- 아직 검증하지 않은 기대효과(근거 아님): 상담 준비 시간 단축, 고객 재방문 감소. 측정 방법과 수치는 후속 Task에서 정한다.

#### 기존 설계 유지

사람 승인은 원문에서 정리한 checklist가 원문과 맞는지 검수하는 통제이며, 실제 은행의 여신 승인 절차나 매 상담마다 별도 승인을 받는 과정이 아니다(인터뷰 4의 "담당자 일차 책임"과도 다른 층위). 기존 승인·재확인·기록 경계는 그대로다.

#### 이번 TASK-015 보완 내용

- READY의 뜻을 **"기준 자료 준비 완료"**로 고정했다. 머리 문구: "기준 자료 준비 완료: 공문군 N개 가운데 필수 M개의 기준 자료가 모두 준비됐습니다. 현재 승인된 매핑에 따른 필수 자료이며, 고객별 적용 조건·제출서류 확인이나 상담·대출 결정이 끝났다는 뜻이 아닙니다."
- 출력에 `notices.staff_check_notice`를 필수로 더했다. READY: "기준 자료 준비 완료입니다. 고객별 적용 조건과 제출서류는 직원 확인이 필요합니다. 고객 니즈 확인과 상품 선택은 이 준비안의 앞 단계이며 이 준비안이 대신하지 않습니다." PARTIAL·HOLD: "상담 준비가 끝나지 않았습니다. 보류된 필수 공문군을 먼저 확인하세요. 그 뒤에도 고객별 적용 조건과 제출서류는 직원 확인이 필요합니다." 두 안내는 문구로 구분된다.
- 계약: READY면 머리 문구에 "기준 자료 준비 완료", `staff_check_notice`에 "직원 확인이 필요합니다"를 요구한다. 고정 문구 표가 바뀌어 `messages_hash`와 준비안 ID가 바뀌므로 표본을 다시 생성했다.
- 테스트: Python `test_all_required_ready_gives_ready_with_completion_notice`(READY 문구·직원 확인 안내·"끝나지 않았습니다" 없음), `test_partial_preparation_…`(PARTIAL은 미완료 안내), 고정 문구 표 검사, Java `prepareCommandProducesFullyReadyPreparation`(READY 문구·직원 확인 안내), `prepareCommandAssemblesPartialPreparationAndRecordsIt`(PARTIAL 미완료 안내). README 3곳 갱신.
- 한계(문서화): 고객 니즈 확인과 상품 선택은 현재 신청 건 기반 준비안 기능의 앞 단계이며 이 기능이 대신하지 않는다. 고객별 적용 조건·제출서류(필요서류, 발급일·기재 조건) 확인은 직원 몫이고 이 기능은 아직 돕지 않는다.

#### 후속 검토 항목 (기록만, 구현하지 않음)

- TASK-016: 업무 매뉴얼·내부 규정까지 포함한 근거 검색과 자료 간 우선순위·충돌 처리(인터뷰 1·5·8). 우선순위 규칙은 인터뷰만으로 정하지 않는다.
- TASK-017: 원문으로 이동, 필요서류와 발급일·기재 조건 확인, 보류 시 다음 확인 행동과 문의할 내용 표시(인터뷰 2·5·7).
- 범위 밖으로 유지: KPI 기반 상품 유도(인터뷰 3)는 이번 범위에 넣지 않는다.

#### 검증 결과 (검증 대상 커밋 `f6ca899`: 안내·계약·테스트 변경. 문서는 후속 커밋)

연결 검증 포함 전체 185건 통과·실패 0·skip 0, 로컬 기본 185건 중 통과 179·실패 0·skip 6, 의도적 환경 누락 1건 실패(`AI_INTEGRATION_ENV_MISSING`), Python 106건 중 통과 104·실패 0·skip 2(기존 비공개 snapshot 사유). 고정 문구 표가 바뀌어 준비안 ID·`messages_hash`가 달라졌고 표본(`docs/evidence/task-015/cli-output-*.json`)을 다시 생성했다. 합계는 `RUN_SUMMARY.txt`(커밋 `c0498c1`에 포함).

### AI self-review

- [x] 승인 범위 안에서만 구현(검색·LLM 생성 없음). [x] 기존 승인·일정·변경안·검증 코드 변경 없음(diff 확인). [x] 토큰 값은 환경변수만, 테스트는 실행 중 생성한 임시 값. [x] 합성 자료 표시(SYNTHETIC_WORK·disclaimer). [x] "기록은 사용 허가가 아님", "READY는 기준 자료 준비 완료이며 고객별 확인·상담·대출 결정의 완료가 아님", "AI 생성 완료 아님" 명시. [x] 완료 기준을 낮추지 않았고 미검증·부분 검증 항목(AC-06, 08 일부, 14 CI)을 표에 그대로 적음. [x] 커밋·push·검토용 PR은 사용자 지시 뒤 수행(병합은 사용자).

### 인간 검수 / Explainability Gate / 결정 기록

- 인간 검수: **통과**(결정자 사용자, 2026-10-08). 검수 대상: 최신 PR #40(인터뷰 반영 구현·계약·테스트 커밋 `f6ca899`, 문서 커밋 `c0498c1` 포함. 이전 검증 대상 커밋 `87b553b`·`2e330d2`도 같은 PR 안). 검수 자료: 이 문서의 1~6차 보완 절, evidence 문서와 `docs/evidence/task-015/` 결과 요약·CLI 표본, PR #40 본문의 CI 기록.
- 인간이 확인하고 동의한 내용: (1) READY는 기준 자료 준비 완료이며 고객별 적용 조건·제출서류는 직원 확인이 필요하고 상담·대출 결정의 완료가 아니다. (2) PARTIAL·HOLD는 준비 미완료 사항과 보류 이유를 표시하고 보류 섹션에는 항목·근거를 제공하지 않는다. (3) 기록 실패는 `recorded=false`와 사용 금지 안내, HTTP 오류 상태·CLI 실패 종료로 구분한다. (4) 재실행과 동시 저장 충돌 뒤에도 현재 상태를 재확인하며, 같은 업무 내용은 중복 저장하지 않고 실행별 이력을 남긴다. (5) 읽기·기록 토큰을 분리하고 기록 경로는 공문 승인·적용 일정 등 기존 업무 상태를 바꾸지 않는다. (6) 검색·LLM 생성·화면·실제 사용자별 인증·권한은 범위 밖이며 인터뷰 기반 기대효과는 측정한 결과가 아니다.
- Explainability Gate: **통과**(결정자 사용자).

### 결정 기록과 완료

- 최종 결정 / 결정자 / 승인 범위 / 검토 대상 PR: **완료** / 사용자 / 규칙 기반 상담 준비안·보류, Core 기록, 저장 전 재확인, CLI와 HTTP 연결, 필수 연결 검증, 안전성 자료 v2 / PR #40(병합은 사용자가 직접).
- Acceptance Criteria 충족 / evidence / PR: AC-01~21 모두 로컬 실행과 PR #40 CI(완료 판정 당시 최신 run 37666909029: Java 185/185, 연결 검증 실제 실행·skip 0, Python 106건 중 skip 2는 기존 비공개 snapshot 사유)로 확인. 브랜치 최종 커밋 `b55ae62`의 [run 37667987850](https://github.com/hj1016/trust-agent/actions/runs/37667987850)과 병합 뒤 main `c54c923`의 [run 37689504964](https://github.com/hj1016/trust-agent/actions/runs/37689504964)도 같은 결과로 성공. evidence는 `docs/evidence/CONSULTATION_PREPARATION_EVIDENCE.md`와 `docs/evidence/task-015/`.
- **이 완료는 규칙 기반 조립·기록·재확인·연결 검증의 완료이며, "AI 생성 완료"나 "LLM 안전성 검증 완료"가 아니다.** 안전성은 구조 검증(SAFE-A·B·D·E·F)까지이고 질문 기반·LLM 출력 검증은 TASK-019에서 한다.
- 알려진 한계(유지): 409 `PREPARATION_CONFLICT`는 정상 경로로 만들 수 없는 방어선이라 테스트 없음. 실제 동시 철회 타이밍은 40001 주입과 저장소 대역으로만 검증. 토큰은 test/demo 수준. 매핑은 합성 시나리오 1건. 고객 니즈 확인·상품 선택과 고객별 적용 조건·제출서류 확인은 이 기능이 돕지 않음. 인터뷰는 한 명의 경험에 따른 업무 가설 근거이며 효과는 미측정.
- 후속 판단(유지): TASK-016(업무 매뉴얼·내부 규정 포함 검색 범위, 자료 간 우선순위·충돌 처리, AC-05b·05c 수치), TASK-017(원문 이동, 필요서류·발급일·기재 조건 확인, 보류 시 다음 행동·문의 내용 표시, 화면), TASK-019(LLM 생성과 안전성 검증). 제안 9의 HTTP 상태 매핑은 화면 연결 시 호출자 처리 방식과 함께 재검토. PLAN-002의 TASK-015 완료 표시는 병합 뒤 문서 PR로 갱신(반영됨. 같은 PR에서 README·AGENTS.md·Core README의 "FastAPI 미구현" 문구와 ADR-012의 "초안"·"승인되면" 제목, 이 문서의 "미커밋" 표기를 정리).
