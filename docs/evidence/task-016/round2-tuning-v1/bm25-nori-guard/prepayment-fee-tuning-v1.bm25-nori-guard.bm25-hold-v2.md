# 검색 평가 결과 bm25-nori-guard / prepayment-fee-tuning v1 (tuning, 24건)

- search_version: `search-bm25-v1`, relevance_hold: `{"method": "combined", "min_score": "4.812111", "min_ratio": "0.00", "version": "bm25-hold-v2"}`, decision_guard: `{"enabled": true, "version": "decision-guard-v1"}`, 최종 평가 기록: 아니오(초기 점검·조정)
- 지연(us, 질의 전체 처리): p50 11749, p95 32303, n=24

## 세 단계 지표

| 단계 | 지표 | 값 | 기준 | 충족 |
|---|---|---|---|---|
| 검색 단계 | 제외 조건 위반(전달 후보) | 0 | 0(필수) | 예 |
| 검색 단계 | 보류 질의 후보 유출 | 0 | 0(필수) | 예 |
| Core 재확인 | 제거 사유별 건수 | {} | 기록 | - |
| 전체 | 보류 종류별 건수 | {"DECISION_REQUEST": 7, "NO_CANDIDATE": 13} | 기록 | - |
| Core 재확인 | Tool 호출 수 | Tool 1 17, Tool 2 4 | 기록 | - |
| 재확인 후 | Recall@5 | 0.375 | ≥ 0.8 | False |
| 재확인 후 | MRR | 0.375 | ≥ 0.7 | False |
| 재확인 후 | Precision(반환 수 기준) | 0.375 | ≥ 0.7 | False |
| 재확인 후 | 무관 근거 혼입률 | 0.250 (1건) | ≤ 0.25 | True |
| 재확인 후 | 최종 노출(must_not) | 0 | 0(필수) | 예 |
| 재확인 후 | 잘못된 보류 | 4 (0.500) ['T18', 'T20', 'T22', 'T24'] | 기록 | - |
| 재확인 후 | 놓친 보류 | 0 (0.000) [] | 0%(필수) | 예 |
| 재확인 후 | 필드 제공 | 0.750 | 1.0 | 아니오 |
| 재확인 후 | 평균 반환 수 | 0.500 | 보조 | - |

**판정**: 필수 항목 전부 0, 수치 기준 일부 미충족.

## 질의별 결과

| ID | 범주 | 보류 정답 | 원시 후보 상위 5(점수) | 전달 후보 | 제거(단계:사유) | 통과 목록 | R@5 | RR | P | 비고 |
|---|---|---|---|---|---|---|---|---|---|---|
| T01 | decision_forbidden | 예 | - | - | - | - | - | - | - | 보류 DECISION_REQUEST ['DECISION_REQUEST_NOT_SUPPORTED'] |
| T02 | decision_forbidden | 예 | - | - | - | - | - | - | - | 보류 DECISION_REQUEST ['DECISION_REQUEST_NOT_SUPPORTED'] |
| T03 | decision_forbidden | 예 | 0827aed5(1.33), 6a0041a7(0.45) | - | 0827aed5 HOLD:RELEVANCE_BELOW_THRESHOLD, 6a0041a7 HOLD:RELEVANCE_BELOW_THRESHOLD | - | - | - | - | 보류 NO_CANDIDATE ['NO_RELEVANT_CANDIDATE'] |
| T04 | decision_forbidden | 예 | - | - | - | - | - | - | - | 보류 DECISION_REQUEST ['DECISION_REQUEST_NOT_SUPPORTED'] |
| T05 | decision_forbidden | 예 | - | - | - | - | - | - | - | 보류 DECISION_REQUEST ['DECISION_REQUEST_NOT_SUPPORTED'] |
| T06 | decision_forbidden | 예 | - | - | - | - | - | - | - | 보류 DECISION_REQUEST ['DECISION_REQUEST_NOT_SUPPORTED'] |
| T07 | decision_forbidden | 예 | - | - | - | - | - | - | - | 보류 DECISION_REQUEST ['DECISION_REQUEST_NOT_SUPPORTED'] |
| T08 | decision_forbidden | 예 | - | - | - | - | - | - | - | 보류 DECISION_REQUEST ['DECISION_REQUEST_NOT_SUPPORTED'] |
| T09 | hold | 예 | 0827aed5(2.77), 6a0041a7(1.54), a72302da(0.69) | - | 0827aed5 HOLD:RELEVANCE_BELOW_THRESHOLD, 6a0041a7 HOLD:RELEVANCE_BELOW_THRESHOLD, a72302da HOLD:RELEVANCE_BELOW_THRESHOLD | - | - | - | - | 보류 NO_CANDIDATE ['NO_RELEVANT_CANDIDATE'] |
| T10 | hold | 예 | 0827aed5(1.79), 6a0041a7(0.95) | - | 0827aed5 HOLD:RELEVANCE_BELOW_THRESHOLD, 6a0041a7 HOLD:RELEVANCE_BELOW_THRESHOLD | - | - | - | - | 보류 NO_CANDIDATE ['NO_RELEVANT_CANDIDATE'] |
| T11 | hold | 예 | a72302da(1.28), 6a0041a7(0.18), 0827aed5(0.12) | - | a72302da HOLD:RELEVANCE_BELOW_THRESHOLD, 6a0041a7 HOLD:RELEVANCE_BELOW_THRESHOLD, 0827aed5 HOLD:RELEVANCE_BELOW_THRESHOLD | - | - | - | - | 보류 NO_CANDIDATE ['NO_RELEVANT_CANDIDATE'] |
| T12 | hold | 예 | 0827aed5(1.31), 6a0041a7(1.09), a72302da(0.15) | - | 0827aed5 HOLD:RELEVANCE_BELOW_THRESHOLD, 6a0041a7 HOLD:RELEVANCE_BELOW_THRESHOLD, a72302da HOLD:RELEVANCE_BELOW_THRESHOLD | - | - | - | - | 보류 NO_CANDIDATE ['NO_RELEVANT_CANDIDATE'] |
| T13 | hold | 예 | 0827aed5(3.12), a72302da(0.54), 6a0041a7(0.20) | - | 0827aed5 HOLD:RELEVANCE_BELOW_THRESHOLD, a72302da HOLD:RELEVANCE_BELOW_THRESHOLD, 6a0041a7 HOLD:RELEVANCE_BELOW_THRESHOLD | - | - | - | - | 보류 NO_CANDIDATE ['NO_RELEVANT_CANDIDATE'] |
| T14 | hold | 예 | a72302da(1.12), 0827aed5(0.90), 6a0041a7(0.76) | - | a72302da HOLD:RELEVANCE_BELOW_THRESHOLD, 0827aed5 HOLD:RELEVANCE_BELOW_THRESHOLD, 6a0041a7 HOLD:RELEVANCE_BELOW_THRESHOLD | - | - | - | - | 보류 NO_CANDIDATE ['NO_RELEVANT_CANDIDATE'] |
| T15 | hold | 예 | 0827aed5(2.22), 6a0041a7(0.45) | - | 0827aed5 HOLD:RELEVANCE_BELOW_THRESHOLD, 6a0041a7 HOLD:RELEVANCE_BELOW_THRESHOLD | - | - | - | - | 보류 NO_CANDIDATE ['NO_RELEVANT_CANDIDATE'] |
| T16 | hold | 예 | 6a0041a7(4.48), 0827aed5(1.96), a72302da(0.31) | - | 6a0041a7 HOLD:RELEVANCE_BELOW_THRESHOLD, 0827aed5 HOLD:RELEVANCE_BELOW_THRESHOLD, a72302da HOLD:RELEVANCE_BELOW_THRESHOLD | - | - | - | - | 보류 NO_CANDIDATE ['NO_RELEVANT_CANDIDATE'] |
| T17 | current_value |  | 0827aed5(5.34), 6a0041a7(1.91), a72302da(1.12) | 0827aed5 | 6a0041a7 HOLD:RELEVANCE_BELOW_THRESHOLD, a72302da HOLD:RELEVANCE_BELOW_THRESHOLD | 0827aed5 | 1.000 | 1.000 | 1.000 |  |
| T18 | paraphrase |  | 0827aed5(3.62), 6a0041a7(0.91) | - | 0827aed5 HOLD:RELEVANCE_BELOW_THRESHOLD, 6a0041a7 HOLD:RELEVANCE_BELOW_THRESHOLD | - | 0.000 | 0.000 | 0.000 | 필드 누락; 보류 NO_CANDIDATE ['NO_RELEVANT_CANDIDATE'] |
| T19 | paraphrase |  | 0827aed5(4.81), 6a0041a7(2.31), a72302da(1.54) | 0827aed5 | 6a0041a7 HOLD:RELEVANCE_BELOW_THRESHOLD, a72302da HOLD:RELEVANCE_BELOW_THRESHOLD | 0827aed5 | 0.000 | 0.000 | 0.000 | 무관 0827aed5 |
| T20 | direct |  | a72302da(4.37), 0827aed5(1.23), 6a0041a7(0.96) | - | a72302da HOLD:RELEVANCE_BELOW_THRESHOLD, 0827aed5 HOLD:RELEVANCE_BELOW_THRESHOLD, 6a0041a7 HOLD:RELEVANCE_BELOW_THRESHOLD | - | 0.000 | 0.000 | 0.000 | 보류 NO_CANDIDATE ['NO_RELEVANT_CANDIDATE'] |
| T21 | numeric |  | 0827aed5(6.36), 6a0041a7(1.99), a72302da(0.15) | 0827aed5 | 6a0041a7 HOLD:RELEVANCE_BELOW_THRESHOLD, a72302da HOLD:RELEVANCE_BELOW_THRESHOLD | 0827aed5 | 1.000 | 1.000 | 1.000 |  |
| T22 | direct |  | a72302da(2.55), 6a0041a7(0.96), 0827aed5(0.24) | - | a72302da HOLD:RELEVANCE_BELOW_THRESHOLD, 6a0041a7 HOLD:RELEVANCE_BELOW_THRESHOLD, 0827aed5 HOLD:RELEVANCE_BELOW_THRESHOLD | - | 0.000 | 0.000 | 0.000 | 보류 NO_CANDIDATE ['NO_RELEVANT_CANDIDATE'] |
| T23 | numeric |  | 0827aed5(6.79), 6a0041a7(2.44), a72302da(0.15) | 0827aed5 | 6a0041a7 HOLD:RELEVANCE_BELOW_THRESHOLD, a72302da HOLD:RELEVANCE_BELOW_THRESHOLD | 0827aed5 | 1.000 | 1.000 | 1.000 |  |
| T24 | old_value_apply |  | 0827aed5(3.71), 6a0041a7(2.53), a72302da(1.82) | - | 0827aed5 HOLD:RELEVANCE_BELOW_THRESHOLD, 6a0041a7 HOLD:RELEVANCE_BELOW_THRESHOLD, a72302da HOLD:RELEVANCE_BELOW_THRESHOLD | - | 0.000 | 0.000 | 0.000 | 보류 NO_CANDIDATE ['NO_RELEVANT_CANDIDATE'] |

실패 질의: T18, T19, T20, T22, T24
