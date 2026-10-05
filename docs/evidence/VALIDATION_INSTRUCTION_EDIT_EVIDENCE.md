# 변경안 검사 기준 조정(설명 문구 수정 허용) 검증 기록 (TASK-013)

## 구현 범위

자동 검증의 V-01(변경 후 값 일치)을 둘로 나눴다. **V-01a 업무 값 일치**: 규칙 키, 근거 필요 여부, 구조화 변경(변경 전후 값, 단위, 시행일, 대상 상품, 조건, 예외, 변경 종류, 필드 키) 전체가 공문 규칙과 같아야 하며 다르면 FAIL `VALUE_MISMATCH`이고 세부 `mismatched_fields`에 다른 필드명을 적는다. **V-01b 설명 문구 일치**: 설명 문구(instruction)만 다르면 WARN `INSTRUCTION_EDITED`이고 세부에 공문 원문 문구와 고친 문구를 저장한다. 업무 값과 문구가 함께 다르면 FAIL만 남는다(먼저 업무 값을 맞춰야 한다).

승인 서비스는 바꾸지 않았다. WARN 결과는 TASK-007 규칙대로 승인 사유가 있어야 승인되고, 발행되는 checklist 항목의 문구는 고친 문구, 업무 값은 공문 값이다. 검증 결과 schema의 issue 코드는 등록 목록(enum)으로 바뀌었고 `INSTRUCTION_EDITED`가 들어갔다.

**명시된 한계**: 자동 검증은 문구가 공문과 다르다는 사실만 알린다. 문구가 업무 의미를 왜곡하는지는 판단하지 못하므로 검수자가 원문과 고친 문구를 대조하고 사유에 남겨야 한다.

## 테스트 evidence

검증 대상 revision: 브랜치 `feat/validation-instruction-edit` HEAD(PR 본문의 commit). 환경: 로컬 macOS arm64, Java 21(openjdk 21.0.8), Docker, PostgreSQL 18.6 Testcontainers, Python 3.11.8.

```text
./gradlew clean test bootJar --offline --no-daemon
144 tests completed (기존 142 + 신규 2), failures 0, errors 0, skipped 0

# 비공개 artifact를 제공한 로컬
TRUSTAGENT_PRIVATE_ARTIFACT_ROOT=<경로> TRUSTAGENT_REQUIRE_PRIVATE_SNAPSHOTS=1 python3 -m unittest discover -s tests -t .
Ran 63 tests (기존 62 + 신규 1), 통과 63, 실패 0, 건너뜀 0

# 공개 CI 조건(비공개 artifact 없음)
python3 -m unittest discover -s tests -t .
Ran 63 tests, 통과 61, 실패 0, 건너뜀 2 (사유: "비공개 snapshot artifact가 제공되지 않았습니다.")
```

| 테스트 | 보장 | AC |
|---|---|---|
| `editedInstructionIsWarnNotFailAndKeepsBothTexts` (단위, 신규) | 설명 문구만 다른 항목은 WARN `INSTRUCTION_EDITED`, FAIL 없음, 세부에 원문 문구와 고친 문구 | AC-01, 07 |
| `businessValuesOtherThanInstructionStayFailWithMismatchedFieldNames` (단위, 신규) | 근거 필요 여부, 조건, 예외, 단위, 시행일, 대상 상품, 변경 전 값이 하나라도 다르면 FAIL이고 `mismatched_fields`에 필드명. 문구와 업무 값이 함께 다르면 FAIL만 | AC-02 |
| `afterValueDifferentFromNoticeFails` (단위, 갱신) | 사례 2(0.08)는 FAIL이고 `mismatched_fields = ["structured_change.after_value"]` | AC-02, 04 |
| `modifyCreatesRevisionThatNeedsRevalidationBeforeApproval` (통합, 갱신) | 문구만 고친 revision 재검증 WARN(`INSTRUCTION_EDITED`, `CHECK_NOTICE_SOURCE`), 사유 없는 승인 `REASON_REQUIRED`, 사유 있는 승인 성공, 발행 항목 문구 = 고친 문구, 업무 값(0.8) = 공문 값, 결정에 사유 저장. 근거 필요 여부를 끈 revision은 FAIL이라 승인 불가 | AC-03 |
| 기존 TASK-006/007 테스트 전부와 정답 파일 `prepayment-fee-v2-validation.expected.json` | 불변(PASS, INFO 2건) | AC-04 |
| `test_instruction_edited_warning_is_a_known_code_and_unknown_codes_are_rejected` (Python, 신규) | WARN `INSTRUCTION_EDITED` 예시가 schema를 통과하고 미등록 코드는 거부 | AC-05 |
| 문서 diff | TASK-006 V-01 이력, README 두 곳, 이 evidence | AC-06 |

### 검수 자료: 사람 대조 항목 (AC-07)

통합 테스트가 만든 실제 자료다. 검수자는 이 표의 원문 문구와 고친 문구를 대조해 업무 의미가 바뀌지 않았는지 판단하고 승인 사유에 남긴다.

| 규칙 키 | 공문 원문 문구 | 고친 문구 | 구조화 값 동일 | 자동 검증 |
|---|---|---|---|---|
| `CHECK_NOTICE_SOURCE` | 상담 안내 전 적용 공문 버전과 원문 근거 위치를 확인한다. | 상담 안내 전 적용 공문 버전과 원문 근거 위치를 확인한다. (검수자 보완 문구) | 같음(구조화 변경 없음) | WARN `INSTRUCTION_EDITED` → 승인 사유 "원문과 대조함: 공문 뜻을 바꾸지 않는 안내 문구 보완"으로 승인 |
| `CHECK_PREPAYMENT_FEE_RATE` | (문구 동일) | (문구 동일) | 같음(0.8, PERCENT, 2026-10-01) | 통과 |

같은 세부가 결과 issue의 `details.notice_instruction`, `details.proposal_instruction`에 저장되어 승인 사유와 함께 추적된다.

## 한계

- 자동 검증은 문구의 의미를 보장하지 않는다. 의미 대조는 사람의 몫이며 결과에는 두 문구와 사유만 남는다.
- 문구 안의 숫자나 날짜가 공문 값과 다른 경우를 자동으로 잡지 않는다(후속 검토).
- 조건·예외 문구의 부분 수정은 허용하지 않는다(업무 값으로 취급해 FAIL).
- 인간 검수와 완료 판정은 미기록이다.
