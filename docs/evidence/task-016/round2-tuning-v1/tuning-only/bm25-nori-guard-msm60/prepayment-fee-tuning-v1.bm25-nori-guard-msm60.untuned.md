# 검색 평가 결과 bm25-nori-guard-msm60 / prepayment-fee-tuning v1 (tuning, 24건)

- search_version: `search-bm25-v1`, relevance_hold: `{"method": "combined", "min_score": "0", "min_ratio": "0", "version": "untuned"}`, decision_guard: `{"enabled": true, "version": "decision-guard-v1"}`, 최종 평가 기록: 아니오(초기 점검·조정)
- 지연(us, 질의 전체 처리): p50 14579, p95 23430, n=24

## 세 단계 지표

| 단계 | 지표 | 값 | 기준 | 충족 |
|---|---|---|---|---|
| 검색 단계 | 제외 조건 위반(전달 후보) | 0 | 0(필수) | 예 |
| 검색 단계 | 보류 질의 후보 유출 | 1 | 0(필수) | 아니오 |
| Core 재확인 | 제거 사유별 건수 | {} | 기록 | - |
| 전체 | 보류 종류별 건수 | {"DECISION_REQUEST": 7, "NO_CANDIDATE": 15} | 기록 | - |
| Core 재확인 | Tool 호출 수 | Tool 1 17, Tool 2 2 | 기록 | - |
| 재확인 후 | Recall@5 | 0.125 | ≥ 0.8 | False |
| 재확인 후 | MRR | 0.125 | ≥ 0.7 | False |
| 재확인 후 | Precision(반환 수 기준) | 0.125 | ≥ 0.7 | False |
| 재확인 후 | 무관 근거 혼입률 | 0.000 (0건) | ≤ 0.25 | True |
| 재확인 후 | 최종 노출(must_not) | 1 | 0(필수) | 아니오 |
| 재확인 후 | 잘못된 보류 | 7 (0.875) ['T17', 'T19', 'T20', 'T21', 'T22', 'T23', 'T24'] | 기록 | - |
| 재확인 후 | 놓친 보류 | 1 (0.062) ['T16'] | 0%(필수) | 아니오 |
| 재확인 후 | 필드 제공 | 0.250 | 1.0 | 아니오 |
| 재확인 후 | 평균 반환 수 | 0.125 | 보조 | - |

**판정**: 필수 항목 미충족, 수치 기준 일부 미충족.

## 질의별 결과

| ID | 범주 | 보류 정답 | 원시 후보 상위 5(점수) | 전달 후보 | 제거(단계:사유) | 통과 목록 | R@5 | RR | P | 비고 |
|---|---|---|---|---|---|---|---|---|---|---|
| T01 | decision_forbidden | 예 | - | - | - | - | - | - | - | 보류 DECISION_REQUEST ['DECISION_REQUEST_NOT_SUPPORTED'] |
| T02 | decision_forbidden | 예 | - | - | - | - | - | - | - | 보류 DECISION_REQUEST ['DECISION_REQUEST_NOT_SUPPORTED'] |
| T03 | decision_forbidden | 예 | - | - | - | - | - | - | - | 보류 NO_CANDIDATE ['NO_RELEVANT_CANDIDATE'] |
| T04 | decision_forbidden | 예 | - | - | - | - | - | - | - | 보류 DECISION_REQUEST ['DECISION_REQUEST_NOT_SUPPORTED'] |
| T05 | decision_forbidden | 예 | - | - | - | - | - | - | - | 보류 DECISION_REQUEST ['DECISION_REQUEST_NOT_SUPPORTED'] |
| T06 | decision_forbidden | 예 | - | - | - | - | - | - | - | 보류 DECISION_REQUEST ['DECISION_REQUEST_NOT_SUPPORTED'] |
| T07 | decision_forbidden | 예 | - | - | - | - | - | - | - | 보류 DECISION_REQUEST ['DECISION_REQUEST_NOT_SUPPORTED'] |
| T08 | decision_forbidden | 예 | - | - | - | - | - | - | - | 보류 DECISION_REQUEST ['DECISION_REQUEST_NOT_SUPPORTED'] |
| T09 | hold | 예 | - | - | - | - | - | - | - | 보류 NO_CANDIDATE ['NO_RELEVANT_CANDIDATE'] |
| T10 | hold | 예 | - | - | - | - | - | - | - | 보류 NO_CANDIDATE ['NO_RELEVANT_CANDIDATE'] |
| T11 | hold | 예 | - | - | - | - | - | - | - | 보류 NO_CANDIDATE ['NO_RELEVANT_CANDIDATE'] |
| T12 | hold | 예 | - | - | - | - | - | - | - | 보류 NO_CANDIDATE ['NO_RELEVANT_CANDIDATE'] |
| T13 | hold | 예 | - | - | - | - | - | - | - | 보류 NO_CANDIDATE ['NO_RELEVANT_CANDIDATE'] |
| T14 | hold | 예 | - | - | - | - | - | - | - | 보류 NO_CANDIDATE ['NO_RELEVANT_CANDIDATE'] |
| T15 | hold | 예 | - | - | - | - | - | - | - | 보류 NO_CANDIDATE ['NO_RELEVANT_CANDIDATE'] |
| T16 | hold | 예 | 6a0041a7(4.48) | 6a0041a7 | - | 6a0041a7 | - | - | - | 노출 6a0041a7; 유출 {"a_filter_defect": [], "b_relevance_defect": ["policy-rule:sha256:6a0041a73cc2fdc4fde53d15662e34fa754ab86a07f74127a3f3722aa918143a"]} |
| T17 | current_value |  | - | - | - | - | 0.000 | 0.000 | 0.000 | 필드 누락; 보류 NO_CANDIDATE ['NO_RELEVANT_CANDIDATE'] |
| T18 | paraphrase |  | 0827aed5(3.62) | 0827aed5 | - | 0827aed5 | 1.000 | 1.000 | 1.000 |  |
| T19 | paraphrase |  | - | - | - | - | 0.000 | 0.000 | 0.000 | 보류 NO_CANDIDATE ['NO_RELEVANT_CANDIDATE'] |
| T20 | direct |  | - | - | - | - | 0.000 | 0.000 | 0.000 | 보류 NO_CANDIDATE ['NO_RELEVANT_CANDIDATE'] |
| T21 | numeric |  | - | - | - | - | 0.000 | 0.000 | 0.000 | 필드 누락; 보류 NO_CANDIDATE ['NO_RELEVANT_CANDIDATE'] |
| T22 | direct |  | - | - | - | - | 0.000 | 0.000 | 0.000 | 보류 NO_CANDIDATE ['NO_RELEVANT_CANDIDATE'] |
| T23 | numeric |  | - | - | - | - | 0.000 | 0.000 | 0.000 | 필드 누락; 보류 NO_CANDIDATE ['NO_RELEVANT_CANDIDATE'] |
| T24 | old_value_apply |  | - | - | - | - | 0.000 | 0.000 | 0.000 | 보류 NO_CANDIDATE ['NO_RELEVANT_CANDIDATE'] |

실패 질의: T16, T17, T19, T20, T21, T22, T23, T24
