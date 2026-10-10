# 검색 평가 결과 bm25-nori / prepayment-fee-smoke v1 (smoke, 10건)

- search_version: `search-bm25-v1`, relevance_hold: `{"method": "combined", "min_score": "0", "min_ratio": "0", "version": "untuned"}`, 최종 평가 기록: 아니오(초기 점검·조정)
- 지연(us, 질의 전체 처리): p50 30794, p95 288069, n=10

## 세 단계 지표

| 단계 | 지표 | 값 | 기준 | 충족 |
|---|---|---|---|---|
| 검색 단계 | 제외 조건 위반(전달 후보) | 0 | 0(필수) | 예 |
| 검색 단계 | 보류 질의 후보 유출 | 2 | 0(필수) | 아니오 |
| Core 재확인 | 제거 사유별 건수 | {} | 기록 | - |
| Core 재확인 | Tool 호출 수 | Tool 1 10, Tool 2 25 | 기록 | - |
| 재확인 후 | Recall@5 | 1.000 | ≥ 0.8 | True |
| 재확인 후 | MRR | 1.000 | ≥ 0.7 | True |
| 재확인 후 | Precision(반환 수 기준) | 0.500 | ≥ 0.7 | False |
| 재확인 후 | 무관 근거 혼입률 | 0.500 (10건) | ≤ 0.25 | False |
| 재확인 후 | 최종 노출(must_not) | 5 | 0(필수) | 아니오 |
| 재확인 후 | 잘못된 보류 | 0 (0.000) [] | 기록 | - |
| 재확인 후 | 놓친 보류 | 2 (0.667) ['S08', 'S10'] | 0%(필수) | 아니오 |
| 재확인 후 | 필드 제공 | 1.000 | 1.0 | 예 |
| 재확인 후 | 평균 반환 수 | 2.857 | 보조 | - |

**판정**: 필수 항목 미충족, 수치 기준 일부 미충족.

## 질의별 결과

| ID | 범주 | 보류 정답 | 원시 후보 상위 5(점수) | 전달 후보 | 제거(단계:사유) | 통과 목록 | R@5 | RR | P | 비고 |
|---|---|---|---|---|---|---|---|---|---|---|
| S01 | current_value |  | 0827aed5(3.55), 6a0041a7(1.15) | 0827aed5, 6a0041a7 | - | 0827aed5, 6a0041a7 | 1.000 | 1.000 | 0.500 | 무관 6a0041a7 |
| S02 | numeric |  | 0827aed5(8.15), 6a0041a7(1.15), a72302da(0.15) | 0827aed5, 6a0041a7, a72302da | - | 0827aed5, 6a0041a7, a72302da | 1.000 | 1.000 | 0.333 | 무관 6a0041a7, a72302da |
| S03 | paraphrase |  | 0827aed5(1.79), a72302da(0.54), 6a0041a7(0.45) | 0827aed5, a72302da, 6a0041a7 | - | 0827aed5, a72302da, 6a0041a7 | 1.000 | 1.000 | 0.333 | 무관 a72302da, 6a0041a7 |
| S04 | direct |  | 6a0041a7(3.52), 0827aed5(1.48), a72302da(0.31) | 6a0041a7, 0827aed5, a72302da | - | 6a0041a7, 0827aed5, a72302da | 1.000 | 1.000 | 0.333 | 무관 0827aed5, a72302da |
| S05 | numeric |  | 0827aed5(3.27), 6a0041a7(2.54), a72302da(0.31) | 0827aed5, 6a0041a7, a72302da | - | 0827aed5, 6a0041a7, a72302da | 1.000 | 1.000 | 1.000 |  |
| S06 | direct |  | a72302da(4.91), 6a0041a7(2.35), 0827aed5(1.23) | a72302da, 6a0041a7, 0827aed5 | - | a72302da, 6a0041a7, 0827aed5 | 1.000 | 1.000 | 0.333 | 무관 6a0041a7, 0827aed5 |
| S07 | old_value_apply |  | 0827aed5(2.62), 6a0041a7(1.13), a72302da(0.15) | 0827aed5, 6a0041a7, a72302da | - | 0827aed5, 6a0041a7, a72302da | 1.000 | 1.000 | 0.667 | 무관 a72302da |
| S08 | other_family | 예 | 0827aed5(1.33), a72302da(0.54) | 0827aed5, a72302da | - | 0827aed5, a72302da | - | - | - | 노출 0827aed5, a72302da; 유출 {"a_filter_defect": [], "b_relevance_defect": ["policy-rule:sha256:0827aed5d6b5dc697c5bcd86e1de62bb8aec9aa7ea89bf89bed0aaa072174ed4", "policy-rule:sha256:a72302da8f28a3b6d13bf74f835e9c6b7d967179c45b88f23e5d783336e03e3c"]} |
| S09 | unapproved | 예 | - | - | - | - | - | - | - | 보류 CORE_DECISION ['APPROVED_CHECKLIST_NOTICE_MISMATCH', 'HUMAN_REVIEW_PENDING'] |
| S10 | decision_forbidden | 예 | 0827aed5(2.35), 6a0041a7(0.63), a72302da(0.15) | 0827aed5, 6a0041a7, a72302da | - | 0827aed5, 6a0041a7, a72302da | - | - | - | 노출 0827aed5, 6a0041a7, a72302da; 유출 {"a_filter_defect": [], "b_relevance_defect": ["policy-rule:sha256:0827aed5d6b5dc697c5bcd86e1de62bb8aec9aa7ea89bf89bed0aaa072174ed4", "policy-rule:sha256:6a0041a73cc2fdc4fde53d15662e34fa754ab86a07f74127a3f3722aa918143a", "policy-rule:sha256:a72302da8f28a3b6d13bf74f835e9c6b7d967179c45b88f23e5d783336e03e3c"]} |

실패 질의: S08, S10
