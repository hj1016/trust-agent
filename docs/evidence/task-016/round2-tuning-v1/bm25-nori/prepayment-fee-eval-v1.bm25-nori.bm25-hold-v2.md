# 검색 평가 결과 bm25-nori / prepayment-fee-eval v1 (eval, 27건)

- search_version: `search-bm25-v1`, relevance_hold: `{"method": "combined", "min_score": "4.812111", "min_ratio": "0.00", "version": "bm25-hold-v2"}`, decision_guard: `{"enabled": false, "version": "decision-guard-v1"}`, 최종 평가 기록: 예
- 지연(us, 질의 전체 처리): p50 26569, p95 67255, n=27

## 세 단계 지표

| 단계 | 지표 | 값 | 기준 | 충족 |
|---|---|---|---|---|
| 검색 단계 | 제외 조건 위반(전달 후보) | 0 | 0(필수) | 예 |
| 검색 단계 | 보류 질의 후보 유출 | 0 | 0(필수) | 예 |
| Core 재확인 | 제거 사유별 건수 | {} | 기록 | - |
| 전체 | 보류 종류별 건수 | {"NO_CANDIDATE": 16, "CORE_DECISION": 5} | 기록 | - |
| Core 재확인 | Tool 호출 수 | Tool 1 27, Tool 2 6 | 기록 | - |
| 재확인 후 | Recall@5 | 0.324 | ≥ 0.8 | False |
| 재확인 후 | MRR | 0.353 | ≥ 0.7 | False |
| 재확인 후 | Precision(반환 수 기준) | 0.353 | ≥ 0.7 | False |
| 재확인 후 | 무관 근거 혼입률 | 0.000 (0건) | ≤ 0.25 | True |
| 재확인 후 | 최종 노출(must_not) | 0 | 0(필수) | 예 |
| 재확인 후 | 잘못된 보류 | 11 (0.647) ['E03', 'E04', 'E05', 'E06', 'E07', 'E08', 'E13', 'E14', 'E15', 'E16', 'E17'] | 기록 | - |
| 재확인 후 | 놓친 보류 | 0 (0.000) [] | 0%(필수) | 예 |
| 재확인 후 | 필드 제공 | 0.154 | 1.0 | 아니오 |
| 재확인 후 | 평균 반환 수 | 0.353 | 보조 | - |

**판정**: 필수 항목 전부 0, 수치 기준 일부 미충족.

## 질의별 결과

| ID | 범주 | 보류 정답 | 원시 후보 상위 5(점수) | 전달 후보 | 제거(단계:사유) | 통과 목록 | R@5 | RR | P | 비고 |
|---|---|---|---|---|---|---|---|---|---|---|
| E01 | direct |  | a72302da(8.23), 6a0041a7(2.67), 0827aed5(1.66) | a72302da | 6a0041a7 HOLD:RELEVANCE_BELOW_THRESHOLD, 0827aed5 HOLD:RELEVANCE_BELOW_THRESHOLD | a72302da | 1.000 | 1.000 | 1.000 |  |
| E02 | direct |  | 6a0041a7(4.83), 0827aed5(1.35), a72302da(1.15) | 6a0041a7 | 0827aed5 HOLD:RELEVANCE_BELOW_THRESHOLD, a72302da HOLD:RELEVANCE_BELOW_THRESHOLD | 6a0041a7 | 1.000 | 1.000 | 1.000 |  |
| E03 | paraphrase |  | 0827aed5(3.55), 6a0041a7(0.91) | - | 0827aed5 HOLD:RELEVANCE_BELOW_THRESHOLD, 6a0041a7 HOLD:RELEVANCE_BELOW_THRESHOLD | - | 0.000 | 0.000 | 0.000 | 필드 누락; 보류 NO_CANDIDATE ['NO_RELEVANT_CANDIDATE'] |
| E04 | paraphrase |  | 0827aed5(4.27), 6a0041a7(2.44), a72302da(0.69) | - | 0827aed5 HOLD:RELEVANCE_BELOW_THRESHOLD, 6a0041a7 HOLD:RELEVANCE_BELOW_THRESHOLD, a72302da HOLD:RELEVANCE_BELOW_THRESHOLD | - | 0.000 | 0.000 | 0.000 | 필드 누락; 보류 NO_CANDIDATE ['NO_RELEVANT_CANDIDATE'] |
| E05 | paraphrase |  | a72302da(3.09), 0827aed5(1.10), 6a0041a7(0.81) | - | a72302da HOLD:RELEVANCE_BELOW_THRESHOLD, 0827aed5 HOLD:RELEVANCE_BELOW_THRESHOLD, 6a0041a7 HOLD:RELEVANCE_BELOW_THRESHOLD | - | 0.000 | 0.000 | 0.000 | 보류 NO_CANDIDATE ['NO_RELEVANT_CANDIDATE'] |
| E06 | paraphrase |  | 0827aed5(4.73), 6a0041a7(1.26), a72302da(0.31) | - | 0827aed5 HOLD:RELEVANCE_BELOW_THRESHOLD, 6a0041a7 HOLD:RELEVANCE_BELOW_THRESHOLD, a72302da HOLD:RELEVANCE_BELOW_THRESHOLD | - | 0.000 | 0.000 | 0.000 | 필드 누락; 보류 NO_CANDIDATE ['NO_RELEVANT_CANDIDATE'] |
| E07 | paraphrase |  | 0827aed5(3.55), 6a0041a7(0.91) | - | 0827aed5 HOLD:RELEVANCE_BELOW_THRESHOLD, 6a0041a7 HOLD:RELEVANCE_BELOW_THRESHOLD | - | 0.000 | 0.000 | 0.000 | 필드 누락; 보류 NO_CANDIDATE ['NO_RELEVANT_CANDIDATE'] |
| E08 | numeric |  | 0827aed5(3.67), 6a0041a7(2.41), a72302da(0.15) | - | 0827aed5 HOLD:RELEVANCE_BELOW_THRESHOLD, 6a0041a7 HOLD:RELEVANCE_BELOW_THRESHOLD, a72302da HOLD:RELEVANCE_BELOW_THRESHOLD | - | 0.000 | 0.000 | 0.000 | 필드 누락; 보류 NO_CANDIDATE ['NO_RELEVANT_CANDIDATE'] |
| E09 | numeric |  | 6a0041a7(5.02), 0827aed5(2.16), a72302da(2.08) | 6a0041a7 | 0827aed5 HOLD:RELEVANCE_BELOW_THRESHOLD, a72302da HOLD:RELEVANCE_BELOW_THRESHOLD | 6a0041a7 | 1.000 | 1.000 | 1.000 |  |
| E10 | numeric |  | 0827aed5(5.34), 6a0041a7(2.23) | 0827aed5 | 6a0041a7 HOLD:RELEVANCE_BELOW_THRESHOLD | 0827aed5 | 1.000 | 1.000 | 1.000 |  |
| E11 | numeric |  | 6a0041a7(4.99), 0827aed5(4.02) | 6a0041a7 | 0827aed5 HOLD:RELEVANCE_BELOW_THRESHOLD | 6a0041a7 | 0.500 | 1.000 | 1.000 | 필드 누락 |
| E12 | current_value |  | 0827aed5(6.24), 6a0041a7(1.15) | 0827aed5 | 6a0041a7 HOLD:RELEVANCE_BELOW_THRESHOLD | 0827aed5 | 1.000 | 1.000 | 1.000 |  |
| E13 | current_value |  | 0827aed5(3.98), 6a0041a7(2.31) | - | 0827aed5 HOLD:RELEVANCE_BELOW_THRESHOLD, 6a0041a7 HOLD:RELEVANCE_BELOW_THRESHOLD | - | 0.000 | 0.000 | 0.000 | 필드 누락; 보류 NO_CANDIDATE ['NO_RELEVANT_CANDIDATE'] |
| E14 | current_value |  | 0827aed5(3.71), 6a0041a7(2.67), a72302da(1.28) | - | 0827aed5 HOLD:RELEVANCE_BELOW_THRESHOLD, 6a0041a7 HOLD:RELEVANCE_BELOW_THRESHOLD, a72302da HOLD:RELEVANCE_BELOW_THRESHOLD | - | 0.000 | 0.000 | 0.000 | 필드 누락; 보류 NO_CANDIDATE ['NO_RELEVANT_CANDIDATE'] |
| E15 | old_value_apply |  | 0827aed5(3.71), 6a0041a7(1.13), a72302da(0.15) | - | 0827aed5 HOLD:RELEVANCE_BELOW_THRESHOLD, 6a0041a7 HOLD:RELEVANCE_BELOW_THRESHOLD, a72302da HOLD:RELEVANCE_BELOW_THRESHOLD | - | 0.000 | 0.000 | 0.000 | 필드 누락; 보류 NO_CANDIDATE ['NO_RELEVANT_CANDIDATE'] |
| E16 | old_value_apply |  | 0827aed5(4.36), a72302da(2.66), 6a0041a7(1.85) | - | 0827aed5 HOLD:RELEVANCE_BELOW_THRESHOLD, a72302da HOLD:RELEVANCE_BELOW_THRESHOLD, 6a0041a7 HOLD:RELEVANCE_BELOW_THRESHOLD | - | 0.000 | 0.000 | 0.000 | 필드 누락; 보류 NO_CANDIDATE ['NO_RELEVANT_CANDIDATE'] |
| E17 | old_value_apply |  | 0827aed5(3.71), 6a0041a7(0.40), a72302da(0.15) | - | 0827aed5 HOLD:RELEVANCE_BELOW_THRESHOLD, 6a0041a7 HOLD:RELEVANCE_BELOW_THRESHOLD, a72302da HOLD:RELEVANCE_BELOW_THRESHOLD | - | 0.000 | 0.000 | 0.000 | 필드 누락; 보류 NO_CANDIDATE ['NO_RELEVANT_CANDIDATE'] |
| E18 | other_family | 예 | 0827aed5(1.33), a72302da(0.54) | - | 0827aed5 HOLD:RELEVANCE_BELOW_THRESHOLD, a72302da HOLD:RELEVANCE_BELOW_THRESHOLD | - | - | - | - | 보류 NO_CANDIDATE ['NO_RELEVANT_CANDIDATE'] |
| E19 | other_family | 예 | - | - | - | - | - | - | - | 보류 CORE_DECISION ['APPROVED_CHECKLIST_NOTICE_MISMATCH', 'HUMAN_REVIEW_PENDING'] |
| E20 | unapproved | 예 | - | - | - | - | - | - | - | 보류 CORE_DECISION ['APPROVED_CHECKLIST_NOTICE_MISMATCH', 'HUMAN_REVIEW_PENDING'] |
| E21 | unapproved | 예 | - | - | - | - | - | - | - | 보류 CORE_DECISION ['APPROVED_CHECKLIST_NOTICE_MISMATCH', 'HUMAN_REVIEW_PENDING'] |
| E22 | decision_forbidden | 예 | 0827aed5(2.88), 6a0041a7(1.58), a72302da(0.69) | - | 0827aed5 HOLD:RELEVANCE_BELOW_THRESHOLD, 6a0041a7 HOLD:RELEVANCE_BELOW_THRESHOLD, a72302da HOLD:RELEVANCE_BELOW_THRESHOLD | - | - | - | - | 보류 NO_CANDIDATE ['NO_RELEVANT_CANDIDATE'] |
| E23 | decision_forbidden | 예 | 6a0041a7(0.18), a72302da(0.15), 0827aed5(0.12) | - | 6a0041a7 HOLD:RELEVANCE_BELOW_THRESHOLD, a72302da HOLD:RELEVANCE_BELOW_THRESHOLD, 0827aed5 HOLD:RELEVANCE_BELOW_THRESHOLD | - | - | - | - | 보류 NO_CANDIDATE ['NO_RELEVANT_CANDIDATE'] |
| E24 | hold | 예 | 0827aed5(1.31), 6a0041a7(1.09), a72302da(0.15) | - | 0827aed5 HOLD:RELEVANCE_BELOW_THRESHOLD, 6a0041a7 HOLD:RELEVANCE_BELOW_THRESHOLD, a72302da HOLD:RELEVANCE_BELOW_THRESHOLD | - | - | - | - | 보류 NO_CANDIDATE ['NO_RELEVANT_CANDIDATE'] |
| E25 | hold | 예 | 6a0041a7(1.86), 0827aed5(1.31), a72302da(1.12) | - | 6a0041a7 HOLD:RELEVANCE_BELOW_THRESHOLD, 0827aed5 HOLD:RELEVANCE_BELOW_THRESHOLD, a72302da HOLD:RELEVANCE_BELOW_THRESHOLD | - | - | - | - | 보류 NO_CANDIDATE ['NO_RELEVANT_CANDIDATE'] |
| E26 | fixture_period | 예 | - | - | - | - | - | - | - | 보류 CORE_DECISION ['FIXTURE_CHECKLIST_NOT_APPROVED'] |
| E27 | fixture_period | 예 | - | - | - | - | - | - | - | 보류 CORE_DECISION ['FIXTURE_CHECKLIST_NOT_APPROVED'] |

실패 질의: E03, E04, E05, E06, E07, E08, E11, E13, E14, E15, E16, E17
