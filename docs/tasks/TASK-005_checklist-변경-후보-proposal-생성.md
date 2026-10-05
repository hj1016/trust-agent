# TASK-005 공문 변경에서 checklist 변경 후보(proposal) 생성

- 상태: **계획 검토 대기** (사전 Acceptance Criteria 작성 완료. 구현은 사용자 착수 승인 뒤)
- 담당자 / 인간 결정자: AI(계획, 구현, 자동 검증, self-review) / 사용자(범위, AC 확정, 제안 판단, 검수, Explainability Gate)
- 요구사항 출처: [PLAN-001](PLAN-001_중도상환수수료-흐름-Task-분할-초안.md) TASK-005, [ADR-008](../adr/ADR-008-internal-notice-effective-policy-and-review.md) "체크리스트 변경 후보와 revision", [ADR-003](../adr/ADR-003-validation-and-human-approval.md), README MVP 목표 5단계 전반부. 최종 기획서 대표 시나리오(중도상환수수료율 1.2퍼센트 → 0.8퍼센트).
- 관련 Issue / PR / ADR / 이전 Task: PR #11(기준선), [TASK-001](TASK-001_합성-공문-조회-자산-보존.md), ADR-009(PostgreSQL 유지), 후속 TASK-006(자동 검증), TASK-007(사람 결정)

## Goal / 관련 요구사항

- Goal: 새 공문 version의 구조화 rule과 그 family의 직전 승인 checklist를 비교해, 추가/수정/삭제 항목과 전후 값을 담은 **결정적 proposal**을 만들어 DERIVED record로 저장한다. 공문 담당 부서 검수자가 검토할 변경 후보가 생기고(S3), TASK-006 자동 검증과 TASK-007 사람 결정의 입력이 된다.
- 관련 요구사항: ADR-008 "Proposal은 base checklist version, target notice, rule과 generator version을 참조한다. item은 추가, 수정, 삭제와 before/after 값을 명시한다. DERIVED로 분류한다. MODIFY는 새 revision을 만들고 `supersedes_proposal_id`, before hash, after hash, 수정 사유를 보존한다. 같은 입력과 generator version은 같은 canonical 내용을 생성하고 실행 사건 ID는 별도로 둔다. LLM을 쓰지 않는다."
- 사용자: 공문 담당 부서 검수자(합성). 후속 Task의 validator와 review command.
- 사전조건: PR #11 기준선(합성 공문 v1/v2, `structured_change`, applicable 조회). PostgreSQL V1~V5. **family에 직전 승인 checklist가 존재해야 한다.** 현재 DB에는 승인 checklist가 없으므로 fixture가 필요하다(제안 1).
- 입력: `family_id`, `target_notice_id`(예: `SIN-PREPAYMENT-FEE-V2`), `generator_version`(설정값, 예: `proposal-generator-v1`), 실행 `run_id`.
- 업무규칙:
  1. base checklist는 target 공문 `effective_from` 전날에 적용되는 checklist다. 즉 family의 최신 visible schedule revision에서 `[effective_from, effective_to)`가 `target.effective_from - 1일`을 포함하는 entry의 checklist version. 없으면 proposal을 만들지 않고 `NO_BASE_CHECKLIST`를 실행 기록에 남긴다.
  2. 비교 단위는 `rule_key`다. target rule에만 있으면 ADD, base item에만 있으면 REMOVE, 둘 다 있고 내용(instruction, evidence_required, structured_change)이 다르면 MODIFY, 같으면 item을 만들지 않는다.
  3. before/after 값은 canonical JSON으로 저장하고 각 hash를 기록한다. 금융 비율은 문자열 십진수 그대로 비교한다(부동소수점 변환 금지).
  4. proposal 내용 identity는 (base checklist version id, target notice id, generator version, canonical items)의 SHA-256이다. 같은 입력은 같은 `proposal_id`를 만든다. 두 번째 생성은 멱등 처리하고 실행 기록만 추가한다.
  5. proposal과 item은 append-only다. 수정은 새 revision(`supersedes_proposal_id`)으로만 한다. 이 Task에서는 revision 생성 경로의 테이블과 제약만 만들고 MODIFY 흐름은 TASK-007에서 구현한다.
  6. proposal 생성은 승인이 아니다. `internalChecklistUseAllowed`에 영향을 주지 않는다. applicable 조회의 `checklistAvailabilityStatus`는 proposal이 있어도 `PENDING_VALIDATION`이다.
  7. 생성 command는 test/demo profile에서만 bean이 만들어진다. production profile에서는 존재하지 않는다(ADR-008 인증 경계).
- 상태전이: (공문 version 존재, 승인 checklist 존재) → `generate` → proposal record 1건 + items + run record. 금지 전이: proposal UPDATE/DELETE, base checklist 없는 생성, LLM 호출, 승인 상태 변경.
- 데이터 영향: 읽기 `internal_notice_version`, `policy_extraction_attempt`, `internal_policy_rule_evidence`, `internal_policy_rule_version`, `approved_checklist_schedule_revision`, `approved_checklist_schedule_entry`, `approved_checklist_version`, (신규) `approved_checklist_item`. 쓰기 (신규) `checklist_change_proposal`, `checklist_change_proposal_item`, `proposal_generation_run`. 불변 조건: R-01 append-only 가드, `proposal_id` 결정성, `supersedes_proposal_id UNIQUE`, item `(proposal_id, item_order)` PK, `change_type IN (ADD, MODIFY, REMOVE)`, ADD는 before NULL, REMOVE는 after NULL, MODIFY는 둘 다 NOT NULL.
- API: 이 Task는 HTTP endpoint를 추가하지 않는다. CLI command(ApplicationRunner, `trust-agent.proposal-generation.enabled=true` + family/notice 인자)와 조회용 Repository만 둔다. applicable 조회 응답에 `latestProposalId`(nullable) 1필드 추가는 제안 3.
- 트랜잭션: proposal + items + run을 하나의 트랜잭션으로 저장. 실패 시 전부 rollback. 외부 호출 없음.
- 권한: runtime role에 신규 3개 테이블 SELECT, INSERT. synthetic importer와 baseline importer에는 권한 없음. maintenance는 R-02 경로. V2 주석의 보호 테이블 추가 절차 준수(가드 trigger, TRUNCATE 가드, `trust_agent_protected_tables()` 갱신, OWNER 변경).
- 실패 시나리오: base 없음 → `NO_BASE_CHECKLIST` run record(FAILED), proposal 없음. 같은 입력 재실행 → 멱등, run record만 추가. target 공문이 수신되지 않음 또는 철회됨 → `TARGET_NOTICE_NOT_VISIBLE`. 두 실행이 동시에 같은 proposal_id를 삽입 → PK 충돌을 멱등으로 처리. DB 장애 → 실패 run record 별도 트랜잭션 시도 후 원래 오류 보존(importer 패턴).
- 테스트(각 테스트가 보장하는 것): AC 표 참조.
- Out of Scope: 자동 검증(TASK-006), 사람 결정과 MODIFY revision 생성(TASK-007), HTTP 노출, LLM, 화면, 공개 상품 교차 검증 실행.

## 요구사항과 범위

업무 문제: 공문 v2가 수신되어도 지금은 "어떤 checklist 항목이 어떻게 바뀌어야 하는가"를 시스템이 만들어 주지 않는다. 검수자는 공문 원문과 기존 checklist를 직접 대조해야 한다.

포함 범위:
- Flyway **V6**: `approved_checklist_item`(제안 1), `checklist_change_proposal`, `checklist_change_proposal_item`, `proposal_generation_run`. 보호 테이블 등록과 권한.
- Java: `ChecklistChangeProposalGenerator`(순수 로직, SQL 없음), `ProposalRepository`(JdbcClient), `ProposalGenerationCommand`(runner), `ProposalGenerationProperties`.
- 합성 fixture: `SIN-PREPAYMENT-FEE` v1 승인 checklist 1건과 schedule revision 1건(제안 1). 계약 schema와 dataset 파일.
- 계약: `contracts/checklist-change-proposal.schema.json`(DERIVED), `contracts/synthetic-approved-checklist-fixture.schema.json`(SYNTHETIC_INTERNAL, `origin=FIXTURE`). 정답표 `contracts/fixtures/prepayment-fee-v2-proposal.expected.json`.
- 테스트: Java 통합 테스트(PostgreSQL Testcontainers), Python 계약 테스트, 기존 테스트의 보호 테이블 개수 단언 갱신.

제외 범위: 위 Out of Scope.

기존 자산과 기준선 차이: V4의 `approved_checklist_version`에는 **checklist 내용(item) 테이블이 없다.** ADR-008은 "승인된 checklist 내용의 immutable identity"라고 했지만 내용 자체를 저장하는 테이블이 구현되지 않았다. proposal이 "직전 checklist와의 차이"를 만들려면 내용이 있어야 한다. 이것이 제안 1의 배경이다.

## Acceptance Criteria (구현 전 고정)

결정자 / 판단 근거 / 검토 대상 revision 또는 PR: 사용자 / ADR-008, PLAN-001 / 이 Task 초안(PR 생성 시 번호 기록). **AC 확정은 사용자 승인 대기.**

| ID | 입력/상황 | 기대 결과(실패/경계 포함) | 검증 방법 | 결과/evidence |
|---|---|---|---|---|
| AC-01 | v1 승인 checklist fixture(2개 item: `CHECK_PREPAYMENT_FEE_RATE` 1.2퍼센트, `CHECK_NOTICE_SOURCE`)와 schedule `[2026-09-15, null)` 적재, v2 공문 수신 상태에서 `generate(SIN-PREPAYMENT-FEE, SIN-PREPAYMENT-FEE-V2)` | proposal 1건. item 2개: `CHECK_PREPAYMENT_FEE_RATE` MODIFY(before `structured_change.after_value` "1.2" → after "0.8", `effective_on` 2026-10-01, 조건 2개), `CHECK_CUSTOMER_CONTRACT_DATE` ADD. `CHECK_NOTICE_SOURCE`는 item 없음(동일). base = v1 checklist version, target = V2 | 통합 테스트 + 정답표 fixture와 canonical 비교 | 미검증 |
| AC-02 | AC-01을 두 번 실행 | `proposal_id` 동일, proposal/item 행 수 불변, `proposal_generation_run` 2건(run_id 다름) | 통합 테스트 | 미검증 |
| AC-03 | 승인 checklist가 없는 family(`SIN-SELLER-CHECKLIST`)로 생성 | proposal 0건. run record 1건 `status=FAILED`, `error_code=NO_BASE_CHECKLIST` | 통합 테스트 | 미검증 |
| AC-04 | runtime 계정으로 proposal UPDATE / DELETE / TRUNCATE | 모두 거부(가드 trigger 42501 또는 권한). `trust_agent_protected_tables()`에 신규 3개 테이블 등록, 가드 trigger 수 50 → 56 | `PublicProductSchemaIntegrationTest` 단언 갱신 | 미검증 |
| AC-05 | item 제약 | ADD인데 before 있음, REMOVE인데 after 있음, MODIFY인데 before 또는 after 없음, 알 수 없는 change_type → CHECK 위반 23514 | 통합 테스트 | 미검증 |
| AC-06 | `supersedes_proposal_id` | 같은 proposal을 두 revision이 동시에 supersede → 두 번째 UNIQUE 위반 23505. 다른 family의 proposal을 supersede → FK 위반 | 통합 테스트 | 미검증 |
| AC-07 | 금융 비율 | before/after JSON에 "1.2", "0.8"이 문자열로 저장되고 canonical hash가 fixture와 일치. `CanonicalJsonHasher`가 부동소수점을 거부 | 단위 + 통합 | 미검증 |
| AC-08 | applicable 조회 (TASK-005 뒤, 2026-10-01) | `checklistAvailabilityStatus=PENDING_VALIDATION`, `internalChecklistUseAllowed=false`. proposal 존재가 사용 허용을 바꾸지 않음 | 기존 `InternalPolicyApplicableIntegrationTest` + 추가 case | 미검증 |
| AC-09 | fixture 표시 | `approved_checklist_version.origin='FIXTURE'`, dataset_class `SYNTHETIC_INTERNAL`, 응답과 로그에 fixture임이 표시. production profile에서 fixture loader bean 없음 | 통합 테스트 + 컨텍스트 테스트 | 미검증 |
| AC-10 | production profile | `ProposalGenerationCommand` bean 없음. `trust-agent.proposal-generation.enabled=true`여도 기동 거부 또는 무시(결정은 제안 2) | `RuntimeDatasourcePropertiesTest` 류 컨텍스트 테스트 | 미검증 |
| AC-11 | 계약 | Python 계약 테스트가 proposal schema, fixture schema, 정답표를 검증. dataset_class 경계 테스트(`tests/contract`의 경로 규칙 검사)에 새 경로 추가 | Python | 미검증 |
| AC-12 | 회귀 | 기존 Java 82개 + 신규 테스트 전부 통과, skip 0. Python 57개 + 신규 통과 | 로컬 + CI | 미검증 |
| AC-13 | 문서 | README 현재 상태에 "proposal 생성 구현, 자동 검증과 사람 검수 미구현" 명시. core README에 command 사용법 | diff 검토 | 미검증 |

### 완료 기준 변경 이력

변경 없음.

## Implementation Plan (AI 구현 전 계획)

Vertical slice: fixture 적재 → generate command → proposal 저장 → 조회 Repository → applicable 조회 영향 없음 확인.

1. **V6 migration.**
   - `approved_checklist_item(approved_checklist_version_id, family_id, item_order, rule_key, instruction, evidence_required, structured_change jsonb, source_rule_version_id NULL, source_record_hash)`; PK `(approved_checklist_version_id, item_order)`; UNIQUE `(approved_checklist_version_id, rule_key)`; FK to version; `structured_change` CHECK V5와 동일.
   - `approved_checklist_version`에 `origin text NOT NULL DEFAULT 'HUMAN_REVIEW' CHECK (origin IN ('HUMAN_REVIEW','FIXTURE'))` 추가. 기존 행 없음.
   - `checklist_change_proposal(proposal_id 'proposal:sha256:...' PK, dataset_class='DERIVED', family_id, base_checklist_version_id FK, target_notice_id FK(notice,family), generator_version, supersedes_proposal_id UNIQUE FK, before_hash, after_hash, created_at)`.
   - `checklist_change_proposal_item(proposal_id, item_order, rule_key, change_type, before_json jsonb, after_json jsonb, before_hash, after_hash)` + CHECK 조합.
   - `proposal_generation_run(run_id PK, family_id, target_notice_id, proposal_id NULL, status, error_code NULL, started_at, completed_at)` 상태별 CHECK.
   - 보호 테이블 절차: 가드 trigger 6개 추가, `trust_agent_protected_tables()` 31개로 갱신, OWNER migration, runtime SELECT/INSERT. 기존 통합 테스트의 "테이블 25개, trigger 50개" 단언을 29개, 58개로 갱신(테이블 4개 추가: item 1 + proposal 2 + run 1).
2. **Fixture.** `datasets/synthetic/internal/approved-checklists/prepayment-fee-v1.approved-checklist.json`(version + items 2개, `origin=FIXTURE`, 면책 문구)과 `prepayment-fee-v1.schedule.json`(root revision, entry `[2026-09-15, null)`). 계약 schema. 적재 경로는 제안 1.
3. **Generator.** 순수 Java: 입력(base items, target rules) → 출력(items 정렬: rule_key 사전순, change_type). canonical JSON은 `CanonicalJsonHasher` 재사용. 단위 테스트로 결정성.
4. **Command.** `@ConditionalOnProperty(trust-agent.proposal-generation.enabled)` + `@Profile("!prod")`. 인자 family, notice, run-id. 트랜잭션 1개. 실패 run record는 별도 트랜잭션(importer 패턴 재사용).
5. **조회.** `ProposalRepository.findLatestByFamilyAndNotice` (TASK-006 입력).
6. **테스트.** Java 통합 테스트 신규 1클래스(AC-01~08), 스키마 테스트 갱신(AC-04), 컨텍스트 테스트(AC-09, 10). Python 계약 테스트 추가(AC-11).
7. **문서.** README, core README, evidence `docs/evidence/CHECKLIST_PROPOSAL_GENERATION_EVIDENCE.md`.

변경 파일 예상: V6 SQL 1, 계약 3, dataset 2, Java main 5~6, Java test 3~4, Python test 1, 문서 3.

대안: (a) proposal을 테이블 없이 조회 시점에 계산 → 검수 대상이 고정되지 않아 ADR-003 위반. 거절. (b) item 비교 단위를 instruction 텍스트 전체로 → rule_key 변경 추적 불가. 거절. (c) fixture 대신 TASK-007을 먼저 구현해 v1을 실제 승인 → 흐름을 두 번 검증하지만 TASK-005가 TASK-007에 의존하게 됨. PLAN-001 제안 A에서 fixture 채택.

위험: V4에 item 테이블이 없어 schema 추가가 예상보다 크다. 보호 테이블 절차를 빠뜨리면 R-01 공백이 생긴다(스키마 테스트 단언으로 잡음). fixture가 "실제 승인"으로 오해될 위험은 `origin` 컬럼과 표시로 완화.

트랜잭션/동시성/권한: 위 Goal 절. 실패/운영: run record. 검증과 문서화: AC 표.

인간의 계획 판단 / 승인 범위: **판단 대기.**

## AI 제안 및 인간 판단 기록

### 제안 1: `approved_checklist_item` 테이블과 `origin` 컬럼 추가, fixture는 demo 전용 loader로 적재

**AI 제안**
내용: V4에 없는 checklist 내용 테이블을 V6에서 추가한다. v1 승인 checklist fixture는 `origin=FIXTURE`로 표시하고, synthetic importer가 아닌 별도 `FixtureApprovedChecklistLoader`(test/demo profile, runtime role 사용)가 적재한다. R-05 "importer는 승인을 기록할 수 없다"를 지키기 위해 importer 권한은 늘리지 않는다.
대안: (가) 테스트 코드 SQL로만 fixture 삽입(현재 `InternalPolicyApplicableIntegrationTest` 방식) → demo 실행 불가. (나) synthetic importer에 승인 테이블 INSERT 권한 부여 → R-05 위반.
기대 효과: proposal 비교 대상이 생기고, fixture와 실제 승인이 데이터에서 구분된다.
위험: V6가 커진다. `origin` 컬럼은 TASK-007에서 `HUMAN_REVIEW`로 쓰인다.

**판단**
- [ ] 채택 [ ] 수정 [ ] 거절
- 판단자 / 검토 대상 revision 또는 PR: 사용자 / 판단 대기

### 제안 2: production profile에서 proposal 생성 설정이 켜져 있으면 기동 거부

**AI 제안**
내용: `ProductionRequiredSettingsConfiguration` 패턴에 맞춰 prod에서 `proposal-generation.enabled=true`면 기동을 거부한다(무시보다 안전).
대안: 무시하고 경고 로그.
**판단**
- [ ] 채택 [ ] 수정 [ ] 거절
- 판단자 / 검토 대상 revision 또는 PR: 사용자 / 판단 대기

### 제안 3: applicable 조회 응답에 `latestProposalId` 1필드 추가

**AI 제안**
내용: 검수자 화면과 TASK-006이 "검증 대기 중인 proposal이 있는지" 알 수 있게 nullable 필드를 추가한다. 상태 enum과 `internalChecklistUseAllowed`는 바꾸지 않는다.
대안: TASK-006까지 응답 변경 없음.
**판단**
- [ ] 채택 [ ] 수정 [ ] 거절
- 판단자 / 검토 대상 revision 또는 PR: 사용자 / 판단 대기

## Implementation Result / AI self-review / 인간 검수 / 결정 기록

미실행 / 미기록. 구현은 사용자 착수 승인 뒤 시작한다.
