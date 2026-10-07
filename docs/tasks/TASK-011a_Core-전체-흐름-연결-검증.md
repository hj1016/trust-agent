# TASK-011a Core 전체 흐름 연결 검증 (화면 전)

- 상태: **완료** (상세 계획 작성 승인: 사용자, PLAN-002 PR #32. 구현 착수 승인: 사용자, 아래 "착수 승인과 조건". 구현 PR #36. 인간 검수와 Explainability Gate 통과, 완료 승인: 사용자. 화면 전 Core 전체 흐름 연결 검증 범위의 완료이며 검색, AI 서비스, 화면, 실제 사용자 권한의 완료가 아니다)
- 담당자 / 인간 결정자: AI 조사·초안·구현·검증 / 사용자 범위·판정·검수
- 요구사항 출처: PLAN-002 TASK-011a 절, 사용자 판단 "Core의 변경안 생성 → 검증 → 승인 → 조회 전체 흐름 검증을 화면 뒤로만 미루지 않는다. 기존 자동 검증으로 이미 충족한 범위를 확인하고 부족한 연결 검증을 먼저 계획", README MVP 4~6단계와 9단계의 Core 범위
- 관련 Task: TASK-005~008, 013(완료). TASK-011b(AI·화면 연결 뒤 전체 흐름 검증)는 별도

## Goal / 관련 요구사항

- Goal: 합성 공문 수신부터 AI용 Tool 조회까지(S1~S7)를 **사람이 실제로 쓰는 진입점**(demo 명령 설정과 HTTP)으로 한 번에 재현하는 테스트와 evidence를 만든다. 업무 로직을 바꾸지 않는다.
- 사용자: 검수자(사용자)와 후속 Task 구현자. "Core 승인과 조회 흐름 완료"의 근거 자료가 된다(전체 MVP 완료가 아니다).
- Out of Scope: 업무 로직 변경, 새 기능, 화면, AI 서비스, 성능·동시성, 사용자별 권한.

## 이미 충족한 범위 (기존 자동 검증, 사실)

| 연결 | 보장하는 테스트 | 진입점 |
|---|---|---|
| 적재 → 예시 checklist → 변경안 생성 → 자동 검증 → 승인·수정·반려 → checklist·일정 발행 | `HumanReviewIntegrationTest`(격리 DB, 7건) | 서비스 객체를 테스트가 직접 생성해 호출 |
| 같은 흐름 뒤 HTTP 적용 공문 조회: 사용 허용 true, FIXTURE·결정 없음·공개 근거·선택 변경·철회·반려 차단 | `HumanReviewApplicableIntegrationTest`(3건), `InternalPolicyApplicableIntegrationTest`(20건) | 서비스 객체 직접 호출 + HTTP |
| 같은 흐름 뒤 Tool 조회·근거, 인증·허용 목록·감사, 조회 뒤 반려·철회 재차단 | `ToolApiIntegrationTest`(7건), `ToolApiMissingTokenIntegrationTest`(1건) | 서비스 객체 직접 호출 + HTTP |
| 변경안 생성, 검증, 결정 각각의 규칙·거부·원자성·DB 제약 | `ChecklistProposalIntegrationTest`, `ProposalValidationIntegrationTest`, `HumanReviewIntegrationTest`, 단위 테스트 | 서비스 객체 직접 호출 |
| production 설정 거부, schema 보호 수치 | `DemoFeatureProductionGuardTest`, `RuntimeDatasourcePropertiesTest`, `PublicProductSchemaIntegrationTest` | 컨텍스트 |

이 범위는 다시 쓰지 않는다.

## 부족한 연결 (사실)

1. **demo 명령 진입점을 타는 테스트가 없다.** core README의 명령 4개(예시 적재 `trust-agent.fixture-approved-checklist.*`, 변경안 생성 `trust-agent.proposal-generation.*`, 검증 `trust-agent.proposal-validation.*`, 사람 결정 `trust-agent.human-review.*`)는 `ProposalDemoConfiguration`의 ApplicationRunner로 실행되는데, 설정 키 이름, 필수 인자 검사(`required(...)`), 결정 종류 문자열 변환, 수정 결정의 `revised-rules-json` 해석을 검증하는 테스트가 없다. README 설명과 코드가 어긋나도 잡히지 않는다.
2. **수정 → 재검증 → 승인 뒤 조회·Tool 응답**이 없다. `HumanReviewIntegrationTest`는 수정 revision 승인까지만 확인하고, 고친 설명 문구가 적용 공문 조회와 Tool 응답의 항목에 실제로 나오는지는 확인하지 않는다.
3. **한 흐름을 끝까지 재현한 evidence가 없다.** 각 Task evidence는 자기 범위만 다룬다. 입력 명령, 단계별 DB 상태, 실제 HTTP 응답을 한 문서에 순서대로 적은 자료가 없다.

## 계획 (추가만, 재작성 없음)

- 새 테스트 1클래스 `CoreEndToEndFlowIntegrationTest`(격리 DB 1개, 순서 고정):
  1. 적재: `BaselineImporter`, `SyntheticInternalImporter`(기존 방식 유지. 적재는 demo 명령이 아니라 importer 명령이므로 기존 진입점 그대로).
  2. 예시 checklist 적재 → 변경안 생성 → 검증 → 승인을 **각각 non-web Spring 컨텍스트**(`SpringApplicationBuilder`, `web-application-type=none`, 해당 설정 키만 켬)로 띄워 ApplicationRunner가 실행되게 한다. 컨텍스트마다 종료 뒤 DB 상태(실행 기록 1건, 결과 ID)를 확인한다. 시계는 테스트 고정값(기존 테스트와 같은 2026-10-05 계열).
  3. 잘못된 순서 거부: 검증 전 승인(`VALIDATION_MISSING`), 없는 변경안 검증(`PROPOSAL_NOT_FOUND`), 필수 인자 누락(runner의 `IllegalStateException`)이 각 단계에서 거부되고 실패 실행 기록만 남는다.
  4. 수정 경로: 설명 문구만 고친 `revised-rules-json`으로 수정 결정 → 재검증(WARN `INSTRUCTION_EDITED`) → 사유 승인을 runner로 실행.
  5. 조회: web 컨텍스트에서 적용 공문 조회와 Tool 조회(임시 토큰)가 같은 version ID·결정 ID를 돌려주고, 항목에 고친 문구가 보이며, 사용 허용 true. 미승인 공문군은 둘 다 사용 불가.
- evidence `docs/evidence/CORE_END_TO_END_FLOW_EVIDENCE.md`: 단계별 명령(설정 키 그대로), 입력, 실제 출력(실행 기록 행, 결과 ID, HTTP 응답 핵심 필드), 거부 사례. README의 명령 예시와 설정 키가 일치하는지 대조한 결과.
- README: "Core 승인과 조회 흐름 연결 검증 완료(화면 전)" 한 줄과 evidence 링크. MVP 완료 표현 금지.

## Acceptance Criteria (초안)

| AC | 내용 | 검증 |
|---|---|---|
| AC-01 | 예시 적재 → 변경안 생성 → 검증 → 승인이 **demo 설정 키와 ApplicationRunner**로 순서대로 실행되고 단계마다 실행 기록(SUCCEEDED)과 결과 ID가 남는다 | 통합 테스트 |
| AC-02 | 승인 뒤 적용 공문 조회와 Tool `applicable_checklist`가 같은 version ID·결정 ID·항목 3개를 돌려주고 사용 허용 true. Tool `rule_evidence`가 그 항목 근거를 제공 | 통합 테스트(HTTP) |
| AC-03 | 설명 문구만 고친 수정 결정(`revised-rules-json`) → 재검증 WARN → 사유 승인이 runner로 실행되고, 조회와 Tool 항목에 고친 문구가 보인다 | 통합 테스트 |
| AC-04 | 잘못된 순서와 누락 인자가 각 단계에서 거부된다: 검증 전 승인 `VALIDATION_MISSING`, 없는 변경안 `PROPOSAL_NOT_FOUND`, 필수 설정 누락 기동 실패, 미승인 공문군 조회 사용 불가. 거부 시 결과·checklist가 생기지 않는다 | 통합 테스트. demo runner 경로는 새 테스트, 서비스 직접 호출·HTTP 경로는 기존 테스트로 나누어 연결한다(구현 결과의 AC-04 대응표). 기존 테스트가 보장하는 조건은 재작성하지 않는다 |
| AC-05 | README의 명령 예시 설정 키가 코드의 키와 전부 일치(테스트가 README를 읽어 대조하거나 evidence에 대조표) | 테스트 또는 evidence |
| AC-06 | 기존 테스트 수와 결과 유지(현재 Java 153, Python 65), 업무 로직 파일 변경 없음(diff가 테스트·문서·README뿐) | 로컬/CI + diff 검토 |
| AC-07 | evidence에 단계별 실제 출력과 거부 사례, "전체 MVP 완료가 아니다" 명시 | diff 검토 |

## Implementation Plan (초안)

1. `CoreEndToEndFlowIntegrationTest`(Testcontainers, 격리 DB, non-web 컨텍스트 4~6개 순차 + web 컨텍스트 1개).
2. README 설정 키 대조(테스트에서 README 텍스트의 `--trust-agent.*` 키 추출 → 코드 상수와 비교).
3. evidence 문서, README 한 줄.

예상 크기: 테스트 1클래스(~8건), 문서. 업무 코드 0행.

## AI 제안 및 인간 판단 기록

### 제안 1: 적재 단계는 importer 명령 그대로, demo runner는 4개만
- 내용: 공문 적재는 TASK-001 범위의 importer 진입점이라 기존 방식으로 두고, 이 Task는 TASK-005~007의 demo runner 4개 연결만 검증한다.

**판단**
- [x] 채택
- 판단자 / 검토 대상 PR: 사용자 / PR #35(계획), PR #36(구현)
- 이유 / 승인 범위: importer 권한과 진입점을 유지한다. 적재는 importer 명령 그대로 두고 demo runner 4개(예시 checklist 적재, 변경안 생성, 자동 검증, 사람 결정)의 연결만 검증한다.

### 제안 2: README 설정 키를 테스트가 직접 대조
- 내용: README에서 `--trust-agent.*` 키를 읽어 코드 상수와 비교해 문서와 코드의 어긋남을 자동으로 잡는다. 대안: evidence의 대조표(수동).

**판단**
- [x] 채택
- 판단자 / 검토 대상 PR: 사용자 / PR #35(계획), PR #36(구현)
- 이유 / 승인 범위: 테스트가 core README의 `--trust-agent.*` 키를 읽어 main 코드(Java, yml)와 대조한다. 수동 대조표는 쓰지 않는다.

### 착수 승인과 조건 (사용자 지시)
- 결정자 / 승인 범위 / 검토 대상 PR: 사용자 / 아래 네 조건 / PR #35(계획), PR #36(구현)
- 조건 1: importer 권한과 진입점 유지(제안 1).
- 조건 2: README 설정 키 대조(제안 2).
- 조건 3: demo 명령 진입점부터 적용 공문 조회와 AI용 Tool 조회까지 연결 검증. 업무 로직 변경 없음.
- 조건 4: 결함을 숨기지 않고 보고한다. 테스트 쪽 수정과 관찰 사항도 evidence와 PR에 남긴다.
- 이 승인은 구현 착수와 검증 실행의 승인이며, 인간 검수와 Explainability Gate 통과, 완료 판정은 아니다.

## Implementation Result / AI self-review / 인간 검수 / 결정 기록

### 구현 결과 (AI 작성, 사실)

- 검증 대상: 브랜치 `test/core-end-to-end-flow`, PR #36. 최초 구현 commit `a3f2def`(공개 CI 통과), 검수 보완 코드 commit `6430930`(거부 사례의 발행 행 전후 건수와 실패 실행 기록 확인, FIXTURE 기간 사유 확인, 기존 테스트 연결. 로컬 Java 158건·Python 65건 통과). 문서만 바꾼 commit은 검증 대상이 아니며 evidence에 구분해 적었다. 상세: [검증 기록](../evidence/CORE_END_TO_END_FLOW_EVIDENCE.md).
- 변경 파일: 테스트 1클래스 `CoreEndToEndFlowIntegrationTest`(5건), evidence 문서, 루트 README 한 줄과 evidence 링크. 업무 코드 0행.
- 실행 방식: demo 명령 4개는 README의 `bootRun --args` 명령과 같은 명령행 인자로 web 없는 Spring 컨텍스트를 띄워 ApplicationRunner를 1회씩 실행했다. 적재는 importer 진입점을 그대로 썼다. 조회와 Tool은 web 컨텍스트에서 HTTP로 호출했다.

AC 대응표. "demo runner"는 새 테스트가 명령 진입점으로 확인한 것, "서비스 직접 호출" 또는 "HTTP"는 기존 테스트가 보장하고 재작성하지 않은 것이다.

| AC | 결과 | 보장 테스트 | 경로 |
|---|---|---|---|
| AC-01 | 통과 | `CoreEndToEndFlowIntegrationTest.demoCommandsRunTheCoreFlowInOrderAndLeaveRunRecords` | demo runner |
| AC-02 | 통과 | `CoreEndToEndFlowIntegrationTest.applicableQueryAndToolReturnTheIssuedChecklistsAndEditedInstruction` | demo runner로 발행 뒤 HTTP |
| AC-03 | 통과 | `CoreEndToEndFlowIntegrationTest.modifyRevalidateAndApproveThroughDemoCommands`, 같은 클래스 `applicableQueryAndToolReturnTheIssuedChecklistsAndEditedInstruction`(고친 문구 노출) | demo runner, HTTP |
| AC-04 검증 전 승인 `VALIDATION_MISSING` | 통과 | `CoreEndToEndFlowIntegrationTest.demoCommandsRunTheCoreFlowInOrderAndLeaveRunRecords`(실패 실행 기록, 발행 행 전후 건수 동일). 기존 `HumanReviewIntegrationTest.approvalIsRefusedForFailedMissingStaleMismatchedOrUnexplainedWarnValidation` | demo runner. 서비스 직접 호출 |
| AC-04 없는 변경안 `PROPOSAL_NOT_FOUND` | 통과 | 승인 쪽: `CoreEndToEndFlowIntegrationTest.wrongOrderAndMissingSettingsAreRefusedBeforeAnythingIsIssued`(실패 실행 기록, 발행 행 없음). 검증 쪽: 기존 `ProposalValidationIntegrationTest.revalidationAppendsNewResultAndRunIdConflictIsRejected`(`validation_run` FAILED) | demo runner. 서비스 직접 호출 |
| AC-04 필수 설정 누락 기동 실패 | 통과 | `CoreEndToEndFlowIntegrationTest.wrongOrderAndMissingSettingsAreRefusedBeforeAnythingIsIssued`(실행 기록과 결과 없음) | demo runner |
| AC-04 사유 없는 WARN 승인 `REASON_REQUIRED` | 통과 | `CoreEndToEndFlowIntegrationTest.modifyRevalidateAndApproveThroughDemoCommands`(실패 실행 기록, 발행 행 전후 건수 동일). 기존 `HumanReviewIntegrationTest.approvalIsRefusedForFailedMissingStaleMismatchedOrUnexplainedWarnValidation` | demo runner. 서비스 직접 호출 |
| AC-04 미승인 공문군 조회 사용 불가 | 통과(기존 테스트) | Tool: `ToolApiIntegrationTest.unusableStatesReturnReasonsOnlyWithoutItemsOrRuleIds`(`HUMAN_REVIEW_PENDING`, 항목과 규칙 ID 없음). Core 조회: `InternalPolicyApplicableIntegrationTest.validationResultDrivesChecklistStatusWithoutAllowingUse`(`PENDING_REVIEW`, 사용 허용 false). 새 테스트는 두 공문군을 모두 승인하므로 이 조건을 다루지 않고, 대신 승인 전 FIXTURE 기간(`FIXTURE_CHECKLIST_NOT_APPROVED`)을 Tool과 Core 조회에서 확인한다 | 서비스 직접 호출로 발행 뒤 HTTP |
| AC-04 거부 시 결과·checklist가 생기지 않음 | 통과 | 새 테스트의 거부 1, 4, 5에서 사람 결정, HUMAN_REVIEW checklist version과 항목, 일정 revision과 entry의 전후 건수 동일. 거부 2는 `validation_run`과 결과 0건, 거부 3은 변경안과 항목 0건. 저장 도중 실패는 기존 `HumanReviewIntegrationTest.failureInsideApprovalTransactionLeavesNoPartialRows`, `ProposalValidationIntegrationTest.failureWhileSavingIssuesRollsBackResultAndIssues` | demo runner. 서비스 직접 호출 |
| AC-05 | 통과 | `CoreEndToEndFlowIntegrationTest.readmeDemoSettingKeysAllExistInTheCode`(README 키 16개, 누락 0) | 파일 대조 |
| AC-06 | 통과 | 기존 Java 153건 유지 + 신규 5건 = 158건, Python 65건. diff는 테스트, evidence, README뿐 | 로컬, CI, diff |
| AC-07 | 통과 | evidence의 단계별 실제 출력, 거부 사례 5건, "전체 MVP 완료가 아니다" 명시 | diff 검토 |

- 결함 보고: 업무 코드 결함 없음. 테스트 쪽 수정 2건(빌더 속성 우선순위 → 명령행 인자, 실행 기록 열 이름)과 관찰 2건(셀러론 조회의 `APPROVED_CHECKLIST_NOTICE_MISMATCH` 동반, README의 선택 키 생략)은 evidence에 기록했다.

### AI self-review

- 수행한 검사: 완료 확인 조건 7개와 테스트 대조, 기존 테스트 범위 대조(재작성 여부), 업무 코드 변경 여부(diff), 권한 우회 여부(Tool 토큰은 테스트 임시값, 서비스 토큰 1개 범위 그대로), 문서와 코드 불일치(README 설정 키 대조, 상태 문구).
- 발견과 수정: (1) 최초 구현의 evidence가 "`human_review_run` FAILED 1행"을 적었으나 테스트가 확인하지 않았다 → 거부 1의 실행 기록 확인을 추가했다. (2) 거부 시 발행 행 부재 확인이 없었다 → 거부 1, 4, 5에 결정·version·항목·일정 revision·entry 전후 건수 확인을 추가했다. (3) AC-04의 "미승인 공문군 조회 사용 불가"는 새 테스트 범위 밖이었다 → 기존 테스트 2건으로 연결하고 FIXTURE 기간 사례의 차단 사유 확인을 추가했다. (4) 계획의 "없는 변경안 검증"은 구현에서 "없는 변경안 승인"으로 바뀌어 있었다 → 검증 쪽은 기존 테스트로 연결하고 대체 사실을 기록했다. (5) evidence에 검증 대상 commit, CI 링크, Python 건너뜀 테스트명과 사유가 없었다 → 추가했다.
- 미해결과 위험: 서비스 직접 호출로 보장되는 조건은 demo runner 경로로 다시 쓰지 않았다(runner는 같은 서비스를 호출한다). README 명령 4개를 `gradlew bootRun`으로 실제 실행하는 검증은 이번 범위에서 제외됐다(사용자 결정). 동시 실행, 성능, 사용자별 권한, AI 서비스와 화면은 범위 밖이다. 루트 README 6행과 AGENTS.md 18행의 "Core Tool API 미구현" 문구는 TASK-008 완료와 어긋나며 별도 보완 항목이다(이 Task에서 고치지 않음).

### 인간 검수와 Explainability Gate

- REVIEW_CHECKLIST 적용 / 검수자 / 검수 대상 revision / 결과: [검수 체크리스트](../development/REVIEW_CHECKLIST.md) / 사용자 / PR #36(코드 commit `a3f2def`, `6430930`), PR #35(이 문서) / **통과**
- 인간이 확인한 내용: 검수 보완 항목 5건(미승인 공문군 차단의 기존 테스트 연결, 없는 변경안 검증·승인 거부의 구분, 거부 시 발행 행 미생성 확인, 검증 대상 commit·CI·Python 건너뜀 사유, 문서 불일치 정리)이 승인 범위에 맞는지 확인했다. 자동 검증 PASS와 사람 승인이 구분되고, 승인 뒤 일반 적용 공문 조회와 AI용 Tool이 같은 승인 checklist(version ID와 결정 ID)를 가리키는 흐름을 이해했다. 수정 시 새 revision과 재검증이 필요하며, 설명 문구의 의미는 자동 검증이 보장하지 않으므로 사람이 원문과 대조해야 한다는 한계를 확인했다.
- Explainability Gate: **통과**(결정자 사용자).
- 검수 자료: [검증 기록](../evidence/CORE_END_TO_END_FLOW_EVIDENCE.md)의 단계별 실제 출력, 거부 사례, 기존 테스트 연결 표.

### 결정 기록과 완료

- 최종 결정 / 결정자 / 승인 범위 / 검토 대상 PR: **완료** / 사용자 / 화면 전 Core 전체 흐름 연결 검증(demo 명령 진입점 → 변경안 생성 → 자동 검증 → 사람 결정 → 승인 checklist 발행 → 적용 공문 조회와 Tool 조회, importer 권한 유지, README 설정 키 대조, 결함 보고) / PR #35(계획·판단·결과), PR #36(테스트·evidence·README)
- Acceptance Criteria 충족 / evidence / PR: AC-01~AC-07 전부 통과(AC-04의 미승인 공문군 차단과 없는 변경안 검증은 기존 테스트로 보장). evidence `docs/evidence/CORE_END_TO_END_FLOW_EVIDENCE.md`. PR #36.
- 확정한 동작(사용자 결정): 거부된 요청은 실패 이유를 실행 기록에 남기고 사람 결정, 승인 checklist, 적용 일정을 만들지 않는다. 실패 조회 화면, 재처리, 자동 재시도는 이번 범위에 추가하지 않는다.
- 이 완료는 **화면 전 Core 전체 흐름 연결 검증의 완료**이며, 근거 검색, AI 서비스(FastAPI), 화면, 실제 사용자별 인증·권한, 전체 MVP의 완료가 아니다. 이들은 PLAN-002의 후속 Task(TASK-011b, 014~019)에서 별도 승인 뒤 진행한다.
- 잔여 위험 / 후속 항목:
  - README의 demo 명령 4개를 `gradlew bootRun`으로 실제 실행하는 검증은 범위에서 제외됐다(사용자 결정). 테스트는 같은 명령행 인자로 Spring 컨텍스트를 띄워 runner를 실행한다.
  - 서비스 직접 호출로 보장되는 거부 조건은 demo runner 경로로 다시 쓰지 않았다(같은 서비스 호출).
  - 동시 실행, 성능, 실제 DB 장애 시 동작은 검증하지 않았다.
  - 루트 README 6행과 AGENTS.md 18행의 "Core Tool API 미구현" 문구는 TASK-008 완료와 어긋나며 별도 보완 항목이다. PLAN-001의 TASK-011 행 갱신은 PLAN-001을 고치는 PR #32·#33 병합 뒤 별도로 반영한다.
