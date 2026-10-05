# 사람 검토 결정과 승인 checklist 발행 검증 기록 (TASK-007)

## 구현 범위

검수자가 자동 검증을 마친 변경안에 대해 **승인(APPROVE), 수정(MODIFY), 반려(REJECT)** 를 기록한다. 승인은 결정 기록, HUMAN_REVIEW 출처의 승인 checklist, 적용 일정 revision을 한 트랜잭션으로 발행하고, 적용 공문 조회는 사람 결정이 있는 checklist만 사용 허용 후보로 본다. 검수자 ID는 합성 값이며 인증, 화면, 승인 철회, 정기 재검증, HTTP 노출은 범위 밖이다.

- DB 구조 변경 파일 V8: `human_review_decision`(변경안당 1건 UNIQUE, 결정 종류별 필수·금지 컬럼 CHECK, 승인은 version과 일정 revision UNIQUE 참조), `human_review_run`. 두 테이블 append-only 가드와 보호 목록 등록(애플리케이션 테이블 33 → 35, 보호 trigger 66 → 70, migration 7 → 8).
- `HumanReviewService`: 승인 전 검사(최신 결과 PASS/WARN, WARN 사유 필수, 결정 시점 기준 검증 유효 기간, 변경안 해시 일치, 대체·결정 여부, 공문 미철회, 중복 승인 없음, 기준 checklist 최신성). 승인 checklist 항목은 기준 항목 순서를 유지하며 수정은 자리에서 바꾸고 삭제는 빼고 추가는 뒤에 붙인다. 일정 revision은 현재 leaf를 잇고 직전 구간 종료일을 새 시행일로 제한한 뒤 새 구간을 붙인다. 수정은 생성기(`human-revision-v1`)로 새 revision을 만들고, 반려는 결정만 남긴다. 결정 입력에 `knownAt`이 없어 과거 시각 결정이 불가능하다.
- 적용 공문 조회: 사용 허용 정책 입력을 실제 값으로 연결했다. 검증 유효 = 사람 승인 결정 존재(승인 시점에 확인), 공개 근거 확인 = 선택 공문의 필수 참조가 조회 시점에 확인됨(참조 없으면 해당 없음), 의미 일치 = checklist의 공문 = 선택 공문(조회 자체가 보장). 새 차단 사유 `FIXTURE_CHECKLIST_NOT_APPROVED`, `HUMAN_DECISION_MISSING`, `PUBLIC_EVIDENCE_UNCONFIRMED`, `PROPOSAL_REJECTED`(상태 `UNAVAILABLE`), `APPROVED_CHECKLIST_NOTICE_MISMATCH`, 경고 `INFORMATIONAL_PUBLIC_EVIDENCE_UNCONFIRMED`. 기존 placeholder 사유 `CURRENT_VALIDATION_NOT_EVALUATED`, `CURRENT_PUBLIC_EVIDENCE_NOT_EVALUATED`는 제거. 응답 `approvedChecklist`에 `origin`, `decisionId` 추가(최상위 필드 변화 없음).
- 운영 기동 거부: `trust-agent.human-review.enabled`를 demo 전용 설정에 추가. importer 계열 역할은 결정 테이블에 쓸 수 없다.

## 테스트 evidence

검증 대상 revision: 브랜치 `feat/human-review-decision` HEAD(PR 본문의 commit). 환경: 로컬 macOS arm64(Darwin 27.0), Java 21(openjdk 21.0.8), Docker, PostgreSQL 18.6 Testcontainers, Python 3.11.8.

```text
./gradlew clean test bootJar --offline --no-daemon
142 tests completed (기존 130 + 신규 12), failures 0, errors 0, skipped 0
executable jar 생성

# 비공개 artifact를 제공한 로컬
TRUSTAGENT_PRIVATE_ARTIFACT_ROOT=<비공개 artifact 경로> TRUSTAGENT_REQUIRE_PRIVATE_SNAPSHOTS=1 python3 -m unittest discover -s tests -t .
Ran 62 tests, 통과 62, 실패 0, 건너뜀 0

# 공개 CI 조건(비공개 artifact 없음)
python3 -m unittest discover -s tests -t .
Ran 62 tests, 통과 60, 실패 0, 건너뜀 2 (사유: "비공개 snapshot artifact가 제공되지 않았습니다.")
```

Java 신규 12 = 결정 통합 7(`HumanReviewIntegrationTest`) + 승인 뒤 조회 3(`HumanReviewApplicableIntegrationTest`) + 반려 조회 1(`InternalPolicyApplicableIntegrationTest` 19 → 20) + 운영 기동 거부 1(`DemoFeatureProductionGuardTest` 6 → 7). 공개 CI의 실제 실행 결과는 PR checks에서 확인한다.

완료 확인 조건(TASK-007 AC) 대응:

| 테스트 | 보장 | AC |
|---|---|---|
| `approvingPassProposalIssuesChecklistAndScheduleRevision` | 결정 1행(APPROVE, 검수자, 변경안, 결과, 해시, 결정 시각 = 서비스 시계), HUMAN_REVIEW checklist(공문 v2) 항목 3개(`CHECK_PREPAYMENT_FEE_RATE` 0.8, `CHECK_NOTICE_SOURCE`, `CHECK_CUSTOMER_CONTRACT_DATE` true, 규칙 version ID 연결), 일정 revision이 fixture 일정을 잇고 구간 `[2026-09-15, 2026-10-01)` + `[2026-10-01, 없음)`, 실행 기록 SUCCEEDED, 변경안·검증 결과 불변 | AC-01, 11 |
| `approvalIsRefusedForFailedMissingStaleMismatchedOrUnexplainedWarnValidation` | `VALIDATION_FAILED`, `VALIDATION_MISSING`, `VALIDATION_STALE`(24h+1초), `VALIDATION_RESULT_MISMATCH`, `PROPOSAL_HASH_MISMATCH`, `REASON_REQUIRED`(WARN) 각각 거부되고 결정·checklist 0건, 실패 실행 기록만. 사유 있는 WARN은 승인 | AC-05, 06 |
| `modifyCreatesRevisionThatNeedsRevalidationBeforeApproval` | 수정은 사유 필수, 새 revision(supersedes, 사유, `human-revision-v1`)만 생성, checklist·일정 없음. 원래 변경안은 `PROPOSAL_ALREADY_DECIDED`, 새 revision은 `VALIDATION_MISSING`. 문구를 고친 revision은 TASK-006 V-01에 걸려 FAIL이라 `VALIDATION_FAILED`. 공문 규칙과 같은 내용의 재수정은 PASS 뒤 승인 가능 | AC-07 |
| `rejectLeavesOnlyTheDecisionAndBlocksFurtherDecisions` | 사유 필수, 결정 1행만, 이후 승인·수정은 `PROPOSAL_REJECTED` | AC-08 |
| `concurrentApprovalsOfTheSameProposalLeaveExactlyOneDecision` | 같은 변경안 동시 승인 2건 중 1건만 성공, 결정 1행, 일정 revision 1건. DB: 두 번째 결정 23505, 같은 leaf를 잇는 두 번째 revision 23505 | AC-09 |
| `failureInsideApprovalTransactionLeavesNoPartialRows` | checklist·일정·결정 저장 뒤 유도한 실패로 전부 취소, HUMAN_REVIEW version·항목·revision·결정 0건, 실패 실행 기록 `REVIEW_WRITE_FAILED` | AC-10 |
| `decisionTablesAreAppendOnlyAndImportersCannotWriteThem` | UPDATE/DELETE 42501, importer 두 역할 INSERT 불가, runtime INSERT 가능·UPDATE 불가 | AC-12 |
| `approvedChecklistIsUsableForTheNoticePeriodWithDecisionIdAndOrigin` | 승인 뒤 2026-10-01 조회 `AVAILABLE`, 사용 허용 true, 차단 사유 없음, `origin=HUMAN_REVIEW`, `decisionId`. 2026-09-30은 FIXTURE라 false(`FIXTURE_CHECKLIST_NOT_APPROVED`). 승인 1초 전 기준 시각은 `PENDING_REVIEW`, 같은 시각은 보이지만 과거 조회라 false | AC-02, 03, 04 |
| `approvalSurvivesValidationAgeButNotRequiredPublicEvidenceGoingStale` | 검증 25시간 뒤에도 true(기간 경과로 자동 만료 없음). 공개 관측 30일 초과 시 셀러론은 `PUBLIC_EVIDENCE_UNCONFIRMED`로 false, 참조 없는 중도상환수수료는 true | AC-13(a)(b) |
| `laterNoticeEventsBlockOnlyWhenSelectionActuallyChanges` | 미래 시행 v3 수신만으로는 계속 true. 끊긴 chain v4 수신으로 선택 모호 → `AMBIGUOUS_EFFECTIVE_NOTICE`. v3 시행 구간 조회는 v3 선택 + `APPROVED_CHECKLIST_NOTICE_MISMATCH`. 셀러론 철회 뒤 `WITHDRAWN`(이전 업무일 포함), 철회 전 기준 시각은 선택됐으나 과거 조회 | AC-13(c)(d) |
| `rejectedProposalStaysRejectedEvenThoughAPassResultExists` | PASS 결과가 있어도 반려 시각부터 `UNAVAILABLE` + `PROPOSAL_REJECTED`, 두 ID null. 반려 1초 전은 `PENDING_REVIEW`. 이후 새 revision은 그 revision 기준 `PENDING_VALIDATION` | AC-08 |
| `humanReviewAloneRefusesProductionStartup` 등 | 결정 설정 단독, 넷 모두, 모두 꺼짐 | AC-12 |
| schema 테스트 갱신 | migration 8, 테이블 35, trigger 70, schema version 8 | AC-14 |

### 기존 테스트 변경

- `InternalPolicyApplicableIntegrationTest`: placeholder 사유 3곳을 `HUMAN_DECISION_MISSING`(수동 셀러론 HUMAN_REVIEW 자료, 결정 없음) 2곳과 `FIXTURE_CHECKLIST_NOT_APPROVED` 1곳으로 교체. 반려 자료(PROPOSAL_1을 2026-10-04T19:30:00Z에 반려) 추가에 따라 PASS 조회 기준 시각 19:59:59 → 19:29:59(3곳, 보이는 결과 동일). 반려 조회 테스트 1건 추가.
- `CoreApplicationIntegrationTest`, `PublicProductSchemaIntegrationTest`, `RuntimeDatasourcePropertiesTest`, `DemoFeatureProductionGuardTest`: schema version 8과 수치.

### 구현 중 고친 것 (업무 로직 결함 아님)

1. 승인 checklist 항목의 `structured_change`가 JSON null인 항목을 DB NULL로 넣어 NOT NULL 위반 → JSON `null`로 저장.
2. 테스트 설계 오류 4건: 권한 조회 문자열 형식, fixture family 2개에 따른 항목 수, 해시 불일치 테스트의 결과 시각이 결정 시각보다 뒤였던 점, 두 번째 root 공문 금지 제약에 걸린 모호성 자료(끊긴 chain으로 변경).
3. 철회 기대값: 철회는 공문 단위 사건이라 이전 업무일 조회도 `WITHDRAWN`이다. 기존 정책대로 기대값을 고쳤다.
4. `APPROVED_CHECKLIST_NOTICE_MISMATCH` 판정을 추출 단계 앞으로 옮겨 구조화 결과가 없는 새 공문에도 사유가 붙게 했다.

## 한계

- CLI 실행 전용, 합성 검수자 ID. 인증·권한 체계, 화면, 알림, 승인 철회, 정기 재검증은 미구현.
- TASK-006 V-01이 변경안 항목 전체를 공문 규칙과 비교하므로 검수자가 문구를 고친 revision은 승인할 수 없다. 완화 여부는 사용자 결정 사항.
- 공개 근거 확인은 조회 시점의 공개 상품 관측 상태 조회를 그대로 쓰며, 과거 조회에서는 확인 결과를 false로 둔다(과거 조회는 이미 차단).
- 반려 표시는 새 상태값 없이 `UNAVAILABLE` + `PROPOSAL_REJECTED`다.
- 인간 검수와 완료 판정은 미기록이다.
