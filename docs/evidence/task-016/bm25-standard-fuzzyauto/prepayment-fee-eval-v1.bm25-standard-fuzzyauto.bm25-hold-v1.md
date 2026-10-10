# 검색 평가 결과 bm25-standard-fuzzyauto / prepayment-fee-eval v1 (eval, 27건)

- search_version: `search-bm25-v1`, relevance_hold: `{"method": "combined", "min_score": "1.266529", "min_ratio": "0.00", "version": "bm25-hold-v1"}`, 최종 평가 기록: 예
- 지연(us, 질의 전체 처리): p50 20866, p95 30713, n=27

## 세 단계 지표

| 단계 | 지표 | 값 | 기준 | 충족 |
|---|---|---|---|---|
| 검색 단계 | 제외 조건 위반(전달 후보) | 0 | 0(필수) | 예 |
| 검색 단계 | 보류 질의 후보 유출 | 0 | 0(필수) | 예 |
| Core 재확인 | 제거 사유별 건수 | {} | 기록 | - |
| Core 재확인 | Tool 호출 수 | Tool 1 27, Tool 2 8 | 기록 | - |
| 재확인 후 | Recall@5 | 0.412 | ≥ 0.8 | False |
| 재확인 후 | MRR | 0.412 | ≥ 0.7 | False |
| 재확인 후 | Precision(반환 수 기준) | 0.412 | ≥ 0.7 | False |
| 재확인 후 | 무관 근거 혼입률 | 0.125 (1건) | ≤ 0.25 | True |
| 재확인 후 | 최종 노출(must_not) | 0 | 0(필수) | 예 |
| 재확인 후 | 잘못된 보류 | 9 (0.529) ['E04', 'E05', 'E06', 'E07', 'E08', 'E11', 'E13', 'E15', 'E16'] | 기록 | - |
| 재확인 후 | 놓친 보류 | 0 (0.000) [] | 0%(필수) | 예 |
| 재확인 후 | 필드 제공 | 0.308 | 1.0 | 아니오 |
| 재확인 후 | 평균 반환 수 | 0.471 | 보조 | - |

**판정**: 필수 항목 전부 0, 수치 기준 일부 미충족.

## 질의별 결과

| ID | 범주 | 보류 정답 | 원시 후보 상위 5(점수) | 전달 후보 | 제거(단계:사유) | 통과 목록 | R@5 | RR | P | 비고 |
|---|---|---|---|---|---|---|---|---|---|---|
| E01 | direct |  | a72302da(5.80), 0827aed5(1.06), 6a0041a7(0.95) | a72302da | 0827aed5 HOLD:RELEVANCE_BELOW_THRESHOLD, 6a0041a7 HOLD:RELEVANCE_BELOW_THRESHOLD | a72302da | 1.000 | 1.000 | 1.000 |  |
| E02 | direct |  | 6a0041a7(3.04), a72302da(0.14), 0827aed5(0.13) | 6a0041a7 | a72302da HOLD:RELEVANCE_BELOW_THRESHOLD, 0827aed5 HOLD:RELEVANCE_BELOW_THRESHOLD | 6a0041a7 | 1.000 | 1.000 | 1.000 |  |
| E03 | paraphrase |  | 0827aed5(1.94), 6a0041a7(0.97) | 0827aed5 | 6a0041a7 HOLD:RELEVANCE_BELOW_THRESHOLD | 0827aed5 | 1.000 | 1.000 | 1.000 |  |
| E04 | paraphrase |  | 0827aed5(0.85) | - | 0827aed5 HOLD:RELEVANCE_BELOW_THRESHOLD | - | 0.000 | 0.000 | 0.000 | 필드 누락; 보류 NO_CANDIDATE ['NO_RELEVANT_CANDIDATE'] |
| E05 | paraphrase |  | a72302da(1.01), 6a0041a7(0.50) | - | a72302da HOLD:RELEVANCE_BELOW_THRESHOLD, 6a0041a7 HOLD:RELEVANCE_BELOW_THRESHOLD | - | 0.000 | 0.000 | 0.000 | 보류 NO_CANDIDATE ['NO_RELEVANT_CANDIDATE'] |
| E06 | paraphrase |  | 0827aed5(0.75), 6a0041a7(0.56) | - | 0827aed5 HOLD:RELEVANCE_BELOW_THRESHOLD, 6a0041a7 HOLD:RELEVANCE_BELOW_THRESHOLD | - | 0.000 | 0.000 | 0.000 | 필드 누락; 보류 NO_CANDIDATE ['NO_RELEVANT_CANDIDATE'] |
| E07 | paraphrase |  | 0827aed5(1.13), 6a0041a7(0.73) | - | 0827aed5 HOLD:RELEVANCE_BELOW_THRESHOLD, 6a0041a7 HOLD:RELEVANCE_BELOW_THRESHOLD | - | 0.000 | 0.000 | 0.000 | 필드 누락; 보류 NO_CANDIDATE ['NO_RELEVANT_CANDIDATE'] |
| E08 | numeric |  | 0827aed5(0.76), 6a0041a7(0.66) | - | 0827aed5 HOLD:RELEVANCE_BELOW_THRESHOLD, 6a0041a7 HOLD:RELEVANCE_BELOW_THRESHOLD | - | 0.000 | 0.000 | 0.000 | 필드 누락; 보류 NO_CANDIDATE ['NO_RELEVANT_CANDIDATE'] |
| E09 | numeric |  | 6a0041a7(2.07), 0827aed5(1.08), a72302da(0.14) | 6a0041a7 | 0827aed5 HOLD:RELEVANCE_BELOW_THRESHOLD, a72302da HOLD:RELEVANCE_BELOW_THRESHOLD | 6a0041a7 | 1.000 | 1.000 | 1.000 |  |
| E10 | numeric |  | 0827aed5(1.41), 6a0041a7(0.97) | 0827aed5 | 6a0041a7 HOLD:RELEVANCE_BELOW_THRESHOLD | 0827aed5 | 1.000 | 1.000 | 1.000 |  |
| E11 | numeric |  | 6a0041a7(1.21), 0827aed5(0.81) | - | 6a0041a7 HOLD:RELEVANCE_BELOW_THRESHOLD, 0827aed5 HOLD:RELEVANCE_BELOW_THRESHOLD | - | 0.000 | 0.000 | 0.000 | 필드 누락; 보류 NO_CANDIDATE ['NO_RELEVANT_CANDIDATE'] |
| E12 | current_value |  | 0827aed5(1.82) | 0827aed5 | - | 0827aed5 | 1.000 | 1.000 | 1.000 |  |
| E13 | current_value |  | 0827aed5(0.83), 6a0041a7(0.48) | - | 0827aed5 HOLD:RELEVANCE_BELOW_THRESHOLD, 6a0041a7 HOLD:RELEVANCE_BELOW_THRESHOLD | - | 0.000 | 0.000 | 0.000 | 필드 누락; 보류 NO_CANDIDATE ['NO_RELEVANT_CANDIDATE'] |
| E14 | current_value |  | 6a0041a7(2.00), a72302da(0.64), 0827aed5(0.43) | 6a0041a7 | a72302da HOLD:RELEVANCE_BELOW_THRESHOLD, 0827aed5 HOLD:RELEVANCE_BELOW_THRESHOLD | 6a0041a7 | 0.000 | 0.000 | 0.000 | 무관 6a0041a7; 필드 누락 |
| E15 | old_value_apply |  | 6a0041a7(0.97), 0827aed5(0.65) | - | 6a0041a7 HOLD:RELEVANCE_BELOW_THRESHOLD, 0827aed5 HOLD:RELEVANCE_BELOW_THRESHOLD | - | 0.000 | 0.000 | 0.000 | 필드 누락; 보류 NO_CANDIDATE ['NO_RELEVANT_CANDIDATE'] |
| E16 | old_value_apply |  | 0827aed5(1.14), a72302da(0.99), 6a0041a7(0.48) | - | 0827aed5 HOLD:RELEVANCE_BELOW_THRESHOLD, a72302da HOLD:RELEVANCE_BELOW_THRESHOLD, 6a0041a7 HOLD:RELEVANCE_BELOW_THRESHOLD | - | 0.000 | 0.000 | 0.000 | 필드 누락; 보류 NO_CANDIDATE ['NO_RELEVANT_CANDIDATE'] |
| E17 | old_value_apply |  | 0827aed5(1.29), 6a0041a7(0.50) | 0827aed5 | 6a0041a7 HOLD:RELEVANCE_BELOW_THRESHOLD | 0827aed5 | 1.000 | 1.000 | 1.000 |  |
| E18 | other_family | 예 | a72302da(0.24), 0827aed5(0.23) | - | a72302da HOLD:RELEVANCE_BELOW_THRESHOLD, 0827aed5 HOLD:RELEVANCE_BELOW_THRESHOLD | - | - | - | - | 보류 NO_CANDIDATE ['NO_RELEVANT_CANDIDATE'] |
| E19 | other_family | 예 | - | - | - | - | - | - | - | 보류 CORE_DECISION ['APPROVED_CHECKLIST_NOTICE_MISMATCH', 'HUMAN_REVIEW_PENDING'] |
| E20 | unapproved | 예 | - | - | - | - | - | - | - | 보류 CORE_DECISION ['APPROVED_CHECKLIST_NOTICE_MISMATCH', 'HUMAN_REVIEW_PENDING'] |
| E21 | unapproved | 예 | - | - | - | - | - | - | - | 보류 CORE_DECISION ['APPROVED_CHECKLIST_NOTICE_MISMATCH', 'HUMAN_REVIEW_PENDING'] |
| E22 | decision_forbidden | 예 | 0827aed5(0.65) | - | 0827aed5 HOLD:RELEVANCE_BELOW_THRESHOLD | - | - | - | - | 보류 NO_CANDIDATE ['NO_RELEVANT_CANDIDATE'] |
| E23 | decision_forbidden | 예 | - | - | - | - | - | - | - | 보류 NO_CANDIDATE ['NO_RELEVANT_CANDIDATE'] |
| E24 | hold | 예 | 6a0041a7(0.97), 0827aed5(0.65) | - | 6a0041a7 HOLD:RELEVANCE_BELOW_THRESHOLD, 0827aed5 HOLD:RELEVANCE_BELOW_THRESHOLD | - | - | - | - | 보류 NO_CANDIDATE ['NO_RELEVANT_CANDIDATE'] |
| E25 | hold | 예 | 6a0041a7(0.97), 0827aed5(0.65), a72302da(0.50) | - | 6a0041a7 HOLD:RELEVANCE_BELOW_THRESHOLD, 0827aed5 HOLD:RELEVANCE_BELOW_THRESHOLD, a72302da HOLD:RELEVANCE_BELOW_THRESHOLD | - | - | - | - | 보류 NO_CANDIDATE ['NO_RELEVANT_CANDIDATE'] |
| E26 | fixture_period | 예 | - | - | - | - | - | - | - | 보류 CORE_DECISION ['FIXTURE_CHECKLIST_NOT_APPROVED'] |
| E27 | fixture_period | 예 | - | - | - | - | - | - | - | 보류 CORE_DECISION ['FIXTURE_CHECKLIST_NOT_APPROVED'] |

실패 질의: E04, E05, E06, E07, E08, E11, E13, E14, E15, E16
