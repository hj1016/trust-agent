# Core Service

Spring Boot 기반 핵심 서비스입니다. Day 4a의 애플리케이션 골격과 감사 저장 기반,
Day 4b의 baseline importer, Day 4c의 공개 상품 관측 상태 조회와 freshness 및
confirmation policy, Day 5a와 5b의 합성 공문 적재 및 기준일 조회를 구현했습니다.
Flyway V5부터는 최종 기획서의 대표 사례인 중도상환수수료 변경 전후, 시행일, 조건과
예외를 `structuredChange`로 보존하고 적용 공문 조회 API에서 반환합니다. 변경 proposal,
자동 검증 결과와 사람의 제공 승인은 아직 구현하지 않았습니다. 일반 서버 기동 중에는
baseline importer bean을 만들거나 데이터를 자동 적재하지 않습니다.

Core 업무 DB는 PostgreSQL이며(ADR-009) 이 README의 구현 설명은 PostgreSQL 18.6 기준입니다.

## 합성 내부 공문 기준일 조회

```text
GET /api/v1/internal-policy/checklists/{familyId}/applicable
    ?businessDate=YYYY-MM-DD
    &knownAt=RFC3339 instant
```

최종 기획서 대표 family는 `SIN-PREPAYMENT-FEE`입니다. 응답은 합성 고지, 선택된 공문,
구조화 규칙과 원문 JSON Pointer 근거를 포함합니다. 검증과 사람 승인 전에는
`internalChecklistUseAllowed=false`로 유지합니다.

## 공개 상품 관측 상태 조회

```text
GET /api/v1/public-products/{productKey}/observed-state?asOf={RFC3339 instant}
```

`asOf`는 그 시각까지 시스템에 들어와 있던 사건만 보여 주는 기준입니다. 생략하면 요청을
시작할 때 한 번 읽은 현재 시각을 사용합니다. Freshness는 과거의 `asOf`가 아니라 실제
평가 시각인 `evaluatedAt`을 기준으로 계산합니다.

- 미래 `asOf`: `400 FUTURE_AS_OF_NOT_ALLOWED`
- 잘못되거나 빈 `asOf`: `400 INVALID_AS_OF`
- 등록되지 않은 상품: `404 PRODUCT_NOT_FOUND`
- 과거 조회: 조회는 허용하지만 `publicEvidenceConfirmationAllowed=false`와
  `HISTORICAL_AS_OF` 반환
- DB 장애: freshness의 `UNAVAILABLE`로 숨기지 않고
  `503 DATABASE_UNAVAILABLE` 반환
- 근거 무결성 오류: `500 EVIDENCE_INTEGRITY_VIOLATION`
- 그 밖의 예상하지 못한 오류: `500 INTERNAL_ERROR`

모든 API 응답에는 `X-Trace-Id` header가 있습니다. 오류 응답은 같은 값을 Problem
Detail의 `traceId`로 반환하고, 요청 완료 로그에도 기록합니다. 서버의 예외 메시지,
내부 경로와 DB 접속 정보는 오류 응답에 포함하지 않습니다. 오류 code와 HTTP status는
명시적인 매핑으로 관리하며, 등록되지 않은 code는 안전하게 `500`으로 처리합니다.

Observation을 먼저 수집하고 나중에 추출한 경우, 성공 ExtractionAttempt의
`attempted_at` 전에는 새 terms, quote와 evidence를 반환하지 않습니다. 응답의
`lastConfirmedAt`은 근거가 설명하는 Observation의 `observed_at`이고, evidence의
`availableAt`은 시스템이 근거를 만든 `attempted_at`입니다.

운영에서는 다음 설정을 명시해야 합니다.

```text
TRUST_AGENT_FRESHNESS_POLICY_VERSION=public-evidence-confirmation-v1
TRUST_AGENT_MAX_CONFIRMATION_AGE=24h
```

`publicEvidenceConfirmationAllowed`는 공개 근거의 최신성 조건 하나만 나타냅니다. 전체 AI
확정이나 업무 승인을 허용하는 값이 아니며, 실제 승인 use case는 아직 구현하지
않았습니다.

응답 구조의 축약 예시는 다음과 같습니다.

```json
{
  "productKey": "kb-seller-loan",
  "asOf": "2026-09-23T12:00:00Z",
  "evaluatedAt": "2026-09-23T12:00:00Z",
  "historicalQuery": false,
  "lastConfirmedAt": "2026-09-23T09:00:00Z",
  "latestObservationAt": "2026-09-23T09:00:00Z",
  "freshnessStatus": "CONFIRMED",
  "blockingReasons": [],
  "warningReasons": [],
  "publicEvidenceConfirmationAllowed": true,
  "confirmationBlockingReasons": [],
  "freshnessPolicyVersion": "public-evidence-confirmation-v1",
  "maxConfirmationAge": "PT24H",
  "terms": { "productTermsVersionId": "...", "facts": [] },
  "confirmedObservation": { "observationId": "...", "snapshotHash": "sha256:..." },
  "rateQuote": { "advertisedRateText": "...", "advertisedRateReferenceDate": null },
  "evidence": { "versionEvidenceId": "...", "availableAt": "...", "facts": [] }
}
```

전체 DB 관계는 `docs/PUBLIC_PRODUCT_ERD.md`에 정리했습니다.

## Baseline 적재

Baseline importer는 `datasets/public/kb`와 `datasets/derived/public-kb`의 허용된 JSON만
읽는 bootstrap 명령입니다. Flyway migration을 먼저 끝낸 DB에 명시적으로 실행합니다.
API 서버용 pool과 분리된 importer pool을 사용하며, 운영에서는 importer 전용 로그인
계정을 반드시 주입합니다.

아래 `trust_agent_runtime_user`와 `trust_agent_import_user`는 예시 이름이며 Flyway가
LOGIN role이나 비밀번호를 만들지 않습니다. DB 관리 절차에서 별도로 만들고, importer
계정에는 Flyway가 만든 NOLOGIN 권한 묶음 `trust_agent_importer`만 부여합니다. 이 role은
baseline 테이블의 `SELECT`와 `INSERT`만 가지며 `UPDATE`, `DELETE`, `TRUNCATE`와 DDL은
허용하지 않습니다. Runtime, migration, maintenance role이나 table owner를 importer
계정으로 사용하지 않습니다. `prod` profile은 importer를 켰을 때 세 importer 환경
변수가 빠지면 runtime 계정으로 대신하지 않고 기동을 거부합니다.

```bash
TRUST_AGENT_DB_URL=jdbc:postgresql://localhost:5432/trust_agent \
TRUST_AGENT_DB_USERNAME=trust_agent_runtime_user \
TRUST_AGENT_DB_PASSWORD='runtime-password' \
TRUST_AGENT_IMPORT_DB_URL=jdbc:postgresql://localhost:5432/trust_agent \
TRUST_AGENT_IMPORT_DB_USERNAME=trust_agent_import_user \
TRUST_AGENT_IMPORT_DB_PASSWORD='import-password' \
JAVA_HOME=$(/usr/libexec/java_home -v 21) \
./gradlew :apps:core-service:bootRun --offline --no-daemon \
  --args='--spring.main.web-application-type=none --trust-agent.baseline-import.enabled=true --trust-agent.baseline-import.root=/absolute/path/to/trust-agent'
```

`--trust-agent.baseline-import.run-id=baseline:<32자리 hex>`를 생략하면 실행마다 새 ID를
만듭니다. 같은 실행을 다시 시도할 때도 새 run ID를 사용해야 합니다. 성공과 실패는
`baseline_import_run`에 별도 사건으로 남습니다.

Importer는 baseline 동기화 도구가 아닙니다. 다른 fingerprint를 기존 DB에 섞거나
없어진 파일에 맞춰 DB 행을 삭제하지 않습니다. Runtime ingestion이 시작되기 전
baseline을 바꿔야 한다면 새 DB 또는 새 schema에 전체 적재하고 검증한 뒤 전환합니다.
운영 Observation이 쌓인 뒤에는 이 절차를 사용하지 않고 별도 migration ADR이
필요합니다. 같은 fingerprint 재실행도 baseline-only DB에서만 멱등 성공하며, 입력에
없는 runtime 행이 있으면 `RUNTIME_DATA_PRESENT`로 거부합니다.

## 검증

Java 21과 Docker daemon이 필요합니다.

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew clean test bootJar --offline --no-daemon
```

테스트는 digest로 고정한 PostgreSQL 18.6 Testcontainer에서 Flyway와 DB 권한을 직접
검증합니다. 운영 DB의 URL, username과 password는 환경 변수로 주입하며 저장소에
커밋하지 않습니다. 구현 범위와 검증 결과는
`docs/evidence/DAY_04A_EVIDENCE.md`, `docs/evidence/DAY_04B_EVIDENCE.md`와
`docs/evidence/DAY_04C_EVIDENCE.md`에 기록합니다.

Management endpoint는 기본적으로 `127.0.0.1:8081`에 별도로 열립니다. 배포 환경에서
address를 바꿀 때는 외부 ingress에 노출하지 않고 내부 probe와 운영자 경로만 허용해야
합니다. `prod` profile은 DB URL, username, password, 기대 schema version,
freshness policy version과 max confirmation age가 모두 명시되지 않으면 기동하지
않습니다.
