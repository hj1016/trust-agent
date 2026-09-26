# 넷째 날 B단계 검증 기록

## 구현 범위

Day 4b에서는 Python pipeline이 만든 공개·정제 baseline을 PostgreSQL에 옮기는 명시적
importer를 구현했습니다. 조회 API와 freshness 및 confirmation policy는 Day 4c 범위로
남겼습니다.

Importer가 서비스에서 담당하는 역할은 파일과 DB 사이의 신뢰 경계를 지키는 것입니다.
JSON의 ID, 원문 연결과 처리 이력을 관계형 행으로 옮기되, 일부만 적재하거나 기존 기록을
조용히 덮어쓰지 않습니다.

## 필요한 이유

- 수동 SQL은 39개 입력 파일과 12개 업무 테이블의 관계를 빠뜨리기 쉽습니다.
- 서버 기동 시 자동 적재하면 서비스 시작과 데이터 이전의 실패 원인을 구분하기 어렵습니다.
- 같은 ID를 `ON CONFLICT DO NOTHING`으로만 처리하면 내용 충돌과 빠진 하위 행을 숨길 수
  있습니다.
- 기존 DB에 다른 baseline을 섞으면 Git의 기준 데이터와 조회 결과가 달라집니다.
- 실패한 적재 시도도 별도 감사 record가 없으면 실행하지 않은 경우와 구분하기 어렵습니다.

## 구현 결정

### 입력 경계와 hash

- 저장소 루트 또는 `datasets` 루트를 호출자가 명시합니다.
- `datasets/public/kb`와 `datasets/derived/public-kb` 아래의 catalog, manifest,
  Observation, terms version, evidence, quote와 세 종류의 처리 기록만 허용합니다.
- HTML, 허용되지 않은 JSON, symlink로 입력 경계 밖을 가리키는 파일을 거부합니다.
- 39개 파일의 저장소 상대 경로와 raw byte SHA-256을 정렬한 canonical JSON hash를
  `baseline_fingerprint`로 사용합니다.
- 현재 baseline fingerprint는
  `sha256:9e8cc358457024bd3c1520d5b8301499ac91d7def66706bf6a68b6ce45b29e3a`입니다.
- 각 최상위 record의 `source_record_hash`는 파일 byte hash가 아니라 해당 record의
  canonical JSON SHA-256입니다. Catalog 안의 상품은 상위 `dataset_class`와 `synthetic`
  값을 포함한 독립 record로 만든 뒤 hash를 계산합니다.

### 검증과 transaction

- 모든 파일을 먼저 9종 Java record DTO와 명시적인 enum으로 변환하고 field, ID, UTC
  `Z` 시각, URL allowlist와 참조 관계를 검증합니다. 적재 SQL도 문자열 enum을 다시
  해석하지 않고 이 typed record를 사용합니다.
- VersionEvidence가 terms fact 전체를 설명하는지, locator가 같은 Snapshot과 source
  URL을 가리키는지, 성공 ExtractionAttempt의 세 결과가 같은 Observation과 연결되는지
  확인합니다.
- 전체 업무 데이터를 하나의 transaction으로 적재합니다.
- 성공한 `baseline_import_run`도 업무 데이터와 같은 transaction에 기록합니다.
- 실패하면 업무 transaction을 rollback하고 별도 transaction으로 실패 run만 남깁니다.
  입력 목록을 정상적으로 읽은 뒤 검증이 실패한 경우 39개 경로와 hash 및 fingerprint도
  실패 기록에 보존합니다.
- 동시에 두 importer가 실행돼 서로의 사전 검증을 통과하지 않도록 PostgreSQL advisory
  transaction lock을 사용합니다.
- 실패 감사 기록 자체가 저장되지 않으면 `FAILURE_AUDIT_WRITE_FAILED`에 원래 오류 code를
  포함하고 원래 예외를 suppressed exception으로 보존합니다.

### 멱등성과 충돌

- 같은 fingerprint 재적재는 runtime ingestion 전의 baseline-only DB에서만 허용하며,
  최상위 record의 ID와 `source_record_hash`를 모두 비교합니다.
- ProductTermsVersion의 fact, VersionEvidence의 locator와 ChangeDetectionResult의
  classification은 개수와 모든 값 및 순서를 다시 비교합니다.
- 다른 fingerprint는 개별 hash 검사보다 먼저 `BASELINE_MISMATCH`로 거부합니다. 같은
  fingerprint인데 저장된 record hash가 다를 때만 `SOURCE_RECORD_CONFLICT`로 거부합니다.
- 같은 fingerprint이더라도 입력에 없는 행이 있으면 `RUNTIME_DATA_PRESENT`로 거부합니다.
  전체 건수 검증을 느슨하게 바꿔 여분의 하위 행을 숨기지 않습니다.
- 기존 행이나 파일에 없는 행을 importer가 수정하거나 삭제하지 않습니다.
- SQL에 들어가는 테이블명과 ID 컬럼명은 코드의 고정 allowlist 조합만 허용합니다.

### 실행 격리와 시간 타입

- 평상시 API 서버 기동에서는 importer bean을 만들지 않습니다.
- 명시적 command mode에서만 API용 datasource와 다른 Hikari pool을 만듭니다. 초기값은
  connection timeout 5초, validation timeout 2초, statement timeout 30초, 최대 pool
  크기 2입니다.
- Flyway V2는 NOLOGIN `trust_agent_importer` role에 baseline 테이블 `SELECT`와 `INSERT`만
  부여합니다. `prod` profile은 importer URL, username과 password를 명시적으로 요구하며
  runtime 자격증명으로 fallback하지 않습니다.
- V2 추가로 애플리케이션의 기대 schema version은 1에서 2로 올라갔습니다. V1까지만
  적용된 DB에 새 애플리케이션을 먼저 연결하면 readiness가
  `SCHEMA_VERSION_MISMATCH`로 `DOWN`이 됩니다. 이는 migration을 먼저 적용하고
  애플리케이션을 나중에 배포하도록 강제하는 의도된 동작입니다.
- Java의 업무 시각은 `Instant`로 유지합니다. PostgreSQL JDBC 경계에서만 UTC
  `OffsetDateTime`으로 바꾸고 DB에는 `timestamptz`로 저장합니다.
- `Timestamp`는 timezone 의미가 타입에 드러나지 않으므로 최종 구현에서 사용하지
  않았습니다.
- Jackson 3에서 deprecated된 `textValue()`와 `isTextual()` 대신 `stringValue()`와
  `isString()`을 사용합니다.

## 검토한 대안

### Flyway seed SQL

DB 생성은 단순하지만 JSON contract와 SQL을 이중 관리해야 하고 record hash 및 입력 파일
감사를 재현하기 어렵기 때문에 선택하지 않았습니다.

### 서버 기동 시 자동 적재

시연은 간단하지만 잘못된 경로나 fingerprint가 서비스 시작을 데이터 이전 작업으로
바꿉니다. 일반 서버와 분리한 명시적 command를 선택했습니다.

### Python에서 DB에 직접 적재

기존 pipeline과 가깝지만 Spring이 실제로 소비할 contract를 Java에서 독립적으로
검증하지 못합니다. Java importer가 같은 baseline을 다시 해석하도록 했습니다.

### `ON CONFLICT DO NOTHING`

동일 ID의 다른 내용과 빠진 하위 행을 정상 재실행으로 숨기므로 사용하지 않았습니다.

## 정확한 baseline 적재 결과

| 테이블 | 행 수 |
| --- | ---: |
| `public_product` | 3 |
| `public_snapshot` | 5 |
| `collection_attempt` | 5 |
| `public_observation` | 5 |
| `product_terms_version` | 3 |
| `product_term_fact` | 16 |
| `version_evidence` | 5 |
| `fact_evidence_locator` | 30 |
| `observed_rate_quote` | 5 |
| `extraction_attempt` | 5 |
| `change_detection_result` | 5 |
| `change_detection_classification` | 5 |
| 최초 성공 `baseline_import_run` | 1 |

Migration으로 만든 ExtractionAttempt 5건은 모두
`attempted_at_source=BACKFILLED_FROM_OBSERVATION`으로 적재됐습니다.

## 테스트와 검증 evidence

### Java와 PostgreSQL

```bash
JAVA_HOME=/Users/faker/Library/Java/JavaVirtualMachines/ms-21.0.11/Contents/Home \
  ./gradlew clean test bootJar --offline --no-daemon
```

결과:

```text
BUILD SUCCESSFUL
37 tests completed
Spring Boot executable jar 생성 성공
```

주요 자동 검증은 다음과 같습니다.

- 명시적 command mode에서 별도 importer pool 생성과 baseline 1회 적재
- 평상시 서버 기동에서 importer bean과 자동 적재 부재
- 12개 업무 테이블의 정확한 행 수와 9종 최상위 JSON ID 전체 일치
- 모든 최상위 `source_record_hash`와 canonical JSON hash 일치
- 같은 baseline 재실행 후 업무 행 수 불변과 별도 성공 run 기록
- runtime 행이 있는 같은 baseline 재실행에 대한 `RUNTIME_DATA_PRESENT`
- 다른 fingerprint의 동일 ID 충돌보다 `BASELINE_MISMATCH`를 우선하는지 확인
- 같은 fingerprint의 저장 hash 손상에 대한 `SOURCE_RECORD_CONFLICT`와 전체 rollback
- 빠지거나 내용이 달라진 locator 하위 행을 멱등 성공으로 숨기지 않는지 확인
- 잘못된 enum, 끊어진 참조와 raw HTML 입력 거부
- 실패 시 업무 행 0건과 별도 FAILED import run 기록
- 입력 파일 생성 순서와 무관한 fingerprint 확인
- Observation의 소수점 이하 시각과 import 실행 시각의
  `OffsetDateTime`/`timestamptz`/`Instant` round-trip 일치
- Migration ExtractionAttempt 5건의 `BACKFILLED_FROM_OBSERVATION` 확인
- Importer role의 baseline 조회·삽입 성공과 수정·삭제·truncate·DDL 및 maintenance 감사
  테이블 조회 거부
- Production importer 자격증명 누락 시 runtime 계정 fallback 없이 기동 거부
- 실패 감사 저장 오류가 원래 오류 code와 예외를 보존하는지 확인

### Python private 검증

```bash
python3 -m unittest discover -s tests -v
```

결과:

```text
Ran 51 tests
OK
```

### Public Git 조건 검증

```bash
TRUSTAGENT_PRIVATE_ARTIFACT_ROOT=/private/tmp/trust-agent-no-private-artifacts \
  python3 -m unittest discover -s tests -v
```

결과:

```text
Ran 51 tests
OK (skipped=3)
```

비공개 raw artifact가 필요한 무결성, golden 재추출과 migration 재현 테스트 3개만
의도대로 skip됐고 나머지 48개는 통과했습니다.

## 위험과 제한사항

- 저장소 경로를 직접 읽는 방식은 운영 중 지속 ingestion에 적합하지 않습니다.
- Importer는 runtime Observation을 동기화하거나 기존 DB를 정리하는 도구가 아닙니다.
- Runtime ingestion이 시작되기 전 baseline 변경은 새 DB 또는 새 schema 전체 적재 후
  전환할 수 있습니다. 운영 데이터가 쌓인 뒤에는 별도 보존·이전 ADR이 필요합니다.
- 프로세스 강제 종료나 DB 자체 장애로 FAILED run도 쓰지 못한 공백은 별도 job run
  감사와 reconciliation이 필요합니다.
- 별도 pool과 `trust_agent_importer` 권한 묶음은 구현했지만 실제 운영의 importer LOGIN
  계정 발급, 비밀 관리, network policy와 작업 승인은 Day 4의 운영 배포 범위가 아닙니다.

## 다음 작업과의 연결

Day 4c는 이 DB baseline을 대상으로 공개 상품 관측 상태 조회 endpoint를 구현합니다.
`asOf`와 `evaluatedAt`을 분리하고, 성공 ExtractionAttempt의 `attempted_at` 전에는
evidence를 노출하지 않으며, 최신 Observation의 처리 상태를 freshness와 confirmation
차단 정책에 연결합니다.
