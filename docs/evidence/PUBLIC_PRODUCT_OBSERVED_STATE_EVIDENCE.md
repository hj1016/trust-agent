# 공개 상품 관측 상태 조회 검증 기록

## 구현 범위

Day 4c에서는 PostgreSQL에 적재된 공개 상품 관측 이력을 조회하는 endpoint와 공개 근거
freshness 및 confirmation policy를 구현했습니다.

```text
GET /api/v1/public-products/{productKey}/observed-state?asOf={RFC3339 instant}
```

실제 승인 API, 내부 공문, 상담 준비안, runtime ingestion과 Batch는 이 단계에 포함하지
않습니다.

## 서비스에서 담당하는 역할

이 조회는 상품 조건, 광고 금리, 원문 위치, 마지막 확인 시각과 최신 처리 상태를 하나의
응답으로 연결합니다. 후속 서비스가 DB 테이블이나 HTML을 직접 해석하지 않고도 공개
근거가 현재 상담에 사용할 만큼 최근인지 확인할 수 있게 합니다.

`publicEvidenceConfirmationAllowed`는 공개 근거 최신성 조건의 결과일 뿐입니다. 전체 AI
확정이나 업무 승인을 뜻하지 않으며, 후속 승인 use case가 다른 필수 조건과 함께 다시
검사해야 합니다.

## 필요한 이유

- `asOf`를 freshness 기준으로 사용하면 과거 정보가 현재도 확정 가능한 것처럼 보입니다.
- Observation 직후 추출이 끝나지 않았는데 `observed_at`만 보고 evidence를 노출하면 당시
  시스템이 알지 못했던 내용을 과거 조회에 만들어 냅니다.
- 최신 관측의 추출이 실패했는데 이전 `SELLING` 조건만 반환하면 소비 계층에서
  fail-open이 됩니다.
- Python과 Java가 서로 다른 freshness 판정을 하면 확정 차단 결과가 실행 경로에 따라
  달라집니다.

## 구현 결정

### 두 개의 조회 시각

- 요청 시작 시 `Clock`을 한 번 읽어 `evaluatedAt`으로 고정합니다.
- `asOf`는 사건을 어디까지 보여 줄지 정하는 cutoff입니다. 생략할 때만
  `evaluatedAt`을 사용합니다.
- Freshness 경과 시간은 항상 `evaluatedAt - lastConfirmedAt`으로 계산합니다.
- 과거 조회는 허용하지만 `HISTORICAL_AS_OF`로 확정을 차단합니다.
- 미래 `asOf`는 `FUTURE_AS_OF_NOT_ALLOWED`, 비었거나 해석할 수 없는 값은
  `INVALID_AS_OF`로 거부합니다.
- Offset이 있는 RFC 3339 입력은 UTC `Instant`로 정규화합니다.

### Evidence visibility

- Observation은 `observed_at <= asOf`일 때 조회 범위에 들어옵니다.
- Terms, quote와 evidence는 이를 만든 성공 ExtractionAttempt의
  `attempted_at <= asOf` 조건까지 만족해야 보입니다.
- `lastConfirmedAt`은 Observation의 `observed_at`, evidence의 `availableAt`은 추출
  `attempted_at`으로 반환합니다.
- 테스트 fixture는 Observation 09:00, ExtractionAttempt 10:00을 사용합니다. 09:30
  조회에는 이전 evidence만 보이고 최신 Observation은 `PENDING_EXTRACTION`, 10:00
  조회부터 새 evidence가 보입니다.

### Freshness와 확정 차단

- 대표 상태 우선순위는
  `UNAVAILABLE > UNCONFIRMED_AFTER_FAILURE > PENDING_EXTRACTION > STALE > CONFIRMED`입니다.
- 현재 조회이고 대표 상태가 `CONFIRMED`일 때만
  `publicEvidenceConfirmationAllowed=true`입니다.
- 수집 실패는 허용 기간 안에서는 warning으로 남기고, 확인 경과 시간이 임계를 넘으면
  `STALE`로 차단합니다.
- 동일한 `observed_at`의 여러 Observation은 ID 내림차순으로 고릅니다. 마지막 성공
  Observation과 ID가 다르면 시각이 같아도 새 관측으로 판단합니다.
- 정책 version과 최대 확인 허용 기간은 설정이며 production에서는 둘 다 필수입니다.

### 공통 정책 정답표

`contracts/fixtures/public-product-confirmation-policy-cases.json`을 Python과 Java가 함께
읽습니다. 경계값 직전·일치·직후, pending, 추출 실패, 수집 warning, unavailable,
과거 조회와 동일 관측시각·다른 Observation을 포함합니다.

### 조회 index

Flyway V3는 최신 Observation, 최신 collection·extraction attempt와 성공 추출 조회용
index 네 개를 추가합니다. 애플리케이션의 기대 schema version도 3으로 올렸습니다.
V2 DB에 새 애플리케이션을 먼저 연결하면 readiness는 의도대로
`SCHEMA_VERSION_MISMATCH`가 됩니다. V3 migration 적용 후 애플리케이션을 배포해야
합니다. 현재 baseline 규모에는 일반 `CREATE INDEX`를 사용하지만, 운영 데이터가 쌓인
테이블의 후속 migration은 `CREATE INDEX CONCURRENTLY`와
`flyway:executeInTransaction=false`를 함께 사용하고 별도 운영 검증을 거칩니다.

## 가능한 대안

### 최신 상태만 제공

응답은 단순하지만 특정 시점에 시스템이 알고 있던 내용을 재현할 수 없어 선택하지
않았습니다.

### `asOf`로 freshness까지 평가

과거 시점에는 항상 신선했던 것처럼 보이고 현재 업무 확정에 사용될 수 있어 배제했습니다.

### 화면에서만 차단

API나 다른 소비자가 경고를 우회할 수 있으므로 Core service 정책으로 계산합니다. 다만
실제 승인 use case가 생기면 boolean을 그대로 신뢰하지 않고 정책을 직접 호출해야 합니다.

### Java만 freshness 구현

기존 Python 기준과 조용히 달라질 수 있어 공유 JSON 정답표를 선택했습니다.

## 테스트와 검증 evidence

### Java와 PostgreSQL

```bash
JAVA_HOME=/Users/faker/Library/Java/JavaVirtualMachines/ms-21.0.11/Contents/Home \
  ./gradlew clean test bootJar --offline --no-daemon
```

결과:

```text
BUILD SUCCESSFUL
53 tests completed
Spring Boot executable jar 생성 성공
```

자동 검증 범위는 다음과 같습니다.

- 공개 상품 3개 endpoint `200`, 미등록 상품 `404`
- 현재 요청에서 `asOf == evaluatedAt`과 요청당 Clock 1회 읽기
- 미래·잘못된·빈 `asOf` 오류 code
- Offset 입력의 UTC 정규화
- 과거 조회의 `HISTORICAL_AS_OF`와 확정 차단
- Observation과 ExtractionAttempt 사이 시각의 evidence 비노출
- 성공 attempt 시각부터 새 terms, quote와 evidence 노출
- `lastConfirmedAt=observed_at`, `evidence.availableAt=attempted_at`
- 동일 관측시각의 Observation ID 안정 정렬
- DB 접근 실패가 `UNAVAILABLE`로 변환되지 않는지 확인
- 모든 API 요청에 생성한 추적 ID가 `X-Trace-Id` header, 오류 Problem Detail과 로그를
  연결하는지 확인
- 오류 code와 HTTP status의 명시적 매핑 및 미등록 code의 `500` 처리
- DB 장애 `503 DATABASE_UNAVAILABLE`, 근거 무결성 오류
  `500 EVIDENCE_INTEGRITY_VIOLATION`, 예상하지 못한 오류 `500 INTERNAL_ERROR`
- 예외 상세, 내부 경로와 DB 접속 정보의 오류 응답 비노출
- 셀러론 법인 한도 `2,000,000,000 KRW`와 광고 금리 기준일 `null`
- `effectiveFrom`, `effectiveTo`의 `null` 보존
- 공통 정책 정답표 전체를 Python과 Java가 각각 통과
- 정책 설정 누락·0 이하 허용 기간 거부
- V3 query index 4개와 schema version 3 확인
- ExtractionAttempt가 Observation보다 빠른 baseline 거부

### Python private와 Public Git 조건

```text
Python private: Ran 53 tests, OK
Public Git: Ran 53 tests, OK (skipped=3)
```

Public Git에서는 비공개 raw snapshot이 필요한 무결성, golden 재추출과 migration 재현
테스트 3개만 의도대로 skip됐고 나머지 50개가 통과했습니다.

## 위험과 제한사항

- 응답은 현재 상품당 데이터가 작다는 전제에서 fact와 locator 전체를 반환합니다. 크기가
  커지면 pagination 또는 evidence 전용 endpoint를 별도 계약으로 추가해야 합니다.
- `maxConfirmationAge`의 운영 값은 규정담당자와 운영자가 승인해야 합니다. 기본 24시간은
  개발 기준이며 은행 정책을 대신하지 않습니다.
- Runtime ingestion use case는 아직 없습니다. 이 단계의 지연 추출 데이터는 테스트
  fixture입니다.
- 인증·인가, rate limiting, 백업·복구, 고가용성, SLO와 실제 승인 차단 API는 구현하지
  않았으므로 운영 준비 완료가 아닙니다.
- 수기 체크리스트 기능은 아직 없으므로 freshness 차단과 독립적으로 제공된다는 항목은
  이번 단계에서 완료 처리하지 않습니다.

## 다음 작업과의 연결

Day 5의 합성 내부 공문과 상담 준비안은 이 endpoint의 공개 근거를 입력으로 사용합니다.
업무상 효력은 내부 공문이 결정하며, 공개 상품 정보는 교차 검증과 변경 감지 근거로만
사용합니다. 실제 승인 use case는 공개 근거 freshness 외의 필수 조건을 함께 검사하고,
AI가 전체 승인을 확정하지 못하도록 설계해야 합니다.
