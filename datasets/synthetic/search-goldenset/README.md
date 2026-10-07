# 검색 골든셋 (TASK-014)

이 폴더의 파일은 전부 합성 행원 질문(`SYNTHETIC_WORK`)입니다. 실제 상담 기록, 고객 정보, 은행 내부자료가 아닙니다. 정답 근거는 합성 공문(`SYNTHETIC_INTERNAL`)의 규칙 version ID를 가리키며, 사용 가능 여부는 평가 시점에 Core가 정합니다.

| 파일 | kind | 용도 |
|---|---|---|
| `prepayment-fee-smoke-v1.json` | `smoke` | 초기 점검용 10건. 관련성 보류 기준 조정에 쓰며 최종 평가에 넣지 않는다 |
| `prepayment-fee-eval-v1.json` | `eval` | 최종 검색 평가용 26~30건. 범주별 최소 수와 의도적 변형(`variant_of`, `variant_purpose`) 포함 |
| `prepayment-fee-safety-v1.json` | `safety` | 안전성 질문 묶음. 질문 본문 없이 `source_query_id`로 참조. 검색 점수에 들어가지 않으며 통과 기준은 TASK-015 계획, 실제 LLM 출력 검증은 TASK-019 |

계약은 `contracts/search-goldenset.schema.json`, 계약 테스트는 `tests/contract/test_search_goldenset_contracts.py`입니다. 정답 기준, 범주, 평가 고정 조건, 지표 정의는 `docs/tasks/TASK-014_검색-골든셋과-평가-기준.md`를 따릅니다. 검색 구현과 관련성 보류 기준값은 TASK-016에서 정합니다.

골든셋을 고치면 `goldenset_version`을 올리고 이유를 Task 문서에 적습니다. `evaluation_context.dataset_fingerprint`는 합성 공문과 추출 결과의 canonical sha256이며, 자료가 바뀌면 계약 테스트가 실패합니다.
