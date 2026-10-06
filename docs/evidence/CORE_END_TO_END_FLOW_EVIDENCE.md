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
| 거부 1 | `human-review.enabled` + 없는 변경안 ID, `decision=APPROVE` | 기동 실패 `PROPOSAL_NOT_FOUND`, `human_review_run` FAILED 1행 |
| 거부 2 | `proposal-validation.enabled`만(proposal-id 없음) | 기동 실패 "demo 설정 trust-agent.proposal-validation.proposal-id가 필요합니다." |
| 거부 3 | `proposal-generation.*`(예시 checklist 적재 전) | 기동 실패 `NO_BASE_CHECKLIST`, `proposal_generation_run` `proposal-run:9999… | FAILED | NO_BASE_CHECKLIST`, 변경안 0건 |
| 1 | `fixture-approved-checklist.enabled`, `.root`, `.run-id` | `checklist-fixture-run:1111… | SUCCEEDED`, FIXTURE version 2개 |
| 2 | `proposal-generation.enabled`, `.family-id=SIN-PREPAYMENT-FEE`, `.target-notice-id=SIN-PREPAYMENT-FEE-V2`, `.run-id` | `proposal-run:1111… | SUCCEEDED | checklist-proposal:sha256:59ad46b8…`, 항목 2개 |
| 거부 4 | 검증 전 `human-review` APPROVE | `review-run:8888… | FAILED | VALIDATION_MISSING` |
| 3 | `proposal-validation.enabled`, `.proposal-id`, `.run-id` | `validation-run:1111… | SUCCEEDED | validation:1111…`, 상태 PASS |
| 4 | `human-review.enabled`, `.proposal-id`, `.validation-result-id`, `.decision=APPROVE`, `.reviewer-id`, `.run-id` | `review-run:1111… | SUCCEEDED | review-decision:1111…`, 승인 checklist `approved-checklist:89043a58…`(origin HUMAN_REVIEW), 항목 `0:CHECK_PREPAYMENT_FEE_RATE, 1:CHECK_NOTICE_SOURCE, 2:CHECK_CUSTOMER_CONTRACT_DATE` |
| 5 | 셀러론 `proposal-generation.*` → `human-review` `decision=MODIFY`, `.reason`, `.revised-rules-json`(CHECK_SETTLEMENT_EVIDENCE 문구만 보완) | 새 revision `checklist-proposal:sha256:49c7c33d…`(supersedes 원 변경안) |
| 6 | revision `proposal-validation.*` | `validation:2222…` WARN, issue `WARN:INSTRUCTION_EDITED, INFO:PUBLIC_CROSS_CHECK_NOT_APPLICABLE, INFO:PUBLIC_FACT_MATCH` |
| 거부 5 | 사유 없는 APPROVE | `REASON_REQUIRED` |
| 7 | 사유 있는 APPROVE | 셀러론 승인 checklist `approved-checklist:46c217aa…` |
| 8 | web 컨텍스트 조회 `GET …/SIN-PREPAYMENT-FEE/applicable?businessDate=2026-10-01` | `internalChecklistUseAllowed=true`, `AVAILABLE`, version `89043a58…`, decision `review-decision:1111…` |
| 9 | Tool `applicable_checklist`(같은 공문군) | `usable=true`, 같은 version·decision, 항목 3개. `rule_evidence`로 항목 0 근거 제공 |
| 10 | 셀러론 조회와 Tool | 사용 허용 true, version `46c217aa…`, 항목 문구에 "매출 정산 내역 확인 여부를 상담 준비 체크리스트에 기록한다. (검수자 보완 문구)" |
| 11 | 승인 전 기간(2026-09-30) Tool | `usable=false`, `approvedChecklist=null` |
| README 대조 | README의 `--trust-agent.*` 키 16개 | 전부 코드(main Java·yml)에 존재, 누락 0 |

## 테스트 evidence

검증 대상 revision: 브랜치 `test/core-end-to-end-flow` HEAD(PR 본문의 commit). 환경: 로컬 macOS arm64, Java 21(openjdk 21.0.8), Docker, PostgreSQL 18.6 Testcontainers, Python 3.11.8.

```text
./gradlew clean test bootJar --offline --no-daemon
158 tests completed (기존 153 + 신규 5), failures 0, errors 0, skipped 0

python3 -m unittest discover -s tests -t .   (비공개 artifact 제공: 65 통과, 건너뜀 0 / 공개 CI 조건: 65 실행, 63 통과, 건너뜀 2)
```

| 테스트 | 보장 | AC |
|---|---|---|
| `wrongOrderAndMissingSettingsAreRefusedBeforeAnythingIsIssued` | 없는 변경안 승인, 필수 설정 누락, 예시 checklist 없는 생성이 각각 거부되고 실패 기록만 남음 | AC-04 |
| `demoCommandsRunTheCoreFlowInOrderAndLeaveRunRecords` | 예시 적재 → 생성 → (검증 전 승인 거부) → 검증 PASS → 승인 발행이 demo 설정 키와 runner로 순서대로 실행되고 단계별 실행 기록과 결과 ID | AC-01, 04 |
| `modifyRevalidateAndApproveThroughDemoCommands` | `revised-rules-json` 수정 → 재검증 WARN `INSTRUCTION_EDITED` → 사유 없는 승인 거부 → 사유 승인 발행 | AC-03, 04 |
| `applicableQueryAndToolReturnTheIssuedChecklistsAndEditedInstruction` | 조회와 Tool이 같은 version·결정 ID와 항목 3개, 근거 Tool 제공, 셀러론 항목에 고친 문구, 승인 전 기간 사용 불가 | AC-02, 03, 04 |
| `readmeDemoSettingKeysAllExistInTheCode` | README의 demo 설정 키 16개가 코드에 전부 존재 | AC-05 |

## 결함 보고

업무 코드 결함은 발견하지 않았다. 테스트 작성 중 고친 것은 테스트 쪽 문제 2건이다. (1) `SpringApplicationBuilder.properties()`는 application.yml보다 우선순위가 낮아 Flyway가 켜지지 않았다 → README와 같은 명령행 인자 방식으로 변경(실제 README 명령에는 해당 없음). (2) 실행 기록 테이블의 열 이름(`generation_run_id`)을 잘못 적었다.

관찰(결함 아님): 셀러론 조회에는 테스트용 v1 일정 구간이 v2 날짜를 덮어 `APPROVED_CHECKLIST_NOTICE_MISMATCH` 사유가 함께 붙는다(TASK-007에서 정의한 동작). README는 선택 키(`run-id`, `generator-version`, `validator-version`, `validation-result-id`)를 적지 않는데, 코드 기본값이 있어 명령 실행에는 지장이 없다.

## 한계

- demo runner는 test/demo profile 전용이며 production에서는 기동 거부다(기존 보장).
- AI 서비스, 화면, 사용자별 권한, 동시 실행, 성능은 검증하지 않았다(TASK-011b, PLAN-002 후속).
- 인간 검수와 완료 판정은 미기록이다.
