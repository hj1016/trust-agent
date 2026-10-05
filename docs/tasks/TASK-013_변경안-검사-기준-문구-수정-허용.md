# TASK-013 변경안 검사 기준 조정: 설명 문구 수정 허용과 구조화 값 고정

- 상태: **검증·검수 대기** (완료 조건 7개와 계획 범위로 구현 착수 승인: 사용자. 구현 PR 검수 대기. 완료와 인간 검수는 미기록)
- 담당자 / 인간 결정자: AI 조사·초안 / 사용자 범위·판정·검수
- 요구사항 출처: TASK-007 구현 중 확인한 한계(검수자가 설명 문구만 고친 revision도 V-01 `VALUE_MISMATCH` FAIL이라 승인 불가), TASK-006 검사 V-01~V-06, CLAUDE.md "AI는 금리·한도 확정 주체가 아니다"
- 관련 Issue / PR / ADR / 이전 Task: TASK-006(완료, PR #21), TASK-007(구현 PR #24), ADR-003

## Goal / 관련 요구사항

- Goal: 검수자가 변경안의 **설명 문구**는 고칠 수 있되, 공문이 정한 **숫자·단위·시행일·대상 상품·조건·예외**는 바꿀 수 없게 자동 검사(V-01)를 나눈다. 그래야 TASK-007의 수정(MODIFY) 결정이 실제로 승인 가능한 revision을 만들 수 있다.
- 현재 문제: V-01은 항목 전체(`rule_key`, `instruction`, `evidence_required`, `structured_change` 전체)를 공문의 구조화 규칙과 비교한다. 설명 문구 한 글자만 달라도 FAIL이다(TASK-007 AC-07 테스트 `modifyCreatesRevisionThatNeedsRevalidationBeforeApproval`가 이 경로를 보인다).
- 사용자: 검수자(수정 결정), 행원(승인 checklist 사용).
- 업무규칙(제안):
  1. **수정 가능한 것**: `instruction`(설명 문구). 공문 규칙의 뜻을 바꾸지 않는 안내 문구 보완이 목적이다.
  2. **반드시 유지해야 하는 것**: `structured_change`의 `field_key`, `change_type`, `before_value`, `after_value`, `unit`, `effective_on`, `applicable_product_keys`, `conditions`, `exceptions`. 그리고 `rule_key`. 이 값들은 공문이 정한 사실이며 사람이 바꾸면 공문과 어긋난다.
  3. `evidence_required`는 유지 대상(제안 1 채택). 근거 필요 여부를 사람이 끄는 것은 공문 적용의 안전장치를 약화시키므로 허용하지 않는다.
  4. 설명 문구를 고친 revision은 결과에 WARN `INSTRUCTION_EDITED`(rule_key, 공문 문구와 고친 문구)를 남긴다. 승인 시 WARN 사유 필수 규칙(TASK-007)이 그대로 적용되어 검수자가 왜 고쳤는지 기록해야 승인된다.
  5. 구조화 값이 하나라도 다르면 지금처럼 FAIL `VALUE_MISMATCH`이고 세부에 어떤 필드가 다른지 적는다.
  6. **자동 검증은 문구의 의미를 보장하지 않는다(명시된 한계).** 구조화 값이 같아도 고친 설명 문구가 업무 의미를 왜곡할 수 있다(예: 숫자는 같은데 적용 대상을 반대로 설명). 자동 검사는 문구가 공문과 다르다는 사실만 WARN으로 알리고, 검수자가 공문 원문과 고친 문구를 직접 대조해 승인 사유에 남겨야 한다. 이 대조 절차와 한계는 검수 자료와 응답 면책 문구에 포함한다(제안 3 채택). 문구 안의 숫자 검사는 후속 검토.
- 상태전이: 변경 없음(TASK-006/007 흐름 유지). 바뀌는 것은 V-01의 판정 결과뿐.
- 데이터 영향: 결과 issue에 새 코드 `INSTRUCTION_EDITED`(WARN)와 `VALUE_MISMATCH` 세부의 `mismatched_fields` 추가. 테이블 변경 없음. 계약 schema의 코드 목록 갱신.
- Out of Scope: 문구 품질·의미 자동 평가(사람 대조로 대체), LLM 사용, 조건·예외 문구의 부분 수정 허용.

## 바뀌는 검사 (V-01 분리안)

| 번호 | 검사 | 통과 | 주의(WARN) | 실패(FAIL) |
|---|---|---|---|---|
| V-01a 구조화 값 일치 | `rule_key`, `evidence_required`, `structured_change` 전체가 공문 규칙과 같은가 | 같음 | — | 다름 → `VALUE_MISMATCH`(세부 `mismatched_fields`) |
| V-01b 설명 문구 일치 | `instruction`이 공문 규칙과 같은가 | 같음 | 다름 → `INSTRUCTION_EDITED`(공문 문구, 고친 문구) | — |

TASK-006 사례 2(수수료율 "0.08")는 V-01a FAIL로 그대로 잡힌다. TASK-007 AC-07의 "문구 보완" revision은 WARN이 되어 사유를 적으면 승인할 수 있다.

## Acceptance Criteria (초안)

| AC | 사례 | 기대 결과 |
|---|---|---|
| AC-01 | 설명 문구만 고친 revision 검증 | WARN `INSTRUCTION_EDITED`(rule_key, 두 문구), FAIL 없음 |
| AC-02 | after_value "0.08", 시행일, 단위, 대상 상품, 조건, 예외, rule_key, evidence_required 중 하나라도 다른 revision | FAIL `VALUE_MISMATCH`, 세부 `mismatched_fields`에 다른 필드명 |
| AC-03 | 문구 고친 revision 승인 | 사유 없으면 `REASON_REQUIRED`, 사유 있으면 승인되고 발행된 checklist 항목의 문구는 고친 문구, 구조화 값은 공문 값 |
| AC-04 | 기존 정답표와 회귀 | TASK-006 정답 파일(PASS, INFO 2건) 불변, 기존 Java/Python 테스트 유지 |
| AC-05 | 계약 | 검증 결과 schema 코드 목록에 `INSTRUCTION_EDITED` 추가, Python 계약 테스트 |
| AC-06 | 문서 | TASK-006 문서에 "V-01을 TASK-013에서 V-01a/V-01b로 분리" 이력, README에 "자동 검증은 설명 문구의 의미를 보장하지 않는다" 명시 |
| AC-07 | 사람 대조 절차 | 검수 자료에 공문 원문 문구와 고친 문구를 나란히 보여주는 항목(규칙 키, 원문 문구, 고친 문구, 구조화 값 동일 여부)을 포함하고, WARN `INSTRUCTION_EDITED`의 세부에 두 문구가 저장돼 승인 사유와 함께 추적된다. 자동 검증이 문구 의미를 보장하지 않는다는 한계를 Task 문서와 evidence에 명시 |

## Implementation Plan (초안)

1. `ProposalValidator.validateItem`: 비교를 두 단계로 나눔(구조화 값 비교 → FAIL, 문구 비교 → WARN). 세부에 `mismatched_fields`.
2. 단위 테스트 사례 추가, TASK-007 AC-07 테스트의 "문구 보완" 경로 기대값을 WARN 승인 가능으로 갱신.
3. 계약 schema와 정답표, 문서.

예상 크기: 작음(코드 ~60행, 테스트 ~6건).

## AI 제안 및 인간 판단 기록

### 제안 1: `evidence_required`를 유지 대상에 둔다

- 내용: 근거 필요 여부는 사람이 끌 수 없다. 대안: 사유 기록 시 허용.

**판단**
- [x] 채택
- 판단자 / 검토 대상 revision 또는 PR: 사용자 / PR #25
- 이유 / 승인 범위: 설명 문구만 수정 가능. 규칙 키, 변경 전후 값, 단위, 시행일, 대상 상품, 조건, 예외, 근거 필요 여부는 유지.

### 제안 2: 문구 수정은 WARN + 승인 사유 필수

- 내용: 문구 수정을 무음으로 통과시키지 않고 WARN으로 남겨 승인 시 사유를 강제한다. 대안: INFO로만 남긴다(사유 불필요).

**판단**
- [x] 채택
- 판단자 / 검토 대상 revision 또는 PR: 사용자 / PR #25
- 이유 / 승인 범위: 설명 문구만 달라지면 WARN `INSTRUCTION_EDITED`로 기록하고 승인 사유를 필수로 한다.

### 제안 3: 문구 의미는 사람이 대조한다 (사용자 지시로 추가)

- 내용: 구조화 값이 같아도 설명 문구가 업무 의미를 왜곡할 수 있다. 자동 검증은 문구의 의미를 보장하지 않는다는 한계를 명시하고, 검수자가 공문 원문과 수정 문구를 대조해야 한다는 점을 검수 자료에 포함한다(AC-07).

**판단**
- [x] 채택
- 판단자 / 검토 대상 revision 또는 PR: 사용자 / PR #25
- 이유 / 승인 범위: 계획 승인까지. 구현 착수는 별도 승인.

## Implementation Result / AI self-review / 인간 검수 / 결정 기록

### 구현 결과 (AI 작성, 사실)

- 검증 대상: 브랜치 `feat/validation-instruction-edit`(PR 본문에 commit 기재). 상세: [검증 기록](../evidence/VALIDATION_INSTRUCTION_EDIT_EVIDENCE.md).
- 바뀐 코드: `ProposalValidator`의 V-01을 V-01a(규칙 키, 근거 필요 여부, 구조화 변경 전체 비교 → FAIL `VALUE_MISMATCH`, 세부 `mismatched_fields`)와 V-01b(설명 문구 비교 → WARN `INSTRUCTION_EDITED`, 세부에 원문 문구와 고친 문구)로 나눴다. 업무 값과 문구가 함께 다르면 FAIL만 남는다. 승인 서비스는 바꾸지 않았다(WARN 승인 사유 필수 규칙이 그대로 적용된다).
- 계약: 검증 결과 schema의 issue 코드가 등록 목록(enum)으로 바뀌었고 `INSTRUCTION_EDITED`가 들어갔다.
- Java `./gradlew clean test bootJar --offline --no-daemon`: 144건 실행, 통과 144, 실패 0, 건너뜀 0 (기존 142 + 신규 2). Python 로컬(비공개 artifact): 63건 실행, 통과 63, 건너뜀 0 (기존 62 + 신규 1). 공개 CI 조건: 63건, 통과 61, 건너뜀 2.
- AC-01~07 자동 검증 통과. 인간 검수와 완료 판정은 미실시.

### AI self-review

- 유지 대상(규칙 키, 변경 전후 값, 단위, 시행일, 대상 상품, 조건, 예외, 근거 필요 여부)은 각각 단위 테스트로 FAIL과 필드명 기록을 확인했다. TASK-006 사례 2(0.08)와 정답 파일은 그대로다.
- 자동 검증은 문구의 의미를 보장하지 않는다. WARN 세부에 두 문구를 저장하고 승인 사유를 강제하는 것까지가 자동화 범위이며, 대조는 사람이 한다(검수 자료 AC-07).
- 범위 밖 변경 없음: 조건·예외 문구의 부분 수정, 문구 품질 자동 평가, LLM 사용 없음.

### 인간 검수 / 결정

미기록. 사용자 검수 뒤 기록.
