# TASK-013 변경안 검사 기준 조정: 설명 문구 수정 허용과 구조화 값 고정

- 상태: **초안** (AI 제안. 계획 작성 승인, 구현 착수 승인 모두 아님)
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
  3. `evidence_required`는 유지 대상(제안). 근거 필요 여부를 사람이 끄는 것은 공문 적용의 안전장치를 약화시키므로 허용하지 않는다. 대안: 사유를 남기면 허용. 판단 필요.
  4. 설명 문구를 고친 revision은 결과에 WARN `INSTRUCTION_EDITED`(rule_key, 공문 문구와 고친 문구)를 남긴다. 승인 시 WARN 사유 필수 규칙(TASK-007)이 그대로 적용되어 검수자가 왜 고쳤는지 기록해야 승인된다.
  5. 구조화 값이 하나라도 다르면 지금처럼 FAIL `VALUE_MISMATCH`이고 세부에 어떤 필드가 다른지 적는다.
  6. 설명 문구가 비어 있거나 공문 문구와 완전히 무관한지(예: 숫자가 들어 있는데 공문 값과 다름)는 이번 범위에서 자동 판단하지 않는다. 문구 안의 숫자 검사는 후속 검토.
- 상태전이: 변경 없음(TASK-006/007 흐름 유지). 바뀌는 것은 V-01의 판정 결과뿐.
- 데이터 영향: 결과 issue에 새 코드 `INSTRUCTION_EDITED`(WARN)와 `VALUE_MISMATCH` 세부의 `mismatched_fields` 추가. 테이블 변경 없음. 계약 schema의 코드 목록 갱신.
- Out of Scope: 문구 품질 평가, LLM 사용, 조건·예외 문구의 부분 수정 허용.

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
| AC-06 | 문서 | TASK-006 문서에 "V-01을 TASK-013에서 V-01a/V-01b로 분리" 이력, README |

## Implementation Plan (초안)

1. `ProposalValidator.validateItem`: 비교를 두 단계로 나눔(구조화 값 비교 → FAIL, 문구 비교 → WARN). 세부에 `mismatched_fields`.
2. 단위 테스트 사례 추가, TASK-007 AC-07 테스트의 "문구 보완" 경로 기대값을 WARN 승인 가능으로 갱신.
3. 계약 schema와 정답표, 문서.

예상 크기: 작음(코드 ~60행, 테스트 ~6건).

## AI 제안 및 인간 판단 기록

### 제안 1: `evidence_required`를 유지 대상에 둔다

- 내용: 근거 필요 여부는 사람이 끌 수 없다. 대안: 사유 기록 시 허용.

**판단**
- [ ] 채택 [ ] 수정 [ ] 거절
- 판단자 / 검토 대상 revision 또는 PR: 사용자 / 판단 대기

### 제안 2: 문구 수정은 WARN + 승인 사유 필수

- 내용: 문구 수정을 무음으로 통과시키지 않고 WARN으로 남겨 승인 시 사유를 강제한다. 대안: INFO로만 남긴다(사유 불필요).

**판단**
- [ ] 채택 [ ] 수정 [ ] 거절
- 판단자 / 검토 대상 revision 또는 PR: 사용자 / 판단 대기

## Implementation Result / AI self-review / 인간 검수 / 결정 기록

미실행 / 미기록.
