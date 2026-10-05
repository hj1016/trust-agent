# 적용 공문 조회 시간 경계 테스트 보완 검증 기록

## 변경 범위

- `InternalPolicyApplicableIntegrationTest`에 테스트 3건 추가. 업무 로직(`src/main`)은 변경하지 않았다.
- 테스트의 고정 시계를 "기본값은 2026-10-05T03:00:00Z로 고정, 서울 자정 테스트 안에서만 평가 시각을 바꾸고 끝나면 되돌리는" 테스트 전용 시계로 바꿨다. 서비스는 시계에서 현재 시각만 읽으므로 동작 차이가 없다.

## 추가한 테스트가 보장하는 것

| 테스트 | 보장 |
|---|---|
| 같은 업무일 | 평가 업무일(서울 기준 2026-10-05)과 같은 날 조회는 미래 업무일로 차단되지 않는다. 승인 checklist 예시 데이터가 있는 family(`SIN-SELLER-CHECKLIST`)는 `AVAILABLE`이지만 검증과 공개 근거 재평가가 없어 사용 불가(`CURRENT_VALIDATION_NOT_EVALUATED`, `CURRENT_PUBLIC_EVIDENCE_NOT_EVALUATED`). 승인 checklist가 없는 family(`SIN-PREPAYMENT-FEE`)는 `PENDING_VALIDATION`으로 사용 불가 |
| 같은 기준 시각 | 인지 시각이 평가 시각과 정확히 같으면 과거 지식 조회로 취급하지 않는다(200, `historicalKnownAt=false`). 1초 앞이면 과거로 취급해 `HISTORICAL_KNOWN_AT`이 붙는다. 1초 뒤 400은 기존 테스트가 확인한다. 시간 사유가 없어도 검증 미완료 사유로 사용은 차단된다 |
| 서울 자정 전후 | UTC 2026-10-05 15:30(서울 10-06 00:30)에는 평가 업무일이 10-06이라 10-06 조회가 미래가 아니고 10-07은 미래다. UTC 14:59:59(서울 23:59:59)에는 평가 업무일이 10-05라 10-06 조회가 미래다. UTC 날짜가 아니라 서울 날짜로 판정함을 증명 |

## 실행 결과

- 검증 대상 revision: 브랜치 `test/applicable-time-boundaries` HEAD. 환경: 로컬 macOS arm64, Java 21(ms-21.0.11), Docker, PostgreSQL 18.6 Testcontainers.
- `./gradlew clean test bootJar --offline --no-daemon`: **85개 통과, 실패 0, skip 0**(82 + 3), executable jar 생성.
- 해당 클래스 11개 전부 통과. 첫 실행에서 새 테스트 2건이 실패했는데 원인은 테스트 작성 시 승인 checklist 예시 데이터(V2용, 2026-10-02 생성)를 고려하지 않은 기대값이었다. 기대값을 실제 예시 데이터에 맞게 고쳤고 업무 로직은 바꾸지 않았다.
- Python 57개 통과(비공개 artifact 지정). 원격 CI 결과는 PR에 기록한다.

## 한계

- 서울 자정 테스트는 평가 시각을 바꾸기 위해 테스트 전용 조정 시계를 쓴다. 테스트는 같은 스레드에서 순차 실행되며 `finally`로 되돌리므로 다른 테스트에 영향이 없지만, 병렬 실행을 켜면 이 전제가 깨진다.
