# TASK-006 checklist 변경안의 자동 검증

- 상태: **계획 검토 대기** (계획 작성 승인. 구현 착수 승인은 아님)
- 담당자 / 인간 결정자: AI(계획, 구현, 자동 검증, self-review) / 사용자(범위, 완료 확인 조건 확정, 제안 판단, 착수 승인, 검수)
- 요구사항 출처: [PLAN-001](PLAN-001_중도상환수수료-흐름-Task-분할-초안.md) TASK-006, [ADR-003](../adr/ADR-003-validation-and-human-approval.md) 자동 검증과 사람 승인 분리, [ADR-008](../adr/ADR-008-internal-notice-effective-policy-and-review.md) "자동 검증" 절, README MVP 목표 5단계 "숫자 및 시행일 오류 차단".
- 관련: [TASK-005](TASK-005_checklist-변경-후보-proposal-생성.md)(입력인 변경안), 후속 TASK-007(사람 결정은 검증 결과를 입력으로 받는다)

## Goal / 관련 요구사항

- Goal: 변경안(proposal)이 공문 원문의 구조화 규칙과 어긋나지 않는지, 숫자와 시행일이 맞는지, 공개 근거와 충돌하지 않는지를 **자동으로 검사해 통과(PASS), 주의(WARN), 실패(FAIL)로 판정**하고 근거와 함께 append-only로 저장한다. 사람 검수자는 이 결과를 보고 결정한다(TASK-007). 자동 검증 통과는 승인이 아니다(ADR-003).
- 사용자: 공문 담당 부서 검수자(합성). 후속 사람 결정 명령.
- 사전조건: TASK-005 병합(변경안과 항목 테이블, 예시 승인 checklist). PostgreSQL V6. 공개 상품 baseline 적재(교차 검증용).
- 입력: `proposal_id`, 실행 `run_id`, validator version(설정값, 예: `proposal-validator-v1`). 검증 시각은 서버 시계에서 한 번 읽는다(`validated_at`).
- 업무규칙(ADR-008 요약과 구체화):
  1. 검증 결과는 별도 테이블에 append-only로 저장한다. 다시 검증하면 이전 결과를 고치지 않고 새 결과를 추가한다. 과거 조회에는 `validated_at <= knownAt`인 결과만 보인다.
  2. 결과는 PASS, WARN, FAIL 셋 중 하나다. 실패 항목이 하나라도 있으면 FAIL, 실패는 없고 주의가 있으면 WARN, 둘 다 없으면 PASS.
  3. 검증은 변경안 저장 당시 해시(`after_hash`)를 함께 기록한다. 변경안이 바뀌면(새 revision) 다시 검증해야 한다.
  4. 공개 근거 교차 검증은 구조화된 값(fact_key, subject_type, value_type, value, unit)으로 비교한다. 문자열 포함 여부로 비교하지 않는다. 공개 정보가 달라도 내부 규칙이나 공문을 자동 수정하지 않는다.
  5. 교차 검증에 쓴 공개 관측, 약관 version, 근거, fact 식별자를 결과에 저장한다. 과거 검증을 다시 볼 때 현재 공개 상태로 결과를 바꾸지 않는다.
  6. 자동 검증은 대출 판단이 아니며 checklist 사용 허용을 직접 바꾸지 않는다. 사용 허용에는 사람 결정(TASK-007)이 더 필요하다.
- 상태전이: 변경안 존재 → `validate` → 검증 결과 1건 + 항목 N건 + 실행 기록. 금지: 결과 수정/삭제, 검증 결과로 승인 상태 변경, 공문 자동 수정.
- 데이터 영향: 읽기 변경안과 항목, 대상 공문 규칙, 기준 checklist 항목, 공개 상품과 관측과 약관 fact, 공문 철회 사건. 쓰기(신규) `automated_validation_result`, `automated_validation_issue`, `validation_run`. 불변 조건: append-only 가드, 결과당 issue 순서 유일, 상태와 issue 심각도의 일관성(FAIL이면 FAIL issue 1개 이상).
- API: HTTP endpoint 추가 없음(CLI 실행). 적용 공문 조회 응답의 **필드 집합은 유지**하되, `checklistAvailabilityStatus`가 ADR-008에 이미 정의된 값(`PENDING_REVIEW`, `VALIDATION_FAILED`, `VALIDATION_STALE`)을 실제로 내기 시작한다(제안 1).
- 트랜잭션: 결과 + issue + 실행 기록을 하나의 트랜잭션. 공개 근거 조회는 같은 트랜잭션 안의 읽기만. 외부 호출 없음.
- 권한: runtime 계정에 신규 3개 테이블 조회와 추가. 적재 도구 계정 권한 없음. 검증 명령은 test/demo profile 전용이며 production에서 설정이 켜지면 기동 거부(TASK-005와 같은 방식, 설정 키 추가).
- 실패 시나리오: 변경안 없음 → `PROPOSAL_NOT_FOUND` 실행 기록. 공개 근거 조회 실패(DB 오류) → 검증 결과를 만들지 않고 실행 기록 FAILED. 같은 변경안 재검증 → 새 결과 추가(허용). 동시 검증 → 두 결과 모두 저장되며 최신 것이 조회에 쓰인다(결과 ID는 실행마다 다름).
- Out of Scope: 사람 결정과 승인 checklist 발행(TASK-007), LLM 판단, 화면, HTTP 노출, 셀러론 공문군의 승인 checklist 예시(제안 3에서 판단).

## 무엇을 검사하고 어떻게 판정하는가

변경안의 항목마다 아래 검사를 적용한다. 각 검사는 issue 코드, 심각도(FAIL/WARN/INFO), 대상 rule_key, 메시지, 세부(JSON)를 남긴다.

| 번호 | 검사 | 통과 | 주의(WARN) | 실패(FAIL) |
|---|---|---|---|---|
| V-01 변경 후 값 일치 | 항목의 변경 후 내용(after)이 대상 공문의 구조화 규칙과 같은가 | 같음 | — | 다름 → `VALUE_MISMATCH`. 사람이 수정한 revision(TASK-007)에서 공문과 어긋난 값을 잡는다 |
| V-02 시행일 일치 | 구조화 변경의 `effective_on`이 대상 공문 `effective_from`과 같은가 | 같음 | — | 다름 또는 없음 → `EFFECTIVE_DATE_MISMATCH` |
| V-03 변경 전 값 연속성 | 수정 항목에서 공문이 말하는 변경 전 값(`before_value`)이 기준 checklist의 현재 값과 같은가 | 같음 | 공문에 변경 전 값이 없음 → `BEFORE_VALUE_NOT_STATED` | 다름 → `BEFORE_VALUE_MISMATCH` (예: 공문은 1.2 → 0.8인데 기준 checklist는 1.0) |
| V-04 숫자 형식과 범위 | 단위가 PERCENT 또는 KRW인 값이 문자열 십진수 또는 정수이고 범위가 합리적인가(퍼센트 0 이상 100 이하, 원 0 이상) | 맞음 | — | 부동소수점, 빈 값, 범위 밖 → `INVALID_NUMERIC_VALUE` |
| V-05 대상 상품 존재 | `applicable_product_keys`의 각 키가 공개 상품 목록(`public_product`)에 있는가 | 있음 | — | 없음 → `UNKNOWN_PRODUCT_KEY` |
| V-06 조건과 예외 | 숫자 정책 변경에 조건이 1개 이상 있는가 | 있음 | 없음 → `MISSING_CONDITIONS` | — |
| V-07 삭제 항목 | 기준 checklist에 있던 항목이 새 공문에서 빠졌는가 | 삭제 없음 | 삭제 있음 → `ITEM_REMOVED` (사람이 의도된 삭제인지 확인) | — |
| V-08 공개 근거 교차 검증 | 공문 규칙에 `public_cross_check`가 있을 때 현재 공개 fact와 값, 주체, 단위가 같은가. 공개 근거는 기존 freshness 정책(`PublicEvidenceConfirmationPolicy`)으로 확인 | 같고 확인됨 | 참고용(INFORMATIONAL) 근거가 미확인(대기, 실패, 오래됨, 없음) → `PUBLIC_EVIDENCE_UNCONFIRMED` | 값이나 주체가 다름 → `PUBLIC_FACT_MISMATCH`. 필수(REQUIRED) 근거가 미확인 → `PUBLIC_EVIDENCE_UNCONFIRMED` |
| V-09 교차 검증 없음 | `public_cross_check`가 없는 규칙 | INFO `PUBLIC_CROSS_CHECK_NOT_APPLICABLE` 기록 | — | — |
| V-10 기준 checklist 최신성 | 변경안의 기준 checklist가 지금도 그 공문군의 최신 적용 일정에 있는가 | 있음 | — | 없음(그 사이 새 승인) → `BASE_CHECKLIST_STALE` |
| V-11 대상 공문 유효성 | 검증 시각 기준으로 대상 공문이 철회되지 않았는가 | 유효 | — | 철회 → `TARGET_NOTICE_WITHDRAWN` |

판정: FAIL issue가 하나라도 있으면 **FAIL**, 없고 WARN이 있으면 **WARN**, 둘 다 없으면 **PASS**. INFO는 판정에 영향이 없다.

### 사례

| 사례 | 입력 | 결과 |
|---|---|---|
| 1 | 중도상환수수료 v1 → v2 변경안(TASK-005 정답) | **PASS.** 항목 2개 모두 V-01, V-02 통과. V-03: 공문 before_value "1.2" = 기준 checklist "1.2". V-05: `kb-seller-loan` 존재. V-06 조건 2개. V-09 INFO 2건(교차 검증 없음) |
| 2 | 사람이 수정한 revision에서 수수료율 after를 "0.08"로 입력 | **FAIL** `VALUE_MISMATCH`(공문은 "0.8") |
| 3 | 시행일을 2026-10-02로 적은 변경안 | **FAIL** `EFFECTIVE_DATE_MISMATCH`(공문 시행일 2026-10-01) |
| 4 | 대상 상품을 `kb-seller-loan-x`로 적은 변경안 | **FAIL** `UNKNOWN_PRODUCT_KEY` |
| 5 | 수수료율을 1.2(부동소수점)로 저장하려는 변경안 | 생성 단계에서 거부되므로 변경안이 없음. 테스트 fixture로 "12e-1" 같은 비정상 문자열을 넣으면 **FAIL** `INVALID_NUMERIC_VALUE` |
| 6 | 기준 checklist가 1.0퍼센트인데 공문은 1.2 → 0.8 | **FAIL** `BEFORE_VALUE_MISMATCH` |
| 7 | 새 공문에서 한 항목이 빠진 변경안 | **WARN** `ITEM_REMOVED`. 사람이 확인 후 승인 가능(TASK-007에서 WARN 승인 시 사유 필수) |
| 8 | 셀러론 법인 한도를 2억원으로 만든 변경안(ADR-008 fixture), 공개 근거 20억원 확인됨 | **FAIL** `PUBLIC_FACT_MISMATCH` |
| 9 | 셀러론 변경안, 공개 근거가 오래돼(stale) 확인 불가, 필수(REQUIRED) | **FAIL** `PUBLIC_EVIDENCE_UNCONFIRMED` |
| 10 | 같은 상황에서 참고용(INFORMATIONAL) | **WARN** `PUBLIC_EVIDENCE_UNCONFIRMED` |
| 11 | 검증 전에 공문군에 새 승인 checklist가 생김 | **FAIL** `BASE_CHECKLIST_STALE` |
| 12 | 검증 전에 대상 공문 철회 | **FAIL** `TARGET_NOTICE_WITHDRAWN` |

사례 8~10은 셀러론 공문군에 기준 checklist가 있어야 변경안이 생긴다(제안 3).

## Acceptance Criteria (구현 전 고정, 초안)

결정자 / 판단 근거 / 검토 대상 revision 또는 PR: 사용자 / ADR-003, ADR-008, 이 계획 / 이 문서 PR. **확정 대기.**

| ID | 입력/상황 | 기대 결과 | 검증 방법 | 결과/evidence |
|---|---|---|---|---|
| AC-01 | 사례 1 | 결과 PASS, FAIL/WARN issue 0, INFO 2, `validator_version`, `proposal_hash`(after_hash), `validated_at` 저장 | 통합 테스트 | 미검증 |
| AC-02 | 사례 2 | FAIL, issue `VALUE_MISMATCH`(rule_key, 공문 값과 변경안 값 세부) | 통합 테스트(테스트 SQL로 revision 삽입) | 미검증 |
| AC-03 | 사례 3 | FAIL `EFFECTIVE_DATE_MISMATCH` | 통합 테스트 | 미검증 |
| AC-04 | 사례 4 | FAIL `UNKNOWN_PRODUCT_KEY` | 통합 테스트 | 미검증 |
| AC-05 | 사례 5, 6 | FAIL `INVALID_NUMERIC_VALUE`, FAIL `BEFORE_VALUE_MISMATCH` | 통합 테스트 | 미검증 |
| AC-06 | 사례 7 | WARN `ITEM_REMOVED`, 결과 WARN | 통합 테스트 | 미검증 |
| AC-07 | 사례 8, 9, 10 | FAIL `PUBLIC_FACT_MISMATCH`, FAIL `PUBLIC_EVIDENCE_UNCONFIRMED`(REQUIRED), WARN(INFORMATIONAL). 결과에 사용한 공개 관측, 약관 version, 근거, fact ID 저장 | 통합 테스트(공개 baseline + 셀러론 기준 checklist 예시, 제안 3) | 미검증 |
| AC-08 | 사례 11, 12 | FAIL `BASE_CHECKLIST_STALE`, FAIL `TARGET_NOTICE_WITHDRAWN` | 통합 테스트 | 미검증 |
| AC-09 | 같은 변경안 두 번 검증 | 결과 2건, 이전 결과 불변. 과거 `knownAt` 조회에는 그 시점 이전 결과만 | 통합 테스트 | 미검증 |
| AC-10 | 과거 검증 재조회 | 공개 상태가 바뀌어도 저장된 검증 결과와 issue는 그대로 | 통합 테스트(검증 후 공개 관측 추가) | 미검증 |
| AC-11 | 결과와 issue 제약 | FAIL 결과에 FAIL issue 0건, PASS 결과에 FAIL issue 존재, 알 수 없는 심각도 → CHECK 위반. 신규 3개 테이블 append-only 가드와 보호 목록(60 → 66) | schema 테스트 | 미검증 |
| AC-12 | 적용 공문 조회(제안 1 채택 시) | 변경안만 있음 `PENDING_VALIDATION` → 최신 검증 PASS/WARN `PENDING_REVIEW` → FAIL `VALIDATION_FAILED` → `max_validation_age` 초과 `VALIDATION_STALE`. 어느 경우에도 `internalChecklistUseAllowed=false`(사람 결정 전). 응답 필드 집합 불변 | 통합 테스트 + 정답표 fixture 확장 | 미검증 |
| AC-13 | production | 검증 명령 설정이 켜지면 기동 거부(`DEMO_FEATURE_ENABLED_IN_PROD`). `max_validation_age`와 validation policy version은 production 필수 설정이며 없거나 0 이하면 기동 거부(ADR-008) | 컨텍스트 테스트 | 미검증 |
| AC-14 | 계약과 회귀 | 검증 결과 schema와 정답표 fixture Python 테스트. 기존 Java 106개와 Python 60개 유지 + 신규 통과, skip 0 | 로컬 + CI | 미검증 |
| AC-15 | 문서 | README에 "자동 검증 구현, 사람 검수와 승인 미구현", core README에 명령, evidence | diff 검토 | 미검증 |

## Implementation Plan (초안)

1. **V7 migration.** `automated_validation_result(validation_result_id 'validation:sha256:..' 또는 run 기반 ID, dataset_class='DERIVED', proposal_id FK, validator_version, proposal_hash, status PASS/WARN/FAIL, validated_at, public_evidence_refs jsonb)`, `automated_validation_issue(validation_result_id, issue_order, severity FAIL/WARN/INFO, code, rule_key NULL, message, details jsonb)`, `validation_run(...)`. 상태와 issue 일관성은 트리거 또는 서비스 검사 + 테스트로 보장(DB 제약으로는 교차 테이블 검사가 어려움. 제안 2). 보호 테이블 절차.
2. **Validator.** 순수 Java `ProposalValidator`: 입력(변경안 항목, 대상 규칙, 기준 항목, 공문 상태, 공개 fact 조회 결과) → issue 목록과 판정. SQL 없음. 각 검사는 작은 함수.
3. **공개 교차 검증 연결.** 기존 `PublicProductObservedStateService`와 `PublicEvidenceConfirmationPolicy`를 호출해 `validated_at` 기준 fact와 freshness를 얻는다. 사용한 ID를 결과에 저장.
4. **Command.** `@Profile("!prod")` + `trust-agent.proposal-validation.enabled`. 인자 proposal-id, run-id, validator-version. 한 트랜잭션.
5. **적용 공문 조회 연결(제안 1).** 선택된 공문에 승인 checklist가 없고 변경안이 있으면 최신 visible 검증 결과로 상태를 정한다. `max_validation_age` 설정 추가.
6. **테스트와 문서.** 통합 테스트 1~2클래스, schema 테스트 갱신, 정답표 확장, Python 계약, evidence.

변경 파일 예상: V7 SQL 1, 계약 2, fixture 2~3, Java main 6~7, Java test 4~5, Python test 1, 문서 3.

인간의 계획 판단 / 승인 범위: **계획 작성만 승인. 구현 착수는 별도.**

## AI 제안 및 인간 판단 기록

### 제안 1: 적용 공문 조회 상태에 검증 결과 반영

**AI 제안**
내용: 응답 필드는 그대로 두고 `checklistAvailabilityStatus`가 ADR-008에 이미 정의된 `PENDING_REVIEW`, `VALIDATION_FAILED`, `VALIDATION_STALE`을 실제로 내게 한다. 사용 허용은 여전히 false.
대안: TASK-007까지 조회 변경 없음(검수자가 상태를 알 수 없음).
**판단**
- [ ] 채택 [ ] 수정 [ ] 거절
- 판단자 / 검토 대상 revision 또는 PR: 사용자 / 판단 대기

### 제안 2: 결과와 issue의 일관성을 서비스 검사와 테스트로 보장

**AI 제안**
내용: "FAIL 결과에는 FAIL issue가 있어야 한다"는 두 테이블에 걸친 조건이라 CHECK로 못 박기 어렵다. 서비스가 저장 전에 검사하고 통합 테스트로 고정한다. DB 수준 보강이 필요하면 트리거를 둔다.
**판단**
- [ ] 채택 [ ] 수정 [ ] 거절
- 판단자 / 검토 대상 revision 또는 PR: 사용자 / 판단 대기

### 제안 3: 셀러론 공문군의 기준 checklist 예시 추가

**AI 제안**
내용: 공개 근거 교차 검증 사례(8~10)를 검증하려면 셀러론 v1 승인 checklist 예시(origin FIXTURE)가 필요하다. TASK-005와 같은 형식으로 예시 데이터 2개를 추가한다.
대안: 교차 검증 사례를 테스트 SQL로만 만든다(데모 불가).
**판단**
- [ ] 채택 [ ] 수정 [ ] 거절
- 판단자 / 검토 대상 revision 또는 PR: 사용자 / 판단 대기

## Implementation Result / AI self-review / 인간 검수 / 결정 기록

미실행 / 미기록. 구현은 사용자 착수 승인 뒤.
