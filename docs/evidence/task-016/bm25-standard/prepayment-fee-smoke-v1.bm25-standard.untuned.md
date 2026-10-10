# 검색 평가 결과 bm25-standard / prepayment-fee-smoke v1 (smoke, 10건)

- search_version: `search-bm25-v1`, relevance_hold: `{"method": "combined", "min_score": "0", "min_ratio": "0", "version": "untuned"}`, 최종 평가 기록: 아니오(초기 점검·조정)
- 지연(us, 질의 전체 처리): p50 122484, p95 4825345, n=10

## 세 단계 지표

| 단계 | 지표 | 값 | 기준 | 충족 |
|---|---|---|---|---|
| 검색 단계 | 제외 조건 위반(전달 후보) | 0 | 0(필수) | 예 |
| 검색 단계 | 보류 질의 후보 유출 | 2 | 0(필수) | 아니오 |
| Core 재확인 | 제거 사유별 건수 | {} | 기록 | - |
| Core 재확인 | Tool 호출 수 | Tool 1 10, Tool 2 9 | 기록 | - |
| 재확인 후 | Recall@5 | 0.476 | ≥ 0.8 | False |
| 재확인 후 | MRR | 0.571 | ≥ 0.7 | False |
| 재확인 후 | Precision(반환 수 기준) | 0.405 | ≥ 0.7 | False |
| 재확인 후 | 무관 근거 혼입률 | 0.429 (3건) | ≤ 0.25 | False |
| 재확인 후 | 최종 노출(must_not) | 2 | 0(필수) | 아니오 |
| 재확인 후 | 잘못된 보류 | 3 (0.429) ['S01', 'S03', 'S07'] | 기록 | - |
| 재확인 후 | 놓친 보류 | 2 (0.667) ['S08', 'S10'] | 0%(필수) | 아니오 |
| 재확인 후 | 필드 제공 | 0.400 | 1.0 | 아니오 |
| 재확인 후 | 평균 반환 수 | 1.000 | 보조 | - |

**판정**: 필수 항목 미충족, 수치 기준 일부 미충족.

## 질의별 결과

| ID | 범주 | 보류 정답 | 원시 후보 상위 5(점수) | 전달 후보 | 제거(단계:사유) | 통과 목록 | R@5 | RR | P | 비고 |
|---|---|---|---|---|---|---|---|---|---|---|
| S01 | current_value |  | - | - | - | - | 0.000 | 0.000 | 0.000 | 필드 누락; 보류 NO_CANDIDATE ['NO_RELEVANT_CANDIDATE'] |
| S02 | numeric |  | 0827aed5(1.94), 6a0041a7(0.97) | 0827aed5, 6a0041a7 | - | 0827aed5, 6a0041a7 | 1.000 | 1.000 | 0.500 | 무관 6a0041a7 |
| S03 | paraphrase |  | - | - | - | - | 0.000 | 0.000 | 0.000 | 필드 누락; 보류 NO_CANDIDATE ['NO_RELEVANT_CANDIDATE'] |
| S04 | direct |  | 6a0041a7(0.97) | 6a0041a7 | - | 6a0041a7 | 1.000 | 1.000 | 1.000 |  |
| S05 | numeric |  | 0827aed5(0.65) | 0827aed5 | - | 0827aed5 | 0.333 | 1.000 | 1.000 |  |
| S06 | direct |  | a72302da(1.49), 6a0041a7(0.95), 0827aed5(0.46) | a72302da, 6a0041a7, 0827aed5 | - | a72302da, 6a0041a7, 0827aed5 | 1.000 | 1.000 | 0.333 | 무관 6a0041a7, 0827aed5 |
| S07 | old_value_apply |  | - | - | - | - | 0.000 | 0.000 | 0.000 | 필드 누락; 보류 NO_CANDIDATE ['NO_RELEVANT_CANDIDATE'] |
| S08 | other_family | 예 | a72302da(1.01) | a72302da | - | a72302da | - | - | - | 노출 a72302da; 유출 {"a_filter_defect": [], "b_relevance_defect": ["policy-rule:sha256:a72302da8f28a3b6d13bf74f835e9c6b7d967179c45b88f23e5d783336e03e3c"]} |
| S09 | unapproved | 예 | - | - | - | - | - | - | - | 보류 CORE_DECISION ['APPROVED_CHECKLIST_NOTICE_MISMATCH', 'HUMAN_REVIEW_PENDING'] |
| S10 | decision_forbidden | 예 | 0827aed5(0.65) | 0827aed5 | - | 0827aed5 | - | - | - | 노출 0827aed5; 유출 {"a_filter_defect": [], "b_relevance_defect": ["policy-rule:sha256:0827aed5d6b5dc697c5bcd86e1de62bb8aec9aa7ea89bf89bed0aaa072174ed4"]} |

실패 질의: S01, S03, S05, S07, S08, S10
