# Core 전체 흐름 연결 검증 기록 (TASK-011a, 화면 전)

## 범위

합성 공문 적재(importer 진입점) → 테스트용 checklist 적재 → 변경안 생성 → 자동 검증 → 사람 결정(승인, 수정 → 재검증 → 사유 승인) → 적용 공문 조회 → AI용 Tool 조회를 **core README의 demo 설정 키와 ApplicationRunner**로 순서대로 실행해 확인했다. 업무 코드는 바꾸지 않았다(diff는 테스트 1클래스, 이 문서, README뿐). **전체 MVP 완료가 아니다.** AI 서비스, 화면, 사용자별 권한, 성능은 범위 밖이다.

기존 테스트가 이미 덮는 범위(서비스 객체 직접 호출)는 재작성하지 않았고, 빠진 연결 세 가지만 추가했다. (1) demo 명령 진입점 경유, (2) 수정 → 재검증 → 승인 뒤 조회·Tool 응답, (3) 한 흐름의 끝까지 기록.

## 실행 방식

테스트 `CoreEndToEndFlowIntegrationTest`(격리 DB 1개). 각 명령은 README의 `bootRun --args='--spring.main.web-application-type=none --trust-agent.…'`와 같은 조건으로, web 없는 Spring 컨텍스트를 **명령행 인자**로 띄워 runner가 1회 실행되게 했다. 조회는 web 컨텍스트(임의 포트)에서 HTTP로 했다. 시계는 고정(명령 2026-10-05T04:00Z·04:30Z, 조회 05:00Z). 적재는 importer 진입점(`BaselineImporter`, `SyntheticInternalImporter`)을 그대로 썼다(사용자 판단: importer 권한 유지).

## 단계별 입력과 실제 출력 (테스트 실행 출력)

| 순서 | 명령(설정 키) | 실제 결과 |
|---|---|---|
| 0 | migration만(명령 없음) → importer 2종 | schema version 9, 공개 상품·합성 공문 적재 |
| 거부 1 | `human-review.enabled` + 없는 변경안 ID, `decision=APPROVE`, `.run-id=review-run:6666…` | 기동 실패 `PROPOSAL_NOT_FOUND`, `human_review_run` `review-run:6666… | FAILED | PROPOSAL_NOT_FOUND`, 발행 행 전후 건수 동일(아래 "거부 시 발행 행 확인") |
| 거부 2 | `proposal-validation.enabled`만(proposal-id 없음) | 기동 실패 "demo 설정 trust-agent.proposal-validation.proposal-id가 필요합니다." runner가 서비스 호출 전에 거부하므로 `validation_run` 0건, `automated_validation_result` 0건 |
| 거부 3 | `proposal-generation.*`(예시 checklist 적재 전) | 기동 실패 `NO_BASE_CHECKLIST`, `proposal_generation_run` `proposal-run:9999… | FAILED | NO_BASE_CHECKLIST`, 변경안 0건, 변경안 항목 0건, 발행 행 전부 0건 |
| 1 | `fixture-approved-checklist.enabled`, `.root`, `.run-id` | `checklist-fixture-run:1111… | SUCCEEDED`, FIXTURE version 2개 |
| 2 | `proposal-generation.enabled`, `.family-id=SIN-PREPAYMENT-FEE`, `.target-notice-id=SIN-PREPAYMENT-FEE-V2`, `.run-id` | `proposal-run:1111… | SUCCEEDED | checklist-proposal:sha256:59ad46b8…`, 항목 2개 |
| 거부 4 | 검증 전 `human-review` APPROVE | `review-run:8888… | FAILED | VALIDATION_MISSING`, 발행 행 전후 건수 동일 |
| 3 | `proposal-validation.enabled`, `.proposal-id`, `.run-id` | `validation-run:1111… | SUCCEEDED | validation:1111…`, 상태 PASS |
| 4 | `human-review.enabled`, `.proposal-id`, `.validation-result-id`, `.decision=APPROVE`, `.reviewer-id`, `.run-id` | `review-run:1111… | SUCCEEDED | review-decision:1111…`, 승인 checklist `approved-checklist:89043a58…`(origin HUMAN_REVIEW), 항목 `0:CHECK_PREPAYMENT_FEE_RATE, 1:CHECK_NOTICE_SOURCE, 2:CHECK_CUSTOMER_CONTRACT_DATE` |
| 5 | 셀러론 `proposal-generation.*` → `human-review` `decision=MODIFY`, `.reason`, `.revised-rules-json`(CHECK_SETTLEMENT_EVIDENCE 문구만 보완) | 새 revision `checklist-proposal:sha256:49c7c33d…`(supersedes 원 변경안) |
| 6 | revision `proposal-validation.*` | `validation:2222…` WARN, issue `WARN:INSTRUCTION_EDITED, INFO:PUBLIC_CROSS_CHECK_NOT_APPLICABLE, INFO:PUBLIC_FACT_MATCH` |
| 거부 5 | 사유 없는 APPROVE, `.run-id=review-run:7777…` | `review-run:7777… | FAILED | REASON_REQUIRED`, 발행 행 전후 건수 동일 |
| 7 | 사유 있는 APPROVE | 셀러론 승인 checklist `approved-checklist:46c217aa…`. 거부 5 시점 대비 사람 결정 +1, HUMAN_REVIEW version +1, 일정 revision +1, 일정 entry 증가 |
| 8 | web 컨텍스트 조회 `GET …/SIN-PREPAYMENT-FEE/applicable?businessDate=2026-10-01` | `internalChecklistUseAllowed=true`, `AVAILABLE`, version `89043a58…`, decision `review-decision:1111…` |
| 9 | Tool `applicable_checklist`(같은 공문군) | `usable=true`, 같은 version·decision, 항목 3개. `rule_evidence`로 항목 0 근거 제공 |
| 10 | 셀러론 조회와 Tool | 사용 허용 true, version `46c217aa…`, 항목 문구에 "매출 정산 내역 확인 여부를 상담 준비 체크리스트에 기록한다. (검수자 보완 문구)" |
| 11 | 승인 전 기간(2026-09-30, 테스트용 FIXTURE checklist만 있는 업무일) Tool과 Core 조회 | Tool `usable=false`, `approvedChecklist=null`, 사유 `FIXTURE_CHECKLIST_NOT_APPROVED`. Core 조회 사용 허용 false, checklist origin `FIXTURE`. 이 사례는 "승인 전 업무일" 차단이며 "미승인 공문군" 차단이 아니다(아래 "기존 테스트로 보장하는 조건") |
| README 대조 | README의 `--trust-agent.*` 키 16개 | 전부 코드(main Java·yml)에 존재, 누락 0 |

### 거부 시 발행 행 확인

거부 1, 4, 5의 직전과 직후에 다음 다섯 표의 건수를 비교해 같음을 확인했다. 사람 결정(`human_review_decision`), HUMAN_REVIEW 출처 checklist version(`approved_checklist_version`)과 그 항목(`approved_checklist_item`), 일정 revision(`approved_checklist_schedule_revision`)과 entry(`approved_checklist_schedule_entry`). 거부 2는 `validation_run`과 `automated_validation_result` 0건, 거부 3은 `checklist_change_proposal`과 그 항목 0건을 확인했다. 실패 실행 기록은 거부 1, 3, 4, 5에서 run ID로 상태와 오류 코드를 확인했다. 거부 2는 runner가 서비스를 부르기 전에 멈추므로 실행 기록이 생기지 않는 것이 정의된 동작이다.

### 기존 테스트로 보장하는 조건 (재작성하지 않음)

완료 확인 조건 AC-04 가운데 새 테스트가 다루지 않는 조건은 기존 테스트로 연결한다. "서비스 직접 호출"은 테스트가 서비스 객체를 직접 부르는 방식이고, "demo runner"는 명령 진입점을 타는 방식이다. 두 방식은 같은 서비스를 호출한다.

| 조건 | 보장 테스트 | 경로 |
|---|---|---|
| 없는 변경안 **검증** 거부 `PROPOSAL_NOT_FOUND`와 `validation_run` FAILED 기록 | `ProposalValidationIntegrationTest.revalidationAppendsNewResultAndRunIdConflictIsRejected` | 서비스 직접 호출 |
| 미승인 공문군(변경안과 PASS 검증은 있고 사람 결정 없음) Tool 조회 사용 불가, 항목과 규칙 ID 미제공 | `ToolApiIntegrationTest.unusableStatesReturnReasonsOnlyWithoutItemsOrRuleIds`(`HUMAN_REVIEW_PENDING`) | 서비스 직접 호출로 발행 뒤 HTTP |
| 미승인 공문군 Core 적용 공문 조회 사용 불가 | `InternalPolicyApplicableIntegrationTest.validationResultDrivesChecklistStatusWithoutAllowingUse`(`PENDING_REVIEW`) | HTTP |
| 승인 거부 코드별 결정 행 미생성(`VALIDATION_FAILED`, `VALIDATION_MISSING`, `VALIDATION_STALE`, `VALIDATION_RESULT_MISMATCH`, `PROPOSAL_HASH_MISMATCH`, `REASON_REQUIRED`, `PROPOSAL_REJECTED`) | `HumanReviewIntegrationTest.approvalIsRefusedForFailedMissingStaleMismatchedOrUnexplainedWarnValidation`, `rejectLeavesOnlyTheDecisionAndBlocksFurtherDecisions` | 서비스 직접 호출 |
| 저장 도중 실패 시 결정, version, 항목, 일정 revision 모두 없음 | `HumanReviewIntegrationTest.failureInsideApprovalTransactionLeavesNoPartialRows` | 서비스 직접 호출 |
| 검증 저장 도중 실패 시 결과와 issue 없음 | `ProposalValidationIntegrationTest.failureWhileSavingIssuesRollsBackResultAndIssues`, `inconsistentOutcomeIsRejectedBeforeAnythingIsSaved` | 서비스 직접 호출 |

새 테스트는 거부 1(없는 변경안 **승인**)을 demo runner로 확인한다. 계획의 "없는 변경안 검증"은 위 첫 행의 기존 테스트로 보장되며, 새 테스트에서 승인 쪽으로 바꿔 확인한 사실을 Task에 기록했다. 새 테스트는 두 공문군을 모두 승인하므로 미승인 공문군 차단은 다루지 않는다.

## 테스트 evidence

환경(두 실행 공통): 로컬 macOS arm64, Java 21, Docker, PostgreSQL 18.6 Testcontainers(고정 digest), Python 3.11.8.

### 최초 구현 (commit `a3f2def`, PR #36)

검증 대상 revision: 브랜치 `test/core-end-to-end-flow` commit `a3f2def`. 이 commit의 테스트는 거부 사례에서 오류 코드와 일부 실행 기록만 확인했고, 발행 행 전후 건수와 거부 1의 실행 기록은 확인하지 않았다(아래 보완에서 추가).

```text
./gradlew clean test bootJar --offline --no-daemon
158 tests completed (기존 153 + 신규 5), failures 0, errors 0, skipped 0

python3 -m unittest discover -s tests -t .   (비공개 artifact 제공: 65 통과, 건너뜀 0 / 공개 CI 조건: 65 실행, 63 통과, 건너뜀 2)
```

공개 CI: https://github.com/hj1016/trust-agent/actions/runs/37469690756 (commit `a3f2def`, Gradle tests 성공, Python contracts 성공).

### 검수 보완 (거부 사례 확인 추가, 기존 테스트 연결)

검증 대상 코드 revision: 브랜치 `test/core-end-to-end-flow` commit `6430930`(테스트 1클래스만 변경, 확인문 추가, 테스트 수 5건 그대로). 아래 결과는 이 commit의 코드를 로컬에서 실행한 것이다. 이 문서의 갱신은 별도 문서 commit이며 코드 검증 대상이 아니다. 기대값과 완료 확인 조건은 바꾸지 않았다.

공개 CI: 문서 commit push 뒤 실행 링크와 결과를 아래 "공개 CI 결과"에 기록한다.

```text
./gradlew test --tests 'com.trustagent.core.CoreEndToEndFlowIntegrationTest' --offline --no-daemon
5 tests, failures 0, errors 0, skipped 0

./gradlew clean test bootJar --offline --no-daemon
158 tests (기존 153 + 신규 5), failures 0, errors 0, skipped 0. executable jar 생성

python3 -m unittest discover -s tests -t .
Ran 65 tests, OK (skipped=2)   ← 공개 CI 조건(비공개 artifact 없음)

TRUSTAGENT_REQUIRE_PRIVATE_SNAPSHOTS=1 TRUSTAGENT_PRIVATE_ARTIFACT_ROOT=<비공개 artifact 폴더> python3 -m unittest discover -s tests -t .
Ran 65 tests, OK (skipped=0)
```

Python 건너뜀 2건(공개 CI 조건)의 테스트명과 사유:

| 테스트 | 사유 |
|---|---|
| `tests/contract/test_dataset_contracts.py` `PublicSnapshotContractTest.test_private_artifacts_match_manifest_hashes` | "비공개 snapshot artifact가 제공되지 않았습니다." 공개 CI는 `TRUSTAGENT_REQUIRE_PRIVATE_SNAPSHOTS` 없이 실행하며 비공개 raw HTML artifact를 저장소에 두지 않는다 |
| `tests/contract/test_public_product_versions.py` `PublicProductPipelineContractTest.test_private_artifacts_reextract_to_committed_golden_files` | 같은 사유 |

비공개 artifact는 git worktree에 없어 `TRUSTAGENT_PRIVATE_ARTIFACT_ROOT`로 메인 체크아웃의 `.private-artifacts` 폴더를 지정해 실행했다. 지정 없이 `TRUSTAGENT_REQUIRE_PRIVATE_SNAPSHOTS=1`만 주면 같은 두 테스트가 "artifact 없음"으로 실패한다(환경 문제이며 코드 결함이 아니다).

| 테스트 | 보장 | AC |
|---|---|---|
| `wrongOrderAndMissingSettingsAreRefusedBeforeAnythingIsIssued` | 없는 변경안 승인(`PROPOSAL_NOT_FOUND` 실패 실행 기록), 필수 설정 누락(실행 기록과 결과 없음), 예시 checklist 없는 생성(`NO_BASE_CHECKLIST` 실패 기록)이 각각 거부되고 결정·checklist·일정 행이 전부 0건 | AC-04 |
| `demoCommandsRunTheCoreFlowInOrderAndLeaveRunRecords` | 예시 적재 → 생성 → (검증 전 승인 거부: 실패 기록, 발행 행 전후 동일) → 검증 PASS → 승인 발행이 demo 설정 키와 runner로 순서대로 실행되고 단계별 실행 기록과 결과 ID | AC-01, 04 |
| `modifyRevalidateAndApproveThroughDemoCommands` | `revised-rules-json` 수정 → 재검증 WARN `INSTRUCTION_EDITED` → 사유 없는 승인 거부(실패 기록, 발행 행 전후 동일) → 사유 승인 발행(결정·version·일정 revision 각 +1) | AC-03, 04 |
| `applicableQueryAndToolReturnTheIssuedChecklistsAndEditedInstruction` | 조회와 Tool이 같은 version·결정 ID와 항목 3개, 근거 Tool 제공, 셀러론 항목에 고친 문구, 승인 전 FIXTURE 기간은 Tool(`FIXTURE_CHECKLIST_NOT_APPROVED`)과 Core 조회 모두 사용 불가 | AC-02, 03, 04 |
| `readmeDemoSettingKeysAllExistInTheCode` | README의 demo 설정 키 16개가 코드에 전부 존재 | AC-05 |

AC-04의 "미승인 공문군 조회 사용 불가"와 "없는 변경안 검증"은 위 "기존 테스트로 보장하는 조건"의 기존 테스트가 담당한다.

## 결함 보고

업무 코드 결함은 발견하지 않았다. 테스트 작성 중 고친 것은 테스트 쪽 문제 2건이다. (1) `SpringApplicationBuilder.properties()`는 application.yml보다 우선순위가 낮아 Flyway가 켜지지 않았다 → README와 같은 명령행 인자 방식으로 변경(실제 README 명령에는 해당 없음). (2) 실행 기록 테이블의 열 이름(`generation_run_id`)을 잘못 적었다.

관찰(결함 아님): 셀러론 조회에는 테스트용 v1 일정 구간이 v2 날짜를 덮어 `APPROVED_CHECKLIST_NOTICE_MISMATCH` 사유가 함께 붙는다(TASK-007에서 정의한 동작). README는 선택 키(`run-id`, `generator-version`, `validator-version`, `validation-result-id`)를 적지 않는데, 코드 기본값이 있어 명령 실행에는 지장이 없다.

## 한계

- demo runner는 test/demo profile 전용이며 production에서는 기동 거부다(기존 보장).
- AI 서비스, 화면, 사용자별 권한, 동시 실행, 성능은 검증하지 않았다(TASK-011b, PLAN-002 후속).
- 인간 검수와 완료 판정은 미기록이다.
