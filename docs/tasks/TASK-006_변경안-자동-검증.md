# TASK-006 checklist 변경안의 자동 검증

- 상태: **계획 검토 대기** (계획 작성 승인. 제안 3건 판단 반영. 구현 착수 승인은 아님)
- 담당자 / 인간 결정자: AI(계획, 구현, 자동 검증, self-review) / 사용자(범위, 완료 확인 조건 확정, 제안 판단, 착수 승인, 검수)
- 요구사항 출처: [PLAN-001](PLAN-001_중도상환수수료-흐름-Task-분할-초안.md) TASK-006, [ADR-003](../adr/ADR-003-validation-and-human-approval.md) 자동 검증과 사람 승인 분리, [ADR-008](../adr/ADR-008-internal-notice-effective-policy-and-review.md) "자동 검증" 절, README MVP 목표 5단계 "숫자 및 시행일 오류 차단".
- 관련: [TASK-005](TASK-005_checklist-변경-후보-proposal-생성.md)(입력인 변경안), 후속 TASK-007(사람 결정은 검증 결과를 입력으로 받는다)

## Goal / 관련 요구사항

- Goal: 변경안(proposal)이 공문 원문의 구조화 규칙과 어긋나지 않는지, 숫자와 시행일이 맞는지, 공개 근거와 충돌하지 않는지를 **자동으로 검사해 통과(PASS), 주의(WARN), 실패(FAIL)로 판정**하고 근거와 함께 append-only로 저장한다. 사람 검수자는 이 결과를 보고 결정한다(TASK-007). **자동 검증 통과는 승인이 아니며 checklist 사용을 허용하지 않는다**(ADR-003).
- 사용자: 공문 담당 부서 검수자(합성). 후속 사람 결정 명령.
- 사전조건: TASK-005 병합(변경안과 항목 테이블, 예시 승인 checklist). PostgreSQL V6. 공개 상품 baseline 적재(교차 검증용).
- 입력: `proposal_id`, 실행 `run_id`, validator version(설정값, 예: `proposal-validator-v1`). 검증 시각은 서버 시계에서 한 번 읽는다(`validated_at`).
- 업무규칙:
  1. 검증 결과와 세부 오류(issue), 실행 기록은 **하나의 트랜잭션**으로 저장한다. 중간에 실패하면 전부 취소되고 아무것도 남지 않는다(실패 실행 기록만 별도 트랜잭션으로 남긴다). 다시 검증하면 이전 결과를 고치지 않고 새 결과를 추가한다.
  2. 결과는 PASS, WARN, FAIL 셋 중 하나다. 실패 항목이 하나라도 있으면 FAIL, 실패는 없고 주의가 있으면 WARN, 둘 다 없으면 PASS.
  3. 결과는 **어떤 변경안(`proposal_id`)의 어떤 내용(`proposal_hash` = 변경안 after_hash)** 에 대한 것인지 기록한다. 변경안이 바뀌면(새 revision) 다시 검증해야 한다.
  4. 공개 근거 교차 검증은 구조화된 값(fact_key, subject_type, value_type, value, unit)으로 비교한다. 공개 정보가 달라도 내부 규칙이나 공문을 자동 수정하지 않는다.
  5. 교차 검증에 쓴 공개 관측, 약관 version, 근거, fact 식별자를 결과에 저장한다. 과거 검증을 다시 볼 때 현재 공개 상태로 결과를 바꾸지 않는다.
  6. 적용 공문 조회는 **조회 기준 시각(`knownAt`) 이후의 검증 결과를 제외**하고, 그 시점에 보이는 최신 변경안의 최신 결과만 쓴다. 결과가 PASS여도 사람 승인 전에는 `internalChecklistUseAllowed=false`이며 차단 사유에 사람 승인 대기가 남는다.
  7. 셀러론 기준 checklist 예시(교차 검증 테스트용)는 TASK-005 예시와 같은 조건을 따른다: 출처 FIXTURE 표시, production 차단, 사람 검토 승인이 있는 공문군에는 적재 거부, 실제 승인이나 사용 허용을 대신하지 않음.
- 상태전이: 변경안 존재 → `validate` → 검증 결과 1건 + issue N건 + 실행 기록(한 트랜잭션). 금지: 결과 수정/삭제, 검증 결과로 승인 상태 변경이나 사용 허용, 공문 자동 수정.
- 데이터 영향: 읽기 변경안과 항목, 대상 공문 규칙, 기준 checklist 항목, 공개 상품과 관측과 약관 fact, 공문 철회 사건. 쓰기(신규) `automated_validation_result`, `automated_validation_issue`, `validation_run`. 불변 조건은 아래 "결과와 issue 일관성" 절.
- API: HTTP endpoint 추가 없음(CLI 실행). 적용 공문 조회 응답은 제안 1의 조건부 승인에 따라 바뀐다(아래).
- 트랜잭션: 결과 + issue + 실행 기록 한 트랜잭션. 공개 근거 조회는 같은 트랜잭션 안의 읽기만. 외부 호출 없음.
- 권한: runtime 계정에 신규 3개 테이블 조회와 추가. 적재 도구 계정 권한 없음. 검증 명령은 test/demo profile 전용이며 production에서 설정이 켜지면 기동 거부(TASK-005와 같은 방식, 설정 키 `trust-agent.proposal-validation.enabled` 추가).
- 실패 시나리오: 변경안 없음 → `PROPOSAL_NOT_FOUND` 실행 기록. issue 저장 중 실패 → 결과와 issue 전부 rollback, 실행 기록 FAILED(`VALIDATION_WRITE_FAILED`). 공개 근거 조회 DB 오류 → 결과 없음, 실행 기록 FAILED. 같은 변경안 재검증 → 새 결과 추가. 동시 검증 → 결과 2건 모두 저장되며 조회는 `validated_at` 최신, 같으면 ID 순.
- Out of Scope: 사람 결정과 승인 checklist 발행(TASK-007), LLM 판단, 화면, HTTP 노출.

## 무엇을 검사하고 어떻게 판정하는가

변경안의 항목마다 아래 검사를 적용한다. 각 검사는 issue 코드, 심각도(FAIL/WARN/INFO), 대상 rule_key, 메시지, 세부(JSON)를 남긴다.

| 번호 | 검사 | 통과 | 주의(WARN) | 실패(FAIL) |
|---|---|---|---|---|
| V-01 변경 후 값 일치 | 항목의 변경 후 내용이 대상 공문의 구조화 규칙과 같은가 | 같음 | — | 다름 → `VALUE_MISMATCH`. 사람이 수정한 revision(TASK-007)에서 공문과 어긋난 값을 잡는다 |
| V-02 시행일 일치 | 구조화 변경의 `effective_on`이 대상 공문 `effective_from`과 같은가 | 같음 | — | 다름 또는 없음 → `EFFECTIVE_DATE_MISMATCH` |
| V-03 변경 전 값 연속성 | 수정 항목에서 공문이 말하는 변경 전 값(`before_value`)이 기준 checklist의 현재 값과 같은가 | 같음 | 공문에 변경 전 값이 없음 → `BEFORE_VALUE_NOT_STATED` | 다름 → `BEFORE_VALUE_MISMATCH` |
| V-04 숫자 형식과 범위 | PERCENT 또는 KRW 값이 문자열 십진수나 정수이고 범위가 합리적인가(퍼센트 0 이상 100 이하, 원 0 이상) | 맞음 | — | 부동소수점, 빈 값, 범위 밖 → `INVALID_NUMERIC_VALUE` |
| V-05 대상 상품 존재 | `applicable_product_keys`의 각 키가 공개 상품 목록에 있는가 | 있음 | — | 없음 → `UNKNOWN_PRODUCT_KEY` |
| V-06 조건과 예외 | 숫자 정책 변경에 조건이 1개 이상 있는가 | 있음 | 없음 → `MISSING_CONDITIONS` | — |
| V-07 삭제 항목 | 기준 checklist에 있던 항목이 새 공문에서 빠졌는가 | 삭제 없음 | 삭제 있음 → `ITEM_REMOVED` | — |
| V-08 공개 근거 교차 검증 | 규칙에 `public_cross_check`가 있을 때 현재 공개 fact와 값, 주체, 단위가 같은가. 공개 근거는 기존 freshness 정책으로 확인 | 같고 확인됨 | 참고용 근거가 미확인 → `PUBLIC_EVIDENCE_UNCONFIRMED` | 값이나 주체가 다름 → `PUBLIC_FACT_MISMATCH`. 필수 근거가 미확인 → `PUBLIC_EVIDENCE_UNCONFIRMED` |
| V-09 교차 검증 없음 | `public_cross_check`가 없는 규칙 | INFO `PUBLIC_CROSS_CHECK_NOT_APPLICABLE` | — | — |
| V-10 기준 checklist 최신성 | 변경안의 기준 checklist가 지금도 그 공문군의 최신 적용 일정에 있는가 | 있음 | — | 없음 → `BASE_CHECKLIST_STALE` |
| V-11 대상 공문 유효성 | 검증 시각 기준으로 대상 공문이 철회되지 않았는가 | 유효 | — | 철회 → `TARGET_NOTICE_WITHDRAWN` |

판정: FAIL issue가 하나라도 있으면 **FAIL**, 없고 WARN이 있으면 **WARN**, 둘 다 없으면 **PASS**. INFO는 판정에 영향이 없다.

### 사례

| 사례 | 입력 | 결과 |
|---|---|---|
| 1 | 중도상환수수료 v1 → v2 변경안(TASK-005 정답) | **PASS.** INFO 2건(교차 검증 없음) |
| 2 | 사람이 수정한 revision에서 수수료율 after를 "0.08"로 입력 | **FAIL** `VALUE_MISMATCH` |
| 3 | 시행일을 2026-10-02로 적은 변경안 | **FAIL** `EFFECTIVE_DATE_MISMATCH` |
| 4 | 대상 상품을 `kb-seller-loan-x`로 적은 변경안 | **FAIL** `UNKNOWN_PRODUCT_KEY` |
| 5 | 테스트 fixture로 "12e-1" 같은 비정상 문자열 | **FAIL** `INVALID_NUMERIC_VALUE` |
| 6 | 기준 checklist가 1.0퍼센트인데 공문은 1.2 → 0.8 | **FAIL** `BEFORE_VALUE_MISMATCH` |
| 7 | 새 공문에서 한 항목이 빠진 변경안 | **WARN** `ITEM_REMOVED` |
| 8 | 셀러론 법인 한도를 2억원으로 만든 변경안, 공개 근거 20억원 확인됨 | **FAIL** `PUBLIC_FACT_MISMATCH` |
| 9 | 셀러론 변경안, 필수 공개 근거가 오래돼 확인 불가 | **FAIL** `PUBLIC_EVIDENCE_UNCONFIRMED` |
| 10 | 같은 상황에서 참고용 근거 | **WARN** `PUBLIC_EVIDENCE_UNCONFIRMED` |
| 11 | 검증 전에 공문군에 새 승인 checklist가 생김 | **FAIL** `BASE_CHECKLIST_STALE` |
| 12 | 검증 전에 대상 공문 철회 | **FAIL** `TARGET_NOTICE_WITHDRAWN` |

## 결과와 issue 일관성: 무엇을 DB 제약으로, 무엇을 서비스와 테스트로 보장하는가 (제안 2)

| 불일치 | 보장 수단 | 근거 |
|---|---|---|
| 상태가 PASS/WARN/FAIL 밖의 값 | DB CHECK | 단일 행 조건 |
| issue 심각도가 FAIL/WARN/INFO 밖의 값, 순서 중복, 결과 없는 issue | DB CHECK, PK `(result_id, issue_order)`, FK | 단일 행 또는 참조 조건 |
| **FAIL issue가 FAIL이 아닌 결과에 붙음, WARN issue가 PASS 결과에 붙음** | **DB 복합 FK + CHECK.** 결과에 `(validation_result_id, status)` UNIQUE를 두고 issue가 `(validation_result_id, result_status)`로 참조하며 `CHECK (severity = 'INFO' OR (severity = 'WARN' AND result_status IN ('WARN','FAIL')) OR (severity = 'FAIL' AND result_status = 'FAIL'))` | 행 단위로 표현 가능하므로 DB가 강제해야 한다. 서비스 검사만으로는 다른 쓰기 경로나 break-glass 정정에서 깨질 수 있다 |
| **FAIL 결과인데 FAIL issue가 0건, WARN 결과인데 WARN issue가 0건** | **서비스 검사 + 통합 테스트**, 보강으로 commit 시점 검사 trigger(`CONSTRAINT TRIGGER ... DEFERRABLE INITIALLY DEFERRED`) | 개수 조건이라 단일 행 CHECK로 못 쓴다. 저장은 한 트랜잭션이므로 서비스가 저장 전 집계를 검사하면 정상 경로에서는 깨지지 않는다. DB까지 막으려면 commit 시점 trigger가 필요하며, 이 프로젝트는 이미 trigger 기반 보호를 쓰므로 비용이 작다. 추천: trigger까지 둔다 |
| 결과 저장 뒤 issue 저장 실패 | 한 트랜잭션 rollback | 업무규칙 1. 테스트로 중간 실패를 유도해 결과 0건을 확인 |
| 결과 수정/삭제 | append-only 가드 trigger와 보호 목록 | R-01 |

결론: 행 단위 불일치는 DB 제약, 개수 단위 불일치는 서비스 검사와 테스트에 commit 시점 trigger를 더한다. 서비스 검사만으로는 충분하지 않다.

## 적용 공문 조회 반영 (제안 1, 조건부 승인 반영)

- 상태 매핑(선택된 공문에 승인 checklist가 없을 때): 변경안 없음 → `PENDING_VALIDATION`(기존). 변경안 있음, 보이는 검증 결과 없음 → `PENDING_VALIDATION`. 최신 결과 PASS 또는 WARN → `PENDING_REVIEW`. 최신 결과 FAIL → `VALIDATION_FAILED`. 최신 결과가 `max_validation_age`보다 오래됨 → `VALIDATION_STALE`.
- 가시성: `created_at <= knownAt`인 변경안 중 최신 revision(supersedes 체인의 끝)을 고르고, 그 변경안의 결과 중 `validated_at <= knownAt`인 최신 것만 쓴다. 조회 기준 시각 이후의 결과는 보이지 않는다.
- 사용 허용: 어떤 상태에서도 사람 결정(TASK-007) 전에는 `internalChecklistUseAllowed=false`. 차단 사유에 `HUMAN_REVIEW_PENDING`(PASS/WARN일 때) 또는 `VALIDATION_FAILED`/`VALIDATION_STALE`을 남긴다.
- 어떤 변경안의 결과인지 구분: 응답에 **nullable 필드 2개**(`validatedProposalId`, `validationResultId`)를 추가한다(제안 1a, 판단 필요). TASK-005에서 보류한 `latestProposalId`를 이 형태로 대체한다. 필드 추가 외 기존 필드 의미는 바꾸지 않는다.

## Acceptance Criteria (구현 전 고정, 수정안)

결정자 / 판단 근거 / 검토 대상 revision 또는 PR: 사용자 / ADR-003, ADR-008, 제안 1~3 판단 / 이 문서 PR. **확정 대기.**

| ID | 입력/상황 | 기대 결과 | 검증 방법 | 결과/evidence |
|---|---|---|---|---|
| AC-01 | 사례 1 | 결과 PASS, FAIL/WARN issue 0, INFO 2. `validator_version`, `proposal_id`, `proposal_hash`(after_hash), `validated_at` 저장 | 통합 테스트 | 미검증 |
| AC-02 | 사례 2 | FAIL, issue `VALUE_MISMATCH`(rule_key, 공문 값과 변경안 값 세부) | 통합 테스트(테스트 SQL로 revision 삽입) | 미검증 |
| AC-03 | 사례 3 | FAIL `EFFECTIVE_DATE_MISMATCH` | 통합 테스트 | 미검증 |
| AC-04 | 사례 4 | FAIL `UNKNOWN_PRODUCT_KEY` | 통합 테스트 | 미검증 |
| AC-05 | 사례 5, 6 | FAIL `INVALID_NUMERIC_VALUE`, FAIL `BEFORE_VALUE_MISMATCH` | 통합 테스트 | 미검증 |
| AC-06 | 사례 7 | WARN `ITEM_REMOVED`, 결과 WARN | 통합 테스트 | 미검증 |
| AC-07 | 사례 8, 9, 10 (셀러론 기준 checklist 예시 사용) | FAIL `PUBLIC_FACT_MISMATCH`, FAIL `PUBLIC_EVIDENCE_UNCONFIRMED`(필수), WARN(참고용). 결과에 사용한 공개 관측, 약관 version, 근거, fact ID 저장. 셀러론 예시는 `origin=FIXTURE`, 사람 검토 승인이 있으면 적재 거부, v1 기간 조회가 `AVAILABLE`이어도 사용 불가, production에서 적재 설정이 켜지면 기동 거부 | 통합 테스트 + 적용 공문 조회 테스트 + 컨텍스트 테스트 | 미검증 |
| AC-08 | 사례 11, 12 | FAIL `BASE_CHECKLIST_STALE`, FAIL `TARGET_NOTICE_WITHDRAWN` | 통합 테스트 | 미검증 |
| AC-09 | 같은 변경안 두 번 검증 | 결과 2건, 이전 결과 불변, 실행 기록 2건. 결과 ID가 다름 | 통합 테스트 | 미검증 |
| AC-10 | 과거 검증 재조회 | 검증 뒤 공개 관측이 추가돼도 저장된 결과와 issue, 참조 ID는 그대로 | 통합 테스트 | 미검증 |
| AC-11 | 원자성과 일관성 | (a) issue 저장 중 실패를 유도하면 결과, issue, 성공 실행 기록이 모두 없고 실패 실행 기록만 있음. (b) FAIL issue를 PASS 결과에 붙이면 DB가 거부(복합 FK + CHECK). (c) FAIL 결과를 FAIL issue 없이 commit하면 commit 시점 trigger가 거부. (d) 신규 3개 테이블 append-only 가드와 보호 목록(60 → 66) | 통합 테스트 + schema 테스트 | 미검증 |
| AC-12 | 적용 공문 조회 반영 | (a) 변경안만 있음 `PENDING_VALIDATION`, PASS/WARN `PENDING_REVIEW`, FAIL `VALIDATION_FAILED`, 오래됨 `VALIDATION_STALE`. (b) `knownAt`을 검증 시각보다 앞으로 두면 그 결과가 보이지 않음. (c) 응답의 `validatedProposalId`, `validationResultId`가 쓰인 결과를 가리킴(제안 1a 채택 시). (d) 모든 경우 `internalChecklistUseAllowed=false`이고 PASS일 때 차단 사유 `HUMAN_REVIEW_PENDING`. (e) 기존 필드 의미 불변, 정답표 fixture 확장 | 통합 테스트 + 정답표 | 미검증 |
| AC-13 | production | 검증 명령 설정 또는 셀러론 예시 적재 설정이 켜지면 기동 거부(`DEMO_FEATURE_ENABLED_IN_PROD`). `max_validation_age`와 validation policy version은 production 필수 설정이며 없거나 0 이하면 기동 거부 | 컨텍스트 테스트 | 미검증 |
| AC-14 | 계약과 회귀 | 검증 결과 schema와 정답표 fixture Python 테스트. 기존 Java 106개와 Python 60개 유지 + 신규 통과, skip 0 | 로컬 + CI | 미검증 |
| AC-15 | 문서 | README에 "자동 검증 구현, 사람 검수와 승인 미구현, 검증 통과는 사용 허용이 아님", core README에 명령, evidence | diff 검토 | 미검증 |

## Implementation Plan (초안)

1. **V7 migration.** `automated_validation_result(validation_result_id 'validation:' + run hex PK, dataset_class='DERIVED', proposal_id FK, proposal_hash, validator_version, status, validated_at, public_evidence_refs jsonb, UNIQUE (validation_result_id, status))`, `automated_validation_issue(validation_result_id, result_status, issue_order, severity, code, rule_key NULL, message, details jsonb, PK (result_id, issue_order), FK (result_id, result_status) → result, 심각도와 result_status CHECK)`, commit 시점 개수 검사 constraint trigger, `validation_run(...)`. 셀러론 예시 데이터 2개(TASK-005 형식). 보호 테이블 절차(60 → 66).
2. **Validator.** 순수 Java `ProposalValidator`: 입력(변경안 항목, 대상 규칙, 기준 항목, 공문 상태, 공개 fact 조회 결과) → issue 목록과 판정. SQL 없음.
3. **공개 교차 검증 연결.** 기존 `PublicProductObservedStateService`와 `PublicEvidenceConfirmationPolicy`를 `validated_at` 기준으로 호출. 사용한 ID를 결과에 저장.
4. **저장.** 결과 + issue + 실행 기록 한 트랜잭션. 저장 전 서비스가 개수 일관성 검사.
5. **Command.** `@Profile("!prod")` + `trust-agent.proposal-validation.enabled`. 인자 proposal-id, run-id, validator-version. production 거부 목록에 설정 키 추가.
6. **적용 공문 조회 연결.** 가시성 규칙, 상태 매핑, `HUMAN_REVIEW_PENDING` 사유, 응답 필드 2개 추가(제안 1a), `max_validation_age` 설정.
7. **테스트와 문서.** 통합 테스트 2클래스, schema 테스트 갱신, 정답표 확장, Python 계약, evidence.

변경 파일 예상: V7 SQL 1, 계약 2, fixture 4~5, Java main 7~8, Java test 5~6, Python test 1, 문서 3.

인간의 계획 판단 / 승인 범위: **계획 작성 승인, 제안 3건 판단 반영. 구현 착수는 별도.**

## AI 제안 및 인간 판단 기록

### 제안 1: 적용 공문 조회 상태에 검증 결과 반영

**판단**
- [x] 수정 (조건부 승인)
- 판단자 / 검토 대상 revision 또는 PR: 사용자 / 이 문서 PR
- 이유 / 승인 범위: 검증 PASS만으로 사용을 허용하지 않고 인간 승인 조건 유지. 조회 기준 시각 이후의 검증 결과 제외. 어떤 변경안에 대한 결과인지 구분.

### 제안 1a: 응답에 `validatedProposalId`, `validationResultId` 추가 (새 제안)

**AI 제안**
내용: "어떤 변경안의 결과인지 구분" 조건을 응답에서 충족하려면 nullable 필드 2개가 필요하다. TASK-005에서 보류한 `latestProposalId`를 이 형태로 대체한다.
대안: 차단 사유 문자열에만 포함(기계 판독 불가), 또는 필드 추가 없이 DB에서만 구분.
**판단**
- [ ] 채택 [ ] 수정 [ ] 거절
- 판단자 / 검토 대상 revision 또는 PR: 사용자 / 판단 대기

### 제안 2: 결과와 issue 일관성 보장 수단

**판단**
- [x] 수정
- 판단자 / 검토 대상 revision 또는 PR: 사용자 / 이 문서 PR
- 이유 / 승인 범위: 결과와 세부 오류를 한 트랜잭션으로 저장하고 중간 실패 시 전부 취소. 서비스 검사와 테스트만으로 충분한지와 DB 제약이 필요한 불일치의 근거를 제시(위 "결과와 issue 일관성" 절). AI 결론: 행 단위 불일치는 DB 복합 FK + CHECK, 개수 단위는 서비스 검사 + 테스트 + commit 시점 trigger.

### 제안 3: 셀러론 기준 checklist 예시 추가

**판단**
- [x] 채택 (교차 검증 테스트 용도)
- 판단자 / 검토 대상 revision 또는 PR: 사용자 / 이 문서 PR
- 이유 / 승인 범위: FIXTURE 출처 표시, production 차단, 실제 승인과 사용 허용 우회 금지 조건을 TASK-005 예시와 동일하게 적용(AC-07).

## Implementation Result / AI self-review / 인간 검수 / 결정 기록

미실행 / 미기록. 구현은 사용자 착수 승인 뒤.
