# TASK-005 공문 변경에서 checklist 변경 후보(proposal) 생성

- 상태: **완료.** 구현과 자동 검증 완료, PR #18 병합, 인간 검수와 Explainability Gate 통과(결정자 사용자). 승인 범위는 아래 결정 기록 참조.
- 담당자 / 인간 결정자: AI(계획, 구현, 자동 검증, self-review) / 사용자(범위, AC 확정, 제안 판단, 착수 승인, 검수, Explainability Gate)
- 요구사항 출처: [PLAN-001](PLAN-001_중도상환수수료-흐름-Task-분할-초안.md) TASK-005, [ADR-008](../adr/ADR-008-internal-notice-effective-policy-and-review.md) "체크리스트 변경 후보와 revision", [ADR-003](../adr/ADR-003-validation-and-human-approval.md), README MVP 목표 5단계 전반부. 최종 기획서 대표 시나리오(중도상환수수료율 1.2퍼센트 → 0.8퍼센트).
- 관련 Issue / PR / ADR / 이전 Task: PR #11(기준선), [TASK-001](TASK-001_합성-공문-조회-자산-보존.md), ADR-009(PostgreSQL 유지), 후속 TASK-006(자동 검증), TASK-007(사람 결정)

## Goal / 관련 요구사항

- Goal: 새 공문 version의 구조화 rule과 그 family의 직전 승인 checklist를 비교해, 추가/수정/삭제 항목과 전후 값을 담은 **결정적 proposal**을 만들어 DERIVED record로 저장한다. 공문 담당 부서 검수자가 검토할 변경 후보가 생기고(S3), TASK-006 자동 검증과 TASK-007 사람 결정의 입력이 된다.
- 관련 요구사항: ADR-008 "Proposal은 base checklist version, target notice, rule과 generator version을 참조한다. item은 추가, 수정, 삭제와 before/after 값을 명시한다. DERIVED로 분류한다. MODIFY는 새 revision을 만들고 `supersedes_proposal_id`, before hash, after hash, 수정 사유를 보존한다. 같은 입력과 generator version은 같은 canonical 내용을 생성하고 실행 사건 ID는 별도로 둔다. LLM을 쓰지 않는다."
- 사용자: 공문 담당 부서 검수자(합성). 후속 Task의 validator와 review command.
- 사전조건: PR #11 기준선(합성 공문 v1/v2, `structured_change`, applicable 조회). PostgreSQL V1~V5. **family에 직전 승인 checklist가 존재해야 한다.** 현재 DB에는 승인 checklist가 없으므로 fixture가 필요하다(제안 1, 조건부 채택).
- 입력: `family_id`, `target_notice_id`(예: `SIN-PREPAYMENT-FEE-V2`), `generator_version`(설정값, 예: `proposal-generator-v1`), 실행 `run_id`.
- 업무규칙:
  1. base checklist는 target 공문 `effective_from` 전날에 적용되는 checklist다. family의 최신 visible schedule revision에서 `[effective_from, effective_to)`가 `target.effective_from - 1일`을 포함하는 entry의 checklist version. 없으면 proposal을 만들지 않고 `NO_BASE_CHECKLIST`를 실행 기록에 남긴다.
  2. 비교 단위는 `rule_key`다. target rule에만 있으면 ADD, base item에만 있으면 REMOVE, 둘 다 있고 내용(instruction, evidence_required, structured_change)이 다르면 MODIFY, 같으면 item을 만들지 않는다.
  3. before/after 값은 canonical JSON으로 저장하고 각 hash를 기록한다. 금융 비율은 문자열 십진수 그대로 비교한다.
  4. proposal 내용 identity는 (base checklist version id, target notice id, generator version, canonical items)의 SHA-256이다. 같은 입력은 같은 `proposal_id`를 만든다. 두 번째 생성은 멱등 처리하고 실행 기록만 추가한다.
  5. proposal과 item은 append-only다. 수정은 새 revision(`supersedes_proposal_id`)으로만 한다. 이 Task에서는 테이블과 제약만 만들고 MODIFY 흐름은 TASK-007에서 구현한다.
  6. proposal 생성은 승인이 아니다. `internalChecklistUseAllowed`에 영향을 주지 않는다.
  7. **`origin`은 출처 구분이며 인간 승인 증거가 아니다.** `origin=FIXTURE` checklist는 test/demo 데이터이고, 검증, 사람 결정, 업무 사용 허용 조건을 우회하지 않는다.
  8. 생성 command와 fixture loader는 production profile에서 존재하지 않으며, 두 demo 설정 중 하나라도 production에서 활성화되면 기동을 거부한다(제안 2).
  9. **applicable 조회 응답 계약은 이 Task에서 바꾸지 않는다**(제안 3 보류, TASK-006까지).
- 상태전이: (공문 version 존재, 승인 checklist 존재) → `generate` → proposal record 1건 + items + run record. 금지 전이: proposal UPDATE/DELETE, base checklist 없는 생성, LLM 호출, 승인 상태 변경, fixture로 인한 사용 허용.
- 데이터 영향: 읽기 `internal_notice_version`, `policy_extraction_attempt`, `internal_policy_rule_evidence`, `internal_policy_rule_version`, `approved_checklist_schedule_revision`, `approved_checklist_schedule_entry`, `approved_checklist_version`, (신규) `approved_checklist_item`. 쓰기 (신규) `checklist_change_proposal`, `checklist_change_proposal_item`, `proposal_generation_run`, (fixture) `approved_checklist_version`, `approved_checklist_item`, `approved_checklist_schedule_revision`, `approved_checklist_schedule_entry`, (신규) `approved_checklist_fixture_run`. 불변 조건: R-01 append-only 가드, `proposal_id` 결정성, `supersedes_proposal_id UNIQUE`, item `(proposal_id, item_order)` PK, `change_type IN (ADD, MODIFY, REMOVE)`, ADD는 before NULL, REMOVE는 after NULL, MODIFY는 둘 다 NOT NULL, checklist item `(version_id, rule_key)` UNIQUE, item과 version의 family 일치(복합 FK).
- API: HTTP endpoint 추가 없음. applicable 응답 필드 집합 변경 없음. CLI command(ApplicationRunner)와 조회 Repository만.
- 트랜잭션: proposal + items + run을 하나의 트랜잭션. fixture 적재도 version + items + schedule revision + entries + run을 하나의 트랜잭션. 실패 시 전부 rollback.
- 권한: runtime role에 신규 proposal 3개 테이블 SELECT, INSERT와 `approved_checklist_item` SELECT, INSERT. fixture loader는 runtime role을 쓴다(V4가 runtime에 승인 테이블 INSERT를 허용). synthetic importer와 baseline importer 권한은 늘리지 않는다(R-05). maintenance는 R-02 경로. V2 주석의 보호 테이블 추가 절차 준수.
- 실패 시나리오: base 없음 → `NO_BASE_CHECKLIST` run record(FAILED). 같은 입력 재실행 → 멱등. target 미수신 또는 철회 → `TARGET_NOTICE_NOT_VISIBLE`. 동시 생성 → PK 충돌을 멱등으로 처리. fixture: 같은 id 다른 내용 → `FIXTURE_CONTENT_CONFLICT` 거부, family에 `HUMAN_REVIEW` checklist 존재 → `HUMAN_APPROVAL_EXISTS` 거부. DB 장애 → 실패 run record 별도 트랜잭션 시도 후 원래 오류 보존.
- Out of Scope: 자동 검증(TASK-006), 사람 결정과 MODIFY revision(TASK-007), HTTP 노출, LLM, 화면, 공개 상품 교차 검증 실행, applicable 응답 변경.

## 요구사항과 범위

업무 문제: 공문 v2가 수신되어도 "어떤 checklist 항목이 어떻게 바뀌어야 하는가"를 시스템이 만들어 주지 않는다. 검수자는 공문 원문과 기존 checklist를 직접 대조해야 한다.

포함 범위:
- Flyway **V6**: `approved_checklist_version.origin` 컬럼, `approved_checklist_item`, `approved_checklist_fixture_run`, `checklist_change_proposal`, `checklist_change_proposal_item`, `proposal_generation_run`. 보호 테이블 등록(신규 테이블 5개, 가드 trigger 10개 추가로 60개, `trust_agent_protected_tables()` 30개)과 권한.
- Java: `ChecklistChangeProposalGenerator`(순수 로직), `ProposalRepository`, `ProposalGenerationCommand`(runner), `FixtureApprovedChecklistLoader`(runner), 설정 속성 2개, `ProductionRequiredSettingsConfiguration` 확장.
- 합성 fixture: `SIN-PREPAYMENT-FEE` v1 승인 checklist 1건(item 2개)과 schedule revision 1건(entry `[2026-09-15, null)`).
- 계약: `contracts/checklist-change-proposal.schema.json`(DERIVED), `contracts/synthetic-approved-checklist-fixture.schema.json`(SYNTHETIC_INTERNAL, `origin` const `FIXTURE`), 정답표 `contracts/fixtures/prepayment-fee-v2-proposal.expected.json`.
- 테스트: Java 통합 테스트(PostgreSQL Testcontainers), 컨텍스트 테스트, Python 계약 테스트, 기존 schema 테스트의 보호 테이블 개수 단언 갱신.

제외 범위: 위 Out of Scope.

기존 자산과 기준선 차이: V4의 `approved_checklist_version`에는 **checklist 내용(item) 테이블이 없다.** proposal이 "직전 checklist와의 차이"를 만들려면 내용이 있어야 한다. 제안 1의 배경이다.

## Acceptance Criteria (구현 전 고정)

결정자 / 판단 근거 / 검토 대상 revision 또는 PR: 사용자 / ADR-008, PLAN-001, 제안 1~3 판단 / 이 Task 문서 PR. **AC 확정됨(설계 방향 승인). 구현 착수는 별도 승인.**

### A. proposal 생성

| ID | 입력/상황 | 기대 결과(실패/경계 포함) | 검증 방법 | 결과/evidence |
|---|---|---|---|---|
| AC-01 | v1 fixture checklist(item 2개: `CHECK_PREPAYMENT_FEE_RATE` 1.2퍼센트, `CHECK_NOTICE_SOURCE`)와 schedule `[2026-09-15, null)` 적재, v2 수신 상태에서 `generate(SIN-PREPAYMENT-FEE, SIN-PREPAYMENT-FEE-V2)` | proposal 1건. item 2개: `CHECK_PREPAYMENT_FEE_RATE` MODIFY(before `after_value` "1.2" → after "0.8", `effective_on` 2026-10-01, 조건 2개), `CHECK_CUSTOMER_CONTRACT_DATE` ADD. `CHECK_NOTICE_SOURCE` item 없음. base = v1 checklist version, target = V2 | 통합 테스트 + 정답표 canonical 비교 | **통과** |
| AC-02 | AC-01 두 번 실행 | `proposal_id` 동일, proposal/item 행 수 불변, `proposal_generation_run` 2건 | 통합 테스트 | **통과** |
| AC-03 | 승인 checklist 없는 family(`SIN-SELLER-CHECKLIST`) | proposal 0건. run `FAILED`, `error_code=NO_BASE_CHECKLIST` | 통합 테스트 | **통과** |
| AC-04 | 수신되지 않은(`knownAt` 밖) 또는 철회된 target | proposal 0건, `TARGET_NOTICE_NOT_VISIBLE` | 통합 테스트 | **통과** |
| AC-05 | item 제약 | ADD인데 before 있음, REMOVE인데 after 있음, MODIFY인데 한쪽 없음, 알 수 없는 change_type → 23514 | 통합 테스트 | **통과** |
| AC-06 | `supersedes_proposal_id` | 동시 supersede 두 번째는 23505. 다른 family proposal supersede는 FK 위반 | 통합 테스트 | **통과** |
| AC-07 | 금융 비율 | before/after JSON에 "1.2", "0.8" 문자열. canonical hash가 정답표와 일치. 부동소수점 거부 | 단위 + 통합 | **통과** |

### B. fixture와 우회 금지 (제안 1 조건)

| ID | 입력/상황 | 기대 결과 | 검증 방법 | 결과/evidence |
|---|---|---|---|---|
| AC-08 | fixture 적재 후 `SIN-PREPAYMENT-FEE?businessDate=2026-09-20&knownAt=2026-09-20T00:00:00Z` | `checklistAvailabilityStatus=AVAILABLE`, `approvedChecklist` 존재, 그러나 blocking에 `CURRENT_VALIDATION_NOT_EVALUATED`, `CURRENT_PUBLIC_EVIDENCE_NOT_EVALUATED` 유지, **`internalChecklistUseAllowed=false`**. fixture가 사용 허용을 우회하지 않음 | 통합 테스트 | **통과** |
| AC-09 | fixture 적재 후 v2 기간 조회(2026-10-01) | `PENDING_VALIDATION`, `approvedChecklist=null`(v1 fixture로 fallback 안 함), 사용 불가 | 통합 테스트 | **통과** |
| AC-10 | `origin` | fixture version은 `origin=FIXTURE`, dataset_class `SYNTHETIC_INTERNAL`, 응답 `approvedChecklist`에 origin 노출(필드 추가가 아니라 기존 approvedChecklist 객체 안 값 포함 여부는 구현 시 결정, 응답 schema 필드 집합은 불변). 로그에 FIXTURE 표시. `origin`은 `HumanReviewDecision` 존재를 뜻하지 않음을 테스트 주석과 문서에 명시 | 통합 테스트 + 문서 | **통과.** 응답 schema 불변, origin은 DB와 로그에만 |
| AC-11 | 멱등성 | 같은 fixture 파일 두 번 적재 → 행 수 불변, `approved_checklist_fixture_run` 2건 | 통합 테스트 | **통과** |
| AC-12 | 동일 ID 내용 불일치 | version id 같고 item 내용 또는 hash 다른 fixture → `FIXTURE_CONTENT_CONFLICT`, rollback, 기존 행 불변 | 통합 테스트 | **통과** |
| AC-13 | HUMAN_REVIEW 존재 | family에 `origin=HUMAN_REVIEW` version이 있으면 fixture 적재 거부 `HUMAN_APPROVAL_EXISTS` | 통합 테스트(HUMAN_REVIEW 행을 테스트 SQL로 삽입) | **통과** |
| AC-14 | family 일치와 중복 금지 | item의 family가 version의 family와 다르면 FK 위반. 같은 version에 같은 `rule_key` 두 번 → 23505. family별 두 번째 root schedule → partial unique 위반. 같은 revision 안 기간 중첩 → 23P01 | 통합 테스트 | **통과** |
| AC-15 | append-only 보호 | 신규 6개 테이블 모두 `trust_agent_protected_tables()`에 등록, 가드 trigger 수 50 → 62. runtime의 UPDATE/DELETE/TRUNCATE 거부(42501) | `PublicProductSchemaIntegrationTest` 단언 갱신 | **통과.** 신규 테이블 5개, trigger 50 → 60, 테이블 25 → 30 |
| AC-16 | 권한 | synthetic importer와 baseline importer 계정의 `approved_checklist_*`, proposal 테이블 INSERT 거부. runtime은 허용. maintenance는 ticket/reason/actor 없이 UPDATE 거부 | 통합 테스트 | **통과** |

### C. production 기동 거부 (제안 2)

| ID | 입력/상황 | 기대 결과 | 검증 방법 | 결과/evidence |
|---|---|---|---|---|
| AC-17 | `prod` profile, `trust-agent.proposal-generation.enabled=true`만 | 기동 거부, 오류 `DEMO_FEATURE_ENABLED_IN_PROD`, 어떤 설정 키인지 메시지에 포함 | 컨텍스트 테스트 | **통과** |
| AC-18 | `prod` profile, `trust-agent.fixture-approved-checklist.enabled=true`만 | 같음 | 컨텍스트 테스트 | **통과** |
| AC-19 | `prod` profile, 둘 다 true | 기동 거부, 두 키 모두 메시지에 포함 | 컨텍스트 테스트 | **통과** |
| AC-20 | `prod` profile, 둘 다 false 또는 미설정 | 기동 성공, 두 runner bean 없음 | 컨텍스트 테스트 | **통과** |
| AC-21 | 비prod profile, 각 설정 true | 해당 runner bean 생성. false면 없음 | 컨텍스트 테스트 | **통과.** 비prod 조건은 통합 테스트의 loader/service 사용으로 확인 |

### D. 계약 유지와 회귀

| ID | 입력/상황 | 기대 결과 | 검증 방법 | 결과/evidence |
|---|---|---|---|---|
| AC-22 | applicable 응답 | 필드 집합이 PR #11 기준선과 동일. `latestProposalId` 없음(제안 3 보류). 기존 `InternalPolicyApplicableIntegrationTest` 8개 통과 | 응답 키 집합 단언 | **통과** |
| AC-23 | 계약 | Python 계약 테스트가 proposal schema, fixture schema, 정답표를 검증. dataset_class 경계 테스트에 새 경로 추가 | Python | **통과** |
| AC-24 | 회귀 | 기존 Java 82개 + 신규 전부 통과, skip 0. Python 57개 + 신규 통과 | 로컬 + CI | **통과.** Java 106, Python 60, skip 0 |
| AC-25 | 문서 | README 현재 상태에 "proposal 생성 구현, 자동 검증과 사람 검수 미구현, fixture는 test/demo 전용" 명시. core README에 command와 loader 사용법 | diff 검토 | **통과** |

### 완료 기준 변경 이력

| 기준 ID | 변경 전 | 변경 후 | 이유 | 결정자 | 승인 범위 | 검토 대상 revision/PR | 영향/재검증 |
|---|---|---|---|---|---|---|---|
| AC-08~16 | fixture 표시 1건(구 AC-09) | 우회 금지, 멱등성, 내용 불일치 거부, HUMAN_REVIEW 존재 시 거부, family 일치, 중복 금지, append-only, 권한 8건 | 제안 1 조건부 채택 조건 | 사용자 | 설계 방향과 계획 수정 | 이 문서 PR | 통합 테스트 추가 |
| AC-17~21 | 기동 거부 1건(구 AC-10) | 각 설정 단독, 동시 활성화, 비활성, 비prod 5건 | 제안 2 채택 조건 | 사용자 | 같음 | 같음 | 컨텍스트 테스트 |
| AC-22 | `latestProposalId` 추가 검토 | applicable 응답 계약 유지, 필드 집합 불변 단언 | 제안 3 보류(TASK-006까지) | 사용자 | 같음 | 같음 | 키 집합 단언 |

## Implementation Plan (AI 구현 전 계획)

Vertical slice: fixture 적재 → generate command → proposal 저장 → 조회 Repository → applicable 응답 불변 확인.

1. **V6 migration.**
   - `ALTER TABLE approved_checklist_version ADD COLUMN origin text NOT NULL DEFAULT 'HUMAN_REVIEW' CHECK (origin IN ('HUMAN_REVIEW', 'FIXTURE'))`. 기존 행 없음. `origin`은 출처 구분이며 승인 증거가 아니라는 COMMENT.
   - `approved_checklist_item(approved_checklist_version_id, family_id, item_order >= 0, rule_key 정규식, instruction length > 0, evidence_required boolean, structured_change jsonb NOT NULL DEFAULT 'null' CHECK typeof IN (object, null), source_rule_version_id NULL REFERENCES internal_policy_rule_version, source_record_hash)`. PK `(version_id, item_order)`, UNIQUE `(version_id, rule_key)`, FK `(version_id, family_id)` → `approved_checklist_version(approved_checklist_version_id, family_id)`.
   - `approved_checklist_fixture_run(run_id, fingerprint, status, imported_counts jsonb, error_code, started_at, completed_at)` 상태별 CHECK.
   - `checklist_change_proposal(proposal_id 'checklist-proposal:sha256:' PK, dataset_class='DERIVED', family_id, base_checklist_version_id, target_notice_id, generator_version, supersedes_proposal_id UNIQUE, before_hash, after_hash, created_at)`. 복합 FK로 base와 target의 family 일치.
   - `checklist_change_proposal_item(proposal_id, item_order, rule_key, change_type, before_json, after_json, before_hash, after_hash)` + 조합 CHECK.
   - `proposal_generation_run(run_id, family_id, target_notice_id, proposal_id NULL, status, error_code NULL, started_at, completed_at)`.
   - 보호 테이블 절차: 가드 trigger 10개(5테이블 × 2), `trust_agent_protected_tables()` 30개, OWNER migration, runtime SELECT/INSERT. 기존 단언 "테이블 25, trigger 50"을 30, 60으로 갱신.
2. **Fixture.** `datasets/synthetic/internal/approved-checklists/prepayment-fee-v1.approved-checklist.json`(version, `origin=FIXTURE`, `synthetic=true`, 면책 문구, items 2개)과 `prepayment-fee-v1.schedule.json`(root revision, entry `[2026-09-15, null)`). 계약 schema 2개.
3. **FixtureApprovedChecklistLoader.** `@Profile("!prod")` + `@ConditionalOnProperty(trust-agent.fixture-approved-checklist.enabled)`. runtime datasource. 절차: HUMAN_REVIEW 존재 검사 → fingerprint와 id/hash 비교(멱등 또는 충돌) → 한 트랜잭션 삽입 → run record. `AppendOnlyBootstrapChecks` 재사용.
4. **Generator.** 순수 Java: 입력(base items, target rules) → items(rule_key 사전순). canonical JSON은 `CanonicalJsonHasher`.
5. **ProposalGenerationCommand.** `@Profile("!prod")` + `@ConditionalOnProperty(trust-agent.proposal-generation.enabled)`. 인자 family, notice, run-id. 한 트랜잭션. 실패 run record 별도 트랜잭션.
6. **production 거부.** `ProductionRequiredSettingsConfiguration`에 두 키 검사 추가. 하나라도 true면 `IllegalStateException("DEMO_FEATURE_ENABLED_IN_PROD: <키 목록>")`.
7. **조회.** `ProposalRepository.findLatestByFamilyAndNotice` (TASK-006 입력). applicable 응답은 변경하지 않는다.
8. **테스트.** Java 통합 테스트 2클래스(proposal AC-01~07, fixture AC-08~16), 컨텍스트 테스트(AC-17~21), 응답 키 집합 단언(AC-22), schema 테스트 갱신(AC-15), Python 계약(AC-23).
9. **문서.** README, core README, evidence `docs/evidence/CHECKLIST_PROPOSAL_GENERATION_EVIDENCE.md`.

변경 파일 예상: V6 SQL 1, 계약 3, dataset 2, Java main 7~8, Java test 4~5, Python test 1, 문서 3.

대안: (a) proposal을 조회 시점 계산 → 검수 대상 고정 불가, ADR-003 위반. 거절. (b) instruction 전체 텍스트를 비교 단위로 → rule_key 추적 불가. 거절. (c) TASK-007 선행으로 실제 승인 생성 → 의존 역전. fixture 채택(PLAN-001 제안 A).

위험: V6 규모. 보호 테이블 절차 누락 시 R-01 공백(AC-15로 잡음). fixture가 승인으로 오해될 위험은 `origin`과 AC-08, AC-10으로 완화.

인간의 계획 판단 / 승인 범위: **설계 방향과 계획 수정 승인(결정자 사용자, 검토 대상 이 문서 PR). 구현 착수 승인은 별도.**

## AI 제안 및 인간 판단 기록

### 제안 1: `approved_checklist_item` 테이블과 `origin` 컬럼 추가, fixture는 demo 전용 loader로 적재

**AI 제안**
내용: V4에 없는 checklist 내용 테이블을 V6에서 추가한다. v1 승인 checklist fixture는 `origin=FIXTURE`로 표시하고, synthetic importer가 아닌 별도 `FixtureApprovedChecklistLoader`(test/demo, runtime role)가 적재한다.
대안: (가) 테스트 SQL로만 삽입 → demo 불가. (나) synthetic importer에 승인 테이블 권한 부여 → R-05 위반.

**판단**
- [x] 수정 (조건부 채택)
- 판단자 / 검토 대상 revision 또는 PR: 사용자 / 이 문서 PR
- 이유 / 승인 범위: `origin`은 출처 구분이며 인간 승인 증거가 아니다. fixture가 검증, 사람 결정, 업무 사용 허용 조건을 우회하지 않는다는 AC(AC-08~10)와 멱등성, 동일 ID 내용 불일치 거부, HUMAN_REVIEW checklist 존재 시 적재 거부, family 일치, 중복 금지, append-only 보호, 권한 검증(AC-11~16)을 포함한다.

### 제안 2: production에서 demo 설정이 켜져 있으면 기동 거부

**판단**
- [x] 채택
- 판단자 / 검토 대상 revision 또는 PR: 사용자 / 이 문서 PR
- 이유 / 승인 범위: 두 설정 키(`trust-agent.proposal-generation.enabled`, `trust-agent.fixture-approved-checklist.enabled`) 중 하나라도 활성화되면 기동 거부. 각 설정 단독과 동시 활성화를 검증(AC-17~21).

### 제안 3: applicable 응답에 `latestProposalId` 추가

**판단**
- [x] 보류 (TASK-006까지)
- 판단자 / 검토 대상 revision 또는 PR: 사용자 / 이 문서 PR
- 이유 / 승인 범위: TASK-005에서는 applicable 응답 계약을 유지한다(AC-22). 재검토는 TASK-006에서.

## Implementation Result (구현 결과와 자동 검증)

실제 변경: V6 migration 1, Java main 7개 파일(`internalpolicy/proposal` 패키지 6 + `ProductionRequiredSettingsConfiguration` 수정), `application.yml`(설정 2개와 schema version 6), 계약 3개와 정답 fixture 1개, 예시 데이터 2개, Java 테스트 신규 3개와 수정 4개, Python 테스트 1개, README 2종, evidence 1개. 계획 대비 차이: 신규 테이블은 6개가 아니라 5개(fixture run 포함, item 1 + proposal 2 + run 2). 결과 상세는 `docs/evidence/CHECKLIST_PROPOSAL_GENERATION_EVIDENCE.md`.

| AC ID | 검증 대상 revision | 실행 명령/절차 | 환경/버전 | 결과 | evidence 경로 |
|---|---|---|---|---|---|
| AC-01~25 | 브랜치 `feat/checklist-change-proposal` HEAD | `./gradlew clean test bootJar --offline --no-daemon`, `python3 -m unittest discover -s tests` | 로컬 macOS arm64, Java 21, Docker, PostgreSQL 18.6 Testcontainers, Python 3.11.8 | Java 106 통과(기존 85 + 신규 21), 실패 0, skip 0, jar 생성. Python 60 통과(57 + 3) | evidence 문서 표. 원격 CI는 PR에 기록 |

## AI self-review

- 검사 범위: 신규 SQL 제약과 보호 테이블 등록, 생성기 결정성, fixture 우회 금지 경로(적용 공문 조회 3건), 권한 매트릭스, production 거부, 응답 계약 불변, Python과 Java 해시 일치(정답 파일을 Python으로 만들고 Java가 같은 ID를 생성).
- 발견과 수정: 첫 실행에서 테스트 1건 실패. 이미 대체된 변경안을 다시 대체해 FK 위반 전에 unique 위반이 났다. 기대값을 고쳤고 업무 로직 결함이 아니다.
- 확인된 결함: 없음. 미해결 위험: 변경안 revision(MODIFY) 흐름은 테이블만 있고 TASK-007에서 구현한다. `approved_checklist_item`에 기존 승인 checklist 행이 있다면 항목이 비어 있을 수 있으나 현재 DB에는 HUMAN_REVIEW 행이 없다.

## 인간 검수와 Explainability Gate

- REVIEW_CHECKLIST 적용 / 검수자 / 검수 대상 revision / 결과: [검수 체크리스트](../development/REVIEW_CHECKLIST.md) / 사용자 / PR #18(브랜치 `feat/checklist-change-proposal`, 커밋 `ef57cc7`, `8826995`) / **통과**
- 인간이 확인한 내용: 아래 검수 자료의 대표 변경안 전후 값, 반복 실행 시 중복 방지, 기준 checklist가 없을 때의 처리, 테스트용 데이터가 실제 승인이나 사용 허용을 대신하지 않는다는 근거, production 차단 결과와 원격 CI 결과를 확인했고 동작과 한계를 이해했으며 승인한 범위에 부합한다고 기록했다.
- Explainability Gate: **통과**(결정자 사용자).
- 아래 검수 자료는 AI가 작성한 확인용 자료다.

### 인간 검수 자료

**대표 변경안의 전후 값** (`contracts/fixtures/prepayment-fee-v2-proposal.expected.json`, 통합 테스트 `generatesTheExpectedPrepaymentFeeProposalFromFixtureBaseline`이 DB 저장 결과와 대조)

| 항목 | 종류 | 변경 전 (v1 기준 checklist) | 변경 후 (v2 공문) |
|---|---|---|---|
| `CHECK_PREPAYMENT_FEE_RATE` | 수정 | 수수료율 after_value "1.2", 시행일 2026-09-15, 조건 1개("기업 고객의 기업여신 상담 건"), 예외 1개 | after_value "0.8"(before_value "1.2"), 시행일 2026-10-01, 조건 2개(+"2026-10-01 이후 중도상환"), 예외 1개 |
| `CHECK_CUSTOMER_CONTRACT_DATE` | 추가 | 없음 | 고객 약정일과 중도상환 예정일 확인, boolean true, 시행일 2026-10-01 |
| `CHECK_NOTICE_SOURCE` | 변경 없음 | 같은 내용 | 같은 내용 (항목 생성 안 함) |

값은 문자열 십진수로 저장되며("1.2", "0.8") 부동소수점은 생성기와 Python 계약 테스트가 거부한다.

**반복 실행 시 중복 방지** (`sameInputIsIdempotentAndOnlyAddsARunRecord`): 같은 입력으로 두 번 실행하면 변경안 ID가 같고(`checklist-proposal:sha256:59ad46b8...`), 변경안 1건과 항목 2건은 그대로이며 실행 기록만 2건이 된다. 두 번째 결과의 `created=false`.

**기준 checklist가 없을 때** (`familyWithoutApprovedChecklistFailsWithNoBaseChecklistAndAuditsTheRun`): 셀러론 공문군은 승인 checklist가 없어 변경안이 만들어지지 않고(0건), 실행 기록에 `FAILED`와 `NO_BASE_CHECKLIST`가 남는다. 미수신 또는 철회된 공문은 `TARGET_NOTICE_NOT_VISIBLE`(`unknownOrWithdrawnTargetIsNotVisible`).

**테스트용 데이터가 승인이나 사용 허용을 대신하지 않는 근거**
- `fixtureChecklistIsAvailableForItsOwnNoticeButNeverUnlocksUse`: 예시 checklist가 있는 v1 기간(2026-09-20) 조회는 `AVAILABLE`이지만 차단 사유 `CURRENT_VALIDATION_NOT_EVALUATED`, `CURRENT_PUBLIC_EVIDENCE_NOT_EVALUATED`가 남고 `internalChecklistUseAllowed=false`.
- `fixtureChecklistDoesNotFallBackToTheNewNoticePeriod`: v2 기간(2026-10-01)은 v1 예시로 대체하지 않고 `PENDING_VALIDATION`, `approvedChecklist=null`, 사용 불가.
- `fixtureIsRefusedWhenHumanReviewedChecklistExistsForTheFamily`: 사람 검토 승인(`origin=HUMAN_REVIEW`)이 있는 공문군에는 예시 적재가 `HUMAN_APPROVAL_EXISTS`로 거부된다.
- DB 컬럼 `origin`은 출처 구분이며 `FIXTURE` 값과 면책 문구가 데이터에 들어 있다. 응답 계약은 바뀌지 않았다(`applicableResponseContractIsUnchangedByProposalWork`).

**production 차단 결과** (`DemoFeatureProductionGuardTest` 4건): 변경안 생성 설정만 켠 경우, 예시 적재 설정만 켠 경우, 둘 다 켠 경우 모두 기동이 거부되고 오류 메시지에 `DEMO_FEATURE_ENABLED_IN_PROD`와 해당 설정 키가 들어간다. 둘 다 꺼져 있거나 없으면 기동한다.

**직접 확인하는 방법**: 통합 테스트 2클래스 실행 `./gradlew :apps:core-service:test --tests '*ChecklistProposalIntegrationTest' --tests '*InternalPolicyApplicableIntegrationTest' --offline --no-daemon`(Docker 필요). 또는 로컬 PostgreSQL에서 core README의 두 명령(예시 적재, 변경안 생성)을 실행한 뒤 `select rule_key, change_type, before_json, after_json from checklist_change_proposal_item order by item_order`로 전후 값을 본다.

## 결정 기록과 완료

- 최종 결정 / 결정자 / 승인 범위 / 검토 대상 revision 또는 PR: **완료** / 사용자 / 확정한 완료 확인 조건 25개와 계획대로의 구현(checklist 변경안 생성, 테스트용 승인 checklist 예시 적재, production demo 설정 차단, 적용 공문 조회 응답 계약 유지) / PR #18
- Acceptance Criteria 충족 / evidence / ADR / PR: AC-01~AC-25 전부 통과. evidence `docs/evidence/CHECKLIST_PROPOSAL_GENERATION_EVIDENCE.md`. ADR-003, ADR-008. PR #18.
- 이 완료는 **자동 검증, 인간 승인, 화면까지 완료됐다는 뜻이 아니다.**
- 잔여 위험 / 후속 Task: 변경안이 있어도 사용 허용은 바뀌지 않음. TASK-006 자동 검증(계획 PR #19), TASK-007 사람 결정.
