# 검색 평가 결과 bm25-standard-fuzzyauto / prepayment-fee-smoke v1 (smoke, 10건)

- search_version: `search-bm25-v1`, relevance_hold: `{"method": "combined", "min_score": "1.266529", "min_ratio": "0.00", "version": "bm25-hold-v1"}`, 최종 평가 기록: 아니오(초기 점검·조정)
- 지연(us, 질의 전체 처리): p50 20359, p95 53300, n=10

## 세 단계 지표

| 단계 | 지표 | 값 | 기준 | 충족 |
|---|---|---|---|---|
| 검색 단계 | 제외 조건 위반(전달 후보) | 0 | 0(필수) | 예 |
| 검색 단계 | 보류 질의 후보 유출 | 0 | 0(필수) | 예 |
| Core 재확인 | 제거 사유별 건수 | {} | 기록 | - |
| Core 재확인 | Tool 호출 수 | Tool 1 10, Tool 2 3 | 기록 | - |
| 재확인 후 | Recall@5 | 0.429 | ≥ 0.8 | False |
| 재확인 후 | MRR | 0.429 | ≥ 0.7 | False |
| 재확인 후 | Precision(반환 수 기준) | 0.429 | ≥ 0.7 | False |
| 재확인 후 | 무관 근거 혼입률 | 0.000 (0건) | ≤ 0.25 | True |
| 재확인 후 | 최종 노출(must_not) | 0 | 0(필수) | 예 |
| 재확인 후 | 잘못된 보류 | 4 (0.571) ['S01', 'S03', 'S05', 'S07'] | 기록 | - |
| 재확인 후 | 놓친 보류 | 0 (0.000) [] | 0%(필수) | 예 |
| 재확인 후 | 필드 제공 | 0.200 | 1.0 | 아니오 |
| 재확인 후 | 평균 반환 수 | 0.429 | 보조 | - |

**판정**: 필수 항목 전부 0, 수치 기준 일부 미충족.

## 질의별 결과

| ID | 범주 | 보류 정답 | 원시 후보 상위 5(점수) | 전달 후보 | 제거(단계:사유) | 통과 목록 | R@5 | RR | P | 비고 |
|---|---|---|---|---|---|---|---|---|---|---|
| S01 | current_value |  | 0827aed5(0.85) | - | 0827aed5 HOLD:RELEVANCE_BELOW_THRESHOLD | - | 0.000 | 0.000 | 0.000 | 필드 누락; 보류 NO_CANDIDATE ['NO_RELEVANT_CANDIDATE'] |
| S02 | numeric |  | 0827aed5(1.94), 6a0041a7(0.97) | 0827aed5 | 6a0041a7 HOLD:RELEVANCE_BELOW_THRESHOLD | 0827aed5 | 1.000 | 1.000 | 1.000 |  |
| S03 | paraphrase |  | 6a0041a7(0.56), 0827aed5(0.43) | - | 6a0041a7 HOLD:RELEVANCE_BELOW_THRESHOLD, 0827aed5 HOLD:RELEVANCE_BELOW_THRESHOLD | - | 0.000 | 0.000 | 0.000 | 필드 누락; 보류 NO_CANDIDATE ['NO_RELEVANT_CANDIDATE'] |
| S04 | direct |  | 6a0041a7(1.69) | 6a0041a7 | - | 6a0041a7 | 1.000 | 1.000 | 1.000 |  |
| S05 | numeric |  | 0827aed5(1.27), 6a0041a7(0.48) | - | 0827aed5 HOLD:RELEVANCE_BELOW_THRESHOLD, 6a0041a7 HOLD:RELEVANCE_BELOW_THRESHOLD | - | 0.000 | 0.000 | 0.000 | 필드 누락; 보류 NO_CANDIDATE ['NO_RELEVANT_CANDIDATE'] |
| S06 | direct |  | a72302da(1.99), 6a0041a7(0.95), 0827aed5(0.46) | a72302da | 6a0041a7 HOLD:RELEVANCE_BELOW_THRESHOLD, 0827aed5 HOLD:RELEVANCE_BELOW_THRESHOLD | a72302da | 1.000 | 1.000 | 1.000 |  |
| S07 | old_value_apply |  | 6a0041a7(0.48), 0827aed5(0.43) | - | 6a0041a7 HOLD:RELEVANCE_BELOW_THRESHOLD, 0827aed5 HOLD:RELEVANCE_BELOW_THRESHOLD | - | 0.000 | 0.000 | 0.000 | 필드 누락; 보류 NO_CANDIDATE ['NO_RELEVANT_CANDIDATE'] |
| S08 | other_family | 예 | a72302da(1.01) | - | a72302da HOLD:RELEVANCE_BELOW_THRESHOLD | - | - | - | - | 보류 NO_CANDIDATE ['NO_RELEVANT_CANDIDATE'] |
| S09 | unapproved | 예 | - | - | - | - | - | - | - | 보류 CORE_DECISION ['APPROVED_CHECKLIST_NOTICE_MISMATCH', 'HUMAN_REVIEW_PENDING'] |
| S10 | decision_forbidden | 예 | 0827aed5(0.65) | - | 0827aed5 HOLD:RELEVANCE_BELOW_THRESHOLD | - | - | - | - | 보류 NO_CANDIDATE ['NO_RELEVANT_CANDIDATE'] |

실패 질의: S01, S03, S05, S07
