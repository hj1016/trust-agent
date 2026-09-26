# 넷째 날 전체 검증 기록

Day 4는 한 번에 구현하지 않고 4a 기반, 4b 적재, 4c 조회와 확정 차단으로 나눴습니다.
세부 설계와 테스트는 다음 문서가 기준입니다.

- `DAY_04A_EVIDENCE.md`: Spring Boot, PostgreSQL schema, append-only 감사와 health
- `DAY_04B_EVIDENCE.md`: 공개·정제 baseline importer, 충돌·rollback과 최소 권한
- `DAY_04C_EVIDENCE.md`: 관측 상태 API, 시간 의미, evidence visibility와 freshness
- `../PUBLIC_PRODUCT_ERD.md`: Flyway V1~V3 기준 관계 요약

## 최종 자동 검증

2026-09-26 기준 결과입니다.

```text
Java/PostgreSQL: 53 tests, 0 failures, 0 errors
Spring Boot executable jar: 성공
Python private: 53 tests, OK
Python Public Git: 50 passed, 3 intentionally skipped
git diff --check: 통과
```

## Day 4가 제공하는 것

- Java 21과 Spring Boot 기반 Core service
- Flyway schema version 3과 PostgreSQL query index
- append-only 업무 record와 승인된 break-glass 변경 감사
- 공개·정제 JSON baseline의 재현 가능한 일괄 적재
- 공개 상품 3개의 상품 조건, quote, Observation과 evidence 조회
- 과거·미래 조회와 evidence 생성 시각을 구분하는 시간 계약
- 최신 관측의 pending·failure·stale 상태에 따른 공개 근거 확정 차단
- Python과 Java가 함께 검증하는 confirmation policy 정답표
- 응답·Problem Detail·로그를 연결하는 요청 추적 ID와 안정적인 오류 code

## 완료하지 않은 것

Day 4 완료는 운영 오픈을 뜻하지 않습니다. 다음 항목은 구현하지 않았습니다.

- 실제 승인 API와 행원의 최종 승인 기록
- runtime 수집·추출 ingestion과 정기 Batch
- 내부 공문, 상담 준비안과 수기 체크리스트
- 인증·인가, rate limiting과 network policy
- 백업·복구, 고가용성, SLO와 운영 모니터링 완성
- 실제 은행 정책으로 승인된 freshness 허용 기간

따라서 `publicEvidenceConfirmationAllowed`는 공개 근거 조건 하나의 판단이며, 전체 AI
확정이나 업무 승인을 대신하지 않습니다.
