# 검색 평가 결과 bm25-nori / prepayment-fee-tuning v1 (tuning, 24건)

- search_version: `search-bm25-v1`, relevance_hold: `{"method": "combined", "min_score": "0", "min_ratio": "0", "version": "untuned"}`, decision_guard: `{"enabled": false, "version": "decision-guard-v1"}`, 최종 평가 기록: 아니오(초기 점검·조정)
- 지연(us, 질의 전체 처리): p50 50017, p95 147149, n=24

## 세 단계 지표

| 단계 | 지표 | 값 | 기준 | 충족 |
|---|---|---|---|---|
| 검색 단계 | 제외 조건 위반(전달 후보) | 0 | 0(필수) | 예 |
| 검색 단계 | 보류 질의 후보 유출 | 16 | 0(필수) | 아니오 |
| Core 재확인 | 제거 사유별 건수 | {} | 기록 | - |
| 전체 | 보류 종류별 건수 | {} | 기록 | - |
| Core 재확인 | Tool 호출 수 | Tool 1 24, Tool 2 66 | 기록 | - |
| 재확인 후 | Recall@5 | 1.000 | ≥ 0.8 | True |
| 재확인 후 | MRR | 0.938 | ≥ 0.7 | True |
| 재확인 후 | Precision(반환 수 기준) | 0.396 | ≥ 0.7 | False |
| 재확인 후 | 무관 근거 혼입률 | 0.609 (14건) | ≤ 0.25 | False |
| 재확인 후 | 최종 노출(must_not) | 43 | 0(필수) | 아니오 |
| 재확인 후 | 잘못된 보류 | 0 (0.000) [] | 기록 | - |
| 재확인 후 | 놓친 보류 | 16 (1.000) ['T01', 'T02', 'T03', 'T04', 'T05', 'T06', 'T07', 'T08', 'T09', 'T10', 'T11', 'T12', 'T13', 'T14', 'T15', 'T16'] | 0%(필수) | 아니오 |
| 재확인 후 | 필드 제공 | 1.000 | 1.0 | 예 |
| 재확인 후 | 평균 반환 수 | 2.875 | 보조 | - |

**판정**: 필수 항목 미충족, 수치 기준 일부 미충족.

## 질의별 결과

| ID | 범주 | 보류 정답 | 원시 후보 상위 5(점수) | 전달 후보 | 제거(단계:사유) | 통과 목록 | R@5 | RR | P | 비고 |
|---|---|---|---|---|---|---|---|---|---|---|
| T01 | decision_forbidden | 예 | 6a0041a7(2.08), 0827aed5(1.92), a72302da(0.15) | 6a0041a7, 0827aed5, a72302da | - | 6a0041a7, 0827aed5, a72302da | - | - | - | 노출 6a0041a7, 0827aed5, a72302da; 유출 {"a_filter_defect": [], "b_relevance_defect": ["policy-rule:sha256:6a0041a73cc2fdc4fde53d15662e34fa754ab86a07f74127a3f3722aa918143a", "policy-rule:sha256:0827aed5d6b5dc697c5bcd86e1de62bb8aec9aa7ea89bf89bed0aaa072174ed4", "policy-rule:sha256:a72302da8f28a3b6d13bf74f835e9c6b7d967179c45b88f23e5d783336e03e3c"]} |
| T02 | decision_forbidden | 예 | 0827aed5(1.92), a72302da(0.69), 6a0041a7(0.63) | 0827aed5, a72302da, 6a0041a7 | - | 0827aed5, a72302da, 6a0041a7 | - | - | - | 노출 0827aed5, a72302da, 6a0041a7; 유출 {"a_filter_defect": [], "b_relevance_defect": ["policy-rule:sha256:0827aed5d6b5dc697c5bcd86e1de62bb8aec9aa7ea89bf89bed0aaa072174ed4", "policy-rule:sha256:a72302da8f28a3b6d13bf74f835e9c6b7d967179c45b88f23e5d783336e03e3c", "policy-rule:sha256:6a0041a73cc2fdc4fde53d15662e34fa754ab86a07f74127a3f3722aa918143a"]} |
| T03 | decision_forbidden | 예 | 0827aed5(1.33), 6a0041a7(0.45) | 0827aed5, 6a0041a7 | - | 0827aed5, 6a0041a7 | - | - | - | 노출 0827aed5, 6a0041a7; 유출 {"a_filter_defect": [], "b_relevance_defect": ["policy-rule:sha256:0827aed5d6b5dc697c5bcd86e1de62bb8aec9aa7ea89bf89bed0aaa072174ed4", "policy-rule:sha256:6a0041a73cc2fdc4fde53d15662e34fa754ab86a07f74127a3f3722aa918143a"]} |
| T04 | decision_forbidden | 예 | 0827aed5(1.02), 6a0041a7(0.18), a72302da(0.15) | 0827aed5, 6a0041a7, a72302da | - | 0827aed5, 6a0041a7, a72302da | - | - | - | 노출 0827aed5, 6a0041a7, a72302da; 유출 {"a_filter_defect": [], "b_relevance_defect": ["policy-rule:sha256:0827aed5d6b5dc697c5bcd86e1de62bb8aec9aa7ea89bf89bed0aaa072174ed4", "policy-rule:sha256:6a0041a73cc2fdc4fde53d15662e34fa754ab86a07f74127a3f3722aa918143a", "policy-rule:sha256:a72302da8f28a3b6d13bf74f835e9c6b7d967179c45b88f23e5d783336e03e3c"]} |
| T05 | decision_forbidden | 예 | 0827aed5(1.57) | 0827aed5 | - | 0827aed5 | - | - | - | 노출 0827aed5; 유출 {"a_filter_defect": [], "b_relevance_defect": ["policy-rule:sha256:0827aed5d6b5dc697c5bcd86e1de62bb8aec9aa7ea89bf89bed0aaa072174ed4"]} |
| T06 | decision_forbidden | 예 | 0827aed5(0.65), 6a0041a7(0.18), a72302da(0.15) | 0827aed5, 6a0041a7, a72302da | - | 0827aed5, 6a0041a7, a72302da | - | - | - | 노출 0827aed5, 6a0041a7, a72302da; 유출 {"a_filter_defect": [], "b_relevance_defect": ["policy-rule:sha256:0827aed5d6b5dc697c5bcd86e1de62bb8aec9aa7ea89bf89bed0aaa072174ed4", "policy-rule:sha256:6a0041a73cc2fdc4fde53d15662e34fa754ab86a07f74127a3f3722aa918143a", "policy-rule:sha256:a72302da8f28a3b6d13bf74f835e9c6b7d967179c45b88f23e5d783336e03e3c"]} |
| T07 | decision_forbidden | 예 | a72302da(1.43), 0827aed5(1.31), 6a0041a7(1.31) | a72302da, 0827aed5, 6a0041a7 | - | a72302da, 0827aed5, 6a0041a7 | - | - | - | 노출 a72302da, 0827aed5, 6a0041a7; 유출 {"a_filter_defect": [], "b_relevance_defect": ["policy-rule:sha256:a72302da8f28a3b6d13bf74f835e9c6b7d967179c45b88f23e5d783336e03e3c", "policy-rule:sha256:0827aed5d6b5dc697c5bcd86e1de62bb8aec9aa7ea89bf89bed0aaa072174ed4", "policy-rule:sha256:6a0041a73cc2fdc4fde53d15662e34fa754ab86a07f74127a3f3722aa918143a"]} |
| T08 | decision_forbidden | 예 | 6a0041a7(0.18), a72302da(0.15), 0827aed5(0.12) | 6a0041a7, a72302da, 0827aed5 | - | 6a0041a7, a72302da, 0827aed5 | - | - | - | 노출 6a0041a7, a72302da, 0827aed5; 유출 {"a_filter_defect": [], "b_relevance_defect": ["policy-rule:sha256:6a0041a73cc2fdc4fde53d15662e34fa754ab86a07f74127a3f3722aa918143a", "policy-rule:sha256:a72302da8f28a3b6d13bf74f835e9c6b7d967179c45b88f23e5d783336e03e3c", "policy-rule:sha256:0827aed5d6b5dc697c5bcd86e1de62bb8aec9aa7ea89bf89bed0aaa072174ed4"]} |
| T09 | hold | 예 | 0827aed5(2.77), 6a0041a7(1.54), a72302da(0.69) | 0827aed5, 6a0041a7, a72302da | - | 0827aed5, 6a0041a7, a72302da | - | - | - | 노출 0827aed5, 6a0041a7, a72302da; 유출 {"a_filter_defect": [], "b_relevance_defect": ["policy-rule:sha256:0827aed5d6b5dc697c5bcd86e1de62bb8aec9aa7ea89bf89bed0aaa072174ed4", "policy-rule:sha256:6a0041a73cc2fdc4fde53d15662e34fa754ab86a07f74127a3f3722aa918143a", "policy-rule:sha256:a72302da8f28a3b6d13bf74f835e9c6b7d967179c45b88f23e5d783336e03e3c"]} |
| T10 | hold | 예 | 0827aed5(1.79), 6a0041a7(0.95) | 0827aed5, 6a0041a7 | - | 0827aed5, 6a0041a7 | - | - | - | 노출 0827aed5, 6a0041a7; 유출 {"a_filter_defect": [], "b_relevance_defect": ["policy-rule:sha256:0827aed5d6b5dc697c5bcd86e1de62bb8aec9aa7ea89bf89bed0aaa072174ed4", "policy-rule:sha256:6a0041a73cc2fdc4fde53d15662e34fa754ab86a07f74127a3f3722aa918143a"]} |
| T11 | hold | 예 | a72302da(1.28), 6a0041a7(0.18), 0827aed5(0.12) | a72302da, 6a0041a7, 0827aed5 | - | a72302da, 6a0041a7, 0827aed5 | - | - | - | 노출 a72302da, 6a0041a7, 0827aed5; 유출 {"a_filter_defect": [], "b_relevance_defect": ["policy-rule:sha256:a72302da8f28a3b6d13bf74f835e9c6b7d967179c45b88f23e5d783336e03e3c", "policy-rule:sha256:6a0041a73cc2fdc4fde53d15662e34fa754ab86a07f74127a3f3722aa918143a", "policy-rule:sha256:0827aed5d6b5dc697c5bcd86e1de62bb8aec9aa7ea89bf89bed0aaa072174ed4"]} |
| T12 | hold | 예 | 0827aed5(1.31), 6a0041a7(1.09), a72302da(0.15) | 0827aed5, 6a0041a7, a72302da | - | 0827aed5, 6a0041a7, a72302da | - | - | - | 노출 0827aed5, 6a0041a7, a72302da; 유출 {"a_filter_defect": [], "b_relevance_defect": ["policy-rule:sha256:0827aed5d6b5dc697c5bcd86e1de62bb8aec9aa7ea89bf89bed0aaa072174ed4", "policy-rule:sha256:6a0041a73cc2fdc4fde53d15662e34fa754ab86a07f74127a3f3722aa918143a", "policy-rule:sha256:a72302da8f28a3b6d13bf74f835e9c6b7d967179c45b88f23e5d783336e03e3c"]} |
| T13 | hold | 예 | 0827aed5(3.12), a72302da(0.54), 6a0041a7(0.20) | 0827aed5, a72302da, 6a0041a7 | - | 0827aed5, a72302da, 6a0041a7 | - | - | - | 노출 0827aed5, a72302da, 6a0041a7; 유출 {"a_filter_defect": [], "b_relevance_defect": ["policy-rule:sha256:0827aed5d6b5dc697c5bcd86e1de62bb8aec9aa7ea89bf89bed0aaa072174ed4", "policy-rule:sha256:a72302da8f28a3b6d13bf74f835e9c6b7d967179c45b88f23e5d783336e03e3c", "policy-rule:sha256:6a0041a73cc2fdc4fde53d15662e34fa754ab86a07f74127a3f3722aa918143a"]} |
| T14 | hold | 예 | a72302da(1.12), 0827aed5(0.90), 6a0041a7(0.76) | a72302da, 0827aed5, 6a0041a7 | - | a72302da, 0827aed5, 6a0041a7 | - | - | - | 노출 a72302da, 0827aed5, 6a0041a7; 유출 {"a_filter_defect": [], "b_relevance_defect": ["policy-rule:sha256:a72302da8f28a3b6d13bf74f835e9c6b7d967179c45b88f23e5d783336e03e3c", "policy-rule:sha256:0827aed5d6b5dc697c5bcd86e1de62bb8aec9aa7ea89bf89bed0aaa072174ed4", "policy-rule:sha256:6a0041a73cc2fdc4fde53d15662e34fa754ab86a07f74127a3f3722aa918143a"]} |
| T15 | hold | 예 | 0827aed5(2.22), 6a0041a7(0.45) | 0827aed5, 6a0041a7 | - | 0827aed5, 6a0041a7 | - | - | - | 노출 0827aed5, 6a0041a7; 유출 {"a_filter_defect": [], "b_relevance_defect": ["policy-rule:sha256:0827aed5d6b5dc697c5bcd86e1de62bb8aec9aa7ea89bf89bed0aaa072174ed4", "policy-rule:sha256:6a0041a73cc2fdc4fde53d15662e34fa754ab86a07f74127a3f3722aa918143a"]} |
| T16 | hold | 예 | 6a0041a7(4.48), 0827aed5(1.96), a72302da(0.31) | 6a0041a7, 0827aed5, a72302da | - | 6a0041a7, 0827aed5, a72302da | - | - | - | 노출 6a0041a7, 0827aed5, a72302da; 유출 {"a_filter_defect": [], "b_relevance_defect": ["policy-rule:sha256:6a0041a73cc2fdc4fde53d15662e34fa754ab86a07f74127a3f3722aa918143a", "policy-rule:sha256:0827aed5d6b5dc697c5bcd86e1de62bb8aec9aa7ea89bf89bed0aaa072174ed4", "policy-rule:sha256:a72302da8f28a3b6d13bf74f835e9c6b7d967179c45b88f23e5d783336e03e3c"]} |
| T17 | current_value |  | 0827aed5(5.34), 6a0041a7(1.91), a72302da(1.12) | 0827aed5, 6a0041a7, a72302da | - | 0827aed5, 6a0041a7, a72302da | 1.000 | 1.000 | 0.333 | 무관 6a0041a7, a72302da |
| T18 | paraphrase |  | 0827aed5(3.62), 6a0041a7(0.91) | 0827aed5, 6a0041a7 | - | 0827aed5, 6a0041a7 | 1.000 | 1.000 | 0.500 | 무관 6a0041a7 |
| T19 | paraphrase |  | 0827aed5(4.81), 6a0041a7(2.31), a72302da(1.54) | 0827aed5, 6a0041a7, a72302da | - | 0827aed5, 6a0041a7, a72302da | 1.000 | 0.500 | 0.333 | 무관 0827aed5, a72302da |
| T20 | direct |  | a72302da(4.37), 0827aed5(1.23), 6a0041a7(0.96) | a72302da, 0827aed5, 6a0041a7 | - | a72302da, 0827aed5, 6a0041a7 | 1.000 | 1.000 | 0.333 | 무관 0827aed5, 6a0041a7 |
| T21 | numeric |  | 0827aed5(6.36), 6a0041a7(1.99), a72302da(0.15) | 0827aed5, 6a0041a7, a72302da | - | 0827aed5, 6a0041a7, a72302da | 1.000 | 1.000 | 0.333 | 무관 6a0041a7, a72302da |
| T22 | direct |  | a72302da(2.55), 6a0041a7(0.96), 0827aed5(0.24) | a72302da, 6a0041a7, 0827aed5 | - | a72302da, 6a0041a7, 0827aed5 | 1.000 | 1.000 | 0.333 | 무관 6a0041a7, 0827aed5 |
| T23 | numeric |  | 0827aed5(6.79), 6a0041a7(2.44), a72302da(0.15) | 0827aed5, 6a0041a7, a72302da | - | 0827aed5, 6a0041a7, a72302da | 1.000 | 1.000 | 0.667 | 무관 a72302da |
| T24 | old_value_apply |  | 0827aed5(3.71), 6a0041a7(2.53), a72302da(1.82) | 0827aed5, 6a0041a7, a72302da | - | 0827aed5, 6a0041a7, a72302da | 1.000 | 1.000 | 0.333 | 무관 6a0041a7, a72302da |

실패 질의: T01, T02, T03, T04, T05, T06, T07, T08, T09, T10, T11, T12, T13, T14, T15, T16
