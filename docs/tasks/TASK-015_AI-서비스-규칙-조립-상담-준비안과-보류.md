# TASK-015 AI 서비스 최소 흐름: 규칙 조립 상담 준비안과 보류

- 상태: **계획 검토 대기** (상세 계획 작성 지시: 사용자. 큰 방향 동의, 보완 요청 8건 반영. **제안 6건 채택**(결정자 사용자, 보완된 방향)과 추가 조건 5건 반영. 제안 7(안전성 책임 표시 변경)은 (b) 채택. 설계 보완 4건(표 수 일치와 매핑 별도 경로, READY·HOLD 기록의 뜻 구분, 안전성 자료 v2 계획, 같은 ID 재요청도 재확인) 반영. ADR-012 최종 승인과 구현 착수는 별도 결정)
- 담당자 / 인간 결정자: AI 조사·초안·구현·검증 / 사용자 범위·판정·검수
- 요구사항 출처: PLAN-002 TASK-015 절(제안 1 수정 채택: LLM 없는 규칙 조립 준비안은 첫 연결 단계, "AI 생성 완료"로 표현하지 않음. 제안 2 채택: 준비안·보류 기록은 Tool이 아니라 별도 쓰기 경로, Core가 저장 전 재확인), CLAUDE.md 기술 경계(AI Service는 Python + FastAPI, 업무 DB 직접 접근과 자격증명 보유 금지, Core Tool API로만 조회. AI는 승인·거절·금리·한도·신용등급 결정 주체가 아님), ADR-011(읽기 전용 Tool 2개, 서비스 토큰, 감사, 쓰기 Tool 없음), README MVP 7단계(상담 준비안)와 9단계(AI 중단 시 수기 checklist, AI 확정 경로 차단), TASK-014 안전성 참조 3건(`pass_criteria_owner: TASK-015`), 사용자 보완 요청 8건(부분 준비 표시, 보류 기록, 재실행, Core 직접 확인, 재현 정보, 메모와 안전성, 기록 권한, 검증 환경과 변경 범위)
- 관련 Task / ADR: TASK-008(완료), TASK-011a(완료), TASK-014(완료. 검색 목표 수치는 TASK-016 시작 전에 정하고, 실제 관련성 보류 기준값은 TASK-016에서 초기 점검 자료로 조정한 뒤 최종 평가 전에 고정한다), TASK-016·017·019(이 Task 뒤). **ADR-012 초안**(`docs/adr/ADR-012-ai-service-preparation-record-path.md`, 미승인)을 이 Task에서 함께 제안한다.
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
| AC-01 | 승인된 중도상환수수료 공문군, 업무일 2026-10-06, `SW-APPLICATION-001` | 섹션 READY. 항목 3개가 Tool 1 응답과 같고 항목마다 Tool 2 근거(문장·위치·해시), version ID·결정 ID 포함 | Python 단위 + 연결 검증 | 미검증 |
| AC-02 | 셀러론 공문군(필수, 미승인) 같은 신청 건 | 섹션 HOLD `CORE_DECISION`/`HUMAN_REVIEW_PENDING`, 항목·근거 없음, 설명·수기 안내. 전체 PARTIAL, `preparation_complete=false`, `remaining_checks`에 셀러론, headline에 "끝나지 않았습니다" | Python 단위 + 연결 검증 | 미검증 |
| AC-03 | 모든 필수 공문군 사용 불가(업무일 2026-09-30) | 전체 HOLD, 추측 항목 없음, HOLD 섹션이 Core에 기록됨 | Python 단위 + Core 통합 | 미검증 |
| AC-04 | Tool 2가 한 항목에 403 | 섹션 전체 HOLD `UNVERIFIED`/`EVIDENCE_UNAVAILABLE`, 다른 항목 근거도 제공하지 않음 | Python 단위 | 미검증 |
| AC-05 | Tool 401/403/5xx/시간 초과/응답 schema 위반 | 섹션 HOLD `UNVERIFIED`(코드 구분), 추측 없음. 기록 실패 시 `recorded=false`·사용 금지 안내·종료 3 | Python 단위 | 미검증 |
| AC-06 | Core 기록: READY 준비안 | 201, 세 표 행과 재현 정보(5절), 근거 원문 미저장 | Core 통합 | 미검증 |
| AC-07 | Core 기록: C1~C5 각각 어긋남(철회·반려·항목 누락·순서 변경·근거 해시 변조·선택 공문 변경) | 422/409 해당 코드, 준비안 행 없음, 실행 기록 REJECTED | Core 통합 | 미검증 |
| AC-08 | 같은 ID 같은 내용 재기록, 동시 2회, 같은 ID 다른 내용 | ALREADY_RECORDED(새 run), 행 1개, 400/409 거부. **같은 ID 재요청에서도 C1~C5 재확인이 실행됨**(재확인 전에 철회하면 기존 행이 있어도 422 거부, 재확인 조회 호출이 기록됨) | Core 통합 | 미검증 |
| AC-09 | 토큰 없음·불일치, 읽기 토큰으로 기록, schema 밖 필드, 본문 actor | 401 / 400, 서비스 ID·scope는 토큰 기준 | Core 통합 | 미검증 |
| AC-10 | 기록 호출 전후 | 승인·일정·변경안·검증·공문 사건 표 행 수 불변. 세 표 append-only·보호 목록·CHECK | Core 통합 | 미검증 |
| AC-11 | 안전성 SAFE-A·B·D·E·F·G | 전부 통과 | Python 계약·단위 + Core 통합 + diff | 미검증 |
| AC-12 | AI 서비스 설정·의존성 | 업무 DB 자격증명 항목 없음, DB 드라이버 의존성 없음 | Python 단위 + 검토 | 미검증 |
| AC-13 | 재실행 | 기존 기록이 있어도 Tool 1·2를 다시 호출함(가짜 Core 호출 횟수), 상태 불변 시 ALREADY_RECORDED, 철회 뒤 새 ID HOLD | Python 단위 + 연결 검증 | 미검증 |
| AC-14 | 연결 검증 자동 테스트 | 필수 CI(Gradle tests 체크)에서 실제 실행되고 통과. 로컬 기본은 명시적 skip으로 보고서에 남고, CI에서는 환경 누락이 실패(`AI_INTEGRATION_ENV_MISSING`)로 드러남. 시간 제한, 출력 파일 수집, 실패 시 프로세스 종료 | Java 통합 + CI 로그 + 의도적 환경 누락 실패 1회 확인 | 미검증 |
| AC-15 | 기존 테스트 수·결과 유지, 기존 승인·일정·변경안·검증 코드 diff 없음, README·AI 서비스 README·ADR-012가 실제 상태 기술, "AI 생성 완료"·"LLM 안전성 검증 완료" 표현 없음, "기록은 사용 허가가 아님"과 "READY는 상담·대출 결정의 완료가 아님" 명시 | 로컬·CI + diff 검토 | 미검증 |
| AC-16 | 매핑 불일치 요청: 필수 섹션 누락, `required` 값 변경, 섹션 상태와 다른 전체 READY 주장, 다른 `family_mapping_hash` | 409 `MAPPING_MISMATCH` / 422 `REQUIRED_SECTION_MISSING`·`REQUIRED_FLAG_MISMATCH`·`PREPARATION_STATUS_INVALID`, 준비안 행 없음, 실행 기록 REJECTED | Core 통합 | 미검증 |
| AC-17 | 필수 공문군이 없는 매핑 | AI 서비스는 HOLD·`preparation_complete=false`·`NO_REQUIRED_FAMILY_CONFIGURED`, Core는 READY 요청을 422 `NO_REQUIRED_FAMILY`로 거부 | Python 단위 + Core 통합 | 미검증 |
| AC-18 | HOLD 섹션 기록의 공통 검사와 보증 범위 | 항목·근거가 있는 HOLD 섹션은 400 `HOLD_SECTION_INVALID`. 저장된 HOLD 행에 `hold_claim_basis`와 `recheck_*`가 있고, AI가 보낸 사유와 재조회 사유가 달라도 둘 다 저장됨 | Core 통합 | 미검증 |
| AC-19 | 직렬화 실패와 실행 기록 | 동시 철회와 기록이 충돌하면 재확인부터 1회 재시도, 재시도 실패는 FAILED(`SERIALIZATION_FAILED`) 1건. 같은 `run_id` 재전송은 409 `RUN_ID_CONFLICT`. 요청당 실행 기록 1건 | Core 통합 | 미검증 |

### 완료 기준 변경 이력

변경 없음(구현 전).

## Implementation Plan (초안)

1. ADR-012 초안 검토·승인(기록 경로, 토큰 분리, 할 수 있는 것과 없는 것, 실패 처리, 기록 ≠ 사용 허가).
2. 계약·설정: `contracts/consultation-preparation.schema.json`, `contracts/consultation-preparation-record.schema.json`, `contracts/consultation-family-mapping.schema.json`, `datasets/synthetic/work/consultation-family-mapping.json`(합성 시나리오의 사용자 승인 설정. Core와 AI 서비스가 같은 파일을 쓴다).
3. Core: V10 migration(준비안·섹션·실행 기록·매핑 네 표, CHECK, 보호 목록, 권한), 매핑 bootstrap importer, 기록 토큰 설정과 필터, `ConsultationPreparationController`/`Service`(공통 검사, M1~M5, C1~C8, SERIALIZABLE 1회 재시도)/`Repository`, 통합 테스트.
4. AI 서비스: `apps/ai-service/ai_service/`(`config.py`, `core_client.py`, `assembler.py`, `messages.py`, `ids.py`(해시·run_id), `cli.py`, `app.py`), `requirements-ai.txt`·lock, 테스트 `tests/ai_service/`.
5. 연결 검증: Gradle 작업·Java 통합 테스트·CI 단계, evidence `docs/evidence/CONSULTATION_PREPARATION_EVIDENCE.md`, README 3곳 갱신, 안전성 참조 자료 v2(책임 객체 구분)와 schema·계약 테스트 변경, TASK-014 문서 "후속 변경" 절 추가(제안 7, 구현 단계).

예상 크기: Core Java 약 8파일 + migration 1 + 테스트 2클래스, Python 약 8파일 + 테스트 5파일, 계약 3, 설정 1, CI·Gradle 변경, 문서 4. **기존 승인·일정·변경안·검증 코드 변경 없음, 새 저장·재확인 기능 추가.**

대안 검토: (a) 쓰기 Tool로 추가 → ADR-011 위반, 거절. (b) AI 서비스 자체 파일 기록 → 재확인과 "기록 없는 제공 없음"을 잃어 거절. (c) 공문군 하나만 처리 → 보류 사례를 한 명령에서 못 보여 줘 보류. (d) LangGraph → 불필요. (e) 기록 생략하고 출력만 → MVP 7단계의 기록 요구와 어긋나 거절.

위험: "AI가 Core에 쓴다"는 인상 → 업무 상태를 바꾸지 않고 산출물만 기록함을 ADR-012·README에 명시, AC-10으로 검증. 고정 문구 표와 Core 사유 코드 불일치 → 테스트 대조. SERIALIZABLE 재시도 → 1회로 제한, 실패는 FAILED 기록. Python 환경이 CI에 없음 → Gradle 작업으로 준비, 속성 없으면 건너뜀을 보고.

인간의 계획 판단 / 승인 범위: 판단 대기.

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

## Implementation Result / AI self-review / 인간 검수 / 결정 기록

미실행 / 미기록. 구현은 사용자 착수 승인 뒤.
