# ADR 006 공개 상품 관측과 상품 조건 버전 분리

## 상태

승인

## 배경

현재 공개 상품 pipeline은 raw HTML snapshot에서 정규화한 모든 fact를 하나의
canonical fact set으로 만들고 그 SHA-256을 product version ID로 사용합니다.
이 구조에는 다음 문제가 있습니다.

- 공개 페이지가 매일 갱신하는 광고 금리 기준일 때문에 상품 조건이 같아도 새
  product version이 생성됩니다.
- 광고 금리 문구는 기준일과 함께 해석해야 하는 시점별 quote이지만 가입 대상,
  한도, 상환 방법과 같은 안정적인 상품 조건과 같은 identity에 포함됩니다.
- 같은 HTML을 다시 관측하면 artifact와 manifest가 중복 제거되면서 재관측 시점이
  남지 않습니다. 따라서 A에서 B로 변경된 뒤 다시 A로 돌아온 사실을 재생할 수
  없습니다.
- 같은 의미의 product version을 다른 snapshot에서 확인해도 version 파일에는 최초
  snapshot과 source locator만 남습니다.
- 수집 실패를 영속 기록하지 않으면 Observation이 없는 날이 실행 누락인지 외부 통신
  실패인지 설명할 수 없습니다.
- 추출 실패를 Observation에 사후 기록하면 append-only 관측 기록을 수정하게 됩니다.
- 판매종료는 예상 가능한 상품 상태 전이지만 현재 parser는 `판매중` 이외의 값을
  모두 실패로 처리합니다.
- 새 snapshot의 추출이 실패해도 이전 `SELLING` terms가 아무 경고 없이 조회되면
  extractor의 fail-closed가 상담 계층에서 fail-open으로 바뀝니다.

공개 snapshot은 법적 효력을 보장하는 증거가 아닙니다. 공식 공개 페이지에서 특정
시점에 관측한 내용을 재현하는 감사 evidence이며, 실제 업무 변경의 기준은 내부
공문입니다. 프로젝트에서는 실제 내부 공문 대신 `SYNTHETIC_INTERNAL` 자료를
사용합니다. 공개 페이지는 교차 검증과 변경 감지에 사용합니다.

## 결정

### 개념 계층

공개 상품 pipeline을 다음 네 개의 개념 계층으로 구분합니다.

1. `Snapshot`: 수집한 raw byte의 content identity
2. `Observation`: 특정 시점에 특정 상품 URL에서 snapshot을 관측한 사건
3. `ProductTermsVersion`: 안정적인 상품 조건의 의미 identity
4. `VersionEvidence`: Observation에서 ProductTermsVersion을 확인한 근거 연결

실제 처리 이력을 append-only로 보존하기 위해 `CollectionAttempt`, `ExtractionAttempt`,
`ObservedRateQuote`, `ChangeDetectionResult` record를 추가합니다.

### Snapshot

- Raw HTML artifact는 기존과 같이 Git 외부 비공개 저장소에 둡니다.
- Artifact object key는 raw byte SHA-256을 사용하는 content-addressed 형식을
  유지합니다.
- 같은 raw byte는 다시 저장하지 않습니다.
- Snapshot record는 `product_key`와 `snapshot_hash` 조합으로 유일하게 등록합니다.
- Snapshot record는 byte size, content type, object key와 canonical source URL을
  보존합니다.
- 기존 manifest의 `collected_at`은 migration 입력에서 최초 Observation의
  `observed_at`으로 사용합니다. 신규 모델에서 관측 시각과 취득 방법의 소유자는
  Observation입니다.

### Observation

- 공식 공개 페이지에서 HTML을 성공적으로 취득하고 검증할 때마다 새 Observation을
  append-only로 기록합니다.
- 동일한 snapshot hash를 열흘 연속 관측하면 Snapshot은 한 건이고 Observation은
  열 건입니다.
- Observation은 `product_key`, canonical source URL, final URL, `observed_at`,
  acquisition method와 note, snapshot hash를 보존합니다.
- `observed_at`은 UTC로 정규화한 유효한 RFC 3339 `Z` date-time이어야 합니다.
  Offset이 있는 입력은 UTC로 변환한 뒤 저장하고 timezone 없는 입력은 거부합니다.
- Observation ID는 `collection_attempt_id`에서 결정하며 snapshot hash나 응답 내용에
  의존하지 않습니다.
- Collection run ID는 외부 Batch job이나 호출자가 주입할 수 있고, 지정하지 않으면
  실행 시작 시 생성합니다. Attempt ID는 run ID, product key와 해당 product 안의
  attempt sequence로 결정합니다.
- 새 HTTP 요청으로 얻은 결과는 내용이 같아도 새 Observation입니다. 이미 기록된
  동일 attempt ID를 replay하면 기존 결과를 반환하고 새 HTTP 요청이나 중복 record를
  만들지 않습니다. 새 HTTP 요청은 새 run ID 또는 attempt sequence를 사용합니다.
- Observation은 추출 성공 여부에 의존하지 않으며 생성 후 수정하지 않습니다.
- 수집 실패는 Snapshot이나 Observation을 생성하지 않지만 CollectionAttempt에
  영속 기록합니다.

### ProductTermsVersion

ProductTermsVersion identity에는 다음 stable term fact만 포함합니다.

- `product_name`
- `applicant_eligibility_text`
- `max_limit_individual_krw`
- `max_limit_corporate_krw`
- `repayment_method_text`
- `sale_status`

ProductTermsVersion ID는 위 fact들의 canonical SHA-256에서 생성합니다. Observation,
수집 시각, source URL, source locator와 광고 금리 quote는 identity에 포함하지
않습니다.

Parser version도 terms 의미 identity나 ProductTermsVersion 본문에 포함하지 않습니다.
같은 terms를 새 parser가 다시 확인할 수 있도록 parser provenance는 VersionEvidence와
ExtractionAttempt에 보존합니다.

`effective_from`과 `effective_to`는 원문 또는 승인된 별도 업무 근거가 제공하지 않으면
`null`로 유지합니다. `observed_at`을 유효일로 대신 사용하지 않습니다.

### 판매 상태

- 원문에서 확인한 알려진 판매 상태는 terms fact로 정규화합니다.
- `판매중`은 `SELLING`으로 정규화합니다.
- `판매종료`는 `DISCONTINUED`로 정규화합니다.
- `SUSPENDED`와 같은 상태는 대응하는 실제 원문 표현과 업무 의미가 확인되기 전에는
  추가하지 않습니다.
- 알려지지 않은 문구는 임의 매핑하지 않고 extraction failure로 기록합니다.

### ObservedRateQuote

- `advertised_rate_text`와 `advertised_rate_reference_date`는
  ProductTermsVersion에서 제거하고 Observation에 속한 quote로 저장합니다.
- 두 값은 같은 quote evidence 단위로 보존합니다.
- 원문에 reference date가 없으면 `null`로 유지하며 `observed_at`으로 대체하지
  않습니다.
- Quote는 snapshot hash, source URL, selector, label, evidence text와 evidence
  hash를 보존합니다.
- 공개 광고 금리는 최종 적용 금리 또는 은행의 내부 금리 결정으로 해석하지 않습니다.

### VersionEvidence

- ProductTermsVersion은 단일 snapshot 참조를 갖지 않습니다.
- VersionEvidence가 Observation과 ProductTermsVersion을 연결합니다.
- VersionEvidence는 각 term fact의 source locator를 보존합니다.
- 한 fact가 가입 대상과 한도처럼 여러 원문 조각을 결합한 composite claim이면 필요한
  locator를 모두 연결합니다.
- 같은 terms version을 여러 번 관측하면 ProductTermsVersion은 재사용하고
  VersionEvidence를 각 Observation에 대해 추가합니다.

### CollectionAttempt

- Catalog의 상품을 수집하려는 각 시도를 append-only CollectionAttempt로 기록합니다.
- CollectionAttempt는 attempt ID, run ID, product key, attempted at, attempt sequence,
  `SUCCEEDED` 또는 `FAILED` 상태를 보존합니다.
- 실패 record는 안정적인 error code와 민감정보를 포함하지 않는 제한된 error
  message를 보존합니다.
- 성공 record는 생성된 Observation ID를 참조합니다.
- 동일 attempt ID replay는 기존 결과를 재사용합니다. 실제 네트워크 요청을 다시
  수행하려면 새 attempt ID를 사용합니다.
- 정상적으로 포착한 HTTP, redirect, content, marker와 상품별 저장 실패는 FAILED로
  기록합니다. 프로세스 강제 종료처럼 최종 attempt record도 남기지 못한 장애는 후속
  Batch job run audit와 reconciliation에서 다룹니다.

### ExtractionAttempt

- Extractor를 실행할 때마다 Observation을 참조하는 append-only
  ExtractionAttempt를 기록합니다.
- ExtractionAttempt는 attempt ID, observation ID, attempted at, attempted at source,
  parser version, `SUCCEEDED` 또는 `FAILED` 상태를 보존합니다.
- `attempted_at_source`는 실행 중 측정한 값이면 `MEASURED`, 과거 migration에서
  Observation 시각을 복사한 값이면 `BACKFILLED_FROM_OBSERVATION`입니다. 두 값을 같은
  신뢰 수준의 실제 처리 시각으로 해석하지 않습니다.
- 실패 record는 안정적인 error code와 민감정보를 포함하지 않는 제한된 error
  message를 보존합니다.
- 성공 record는 생성하거나 재사용한 ProductTermsVersion, VersionEvidence와
  ObservedRateQuote ID를 참조합니다.
- Parser 수정 후 재시도할 때 기존 실패 record를 수정하지 않고 새 attempt를
  추가합니다.

### 데이터 분류와 audit 저장

- CollectionAttempt, ExtractionAttempt와 ChangeDetectionResult는 `DERIVED`로
  분류합니다. Snapshot, Observation, ProductTermsVersion, VersionEvidence와
  ObservedRateQuote는 `PUBLIC_KB`로 분류합니다.
- Baseline과 개발 중 생성한 공개 pipeline audit record는
  `datasets/derived/public-kb/` 아래의 allowlisted 하위 경로에 저장하고 Git에
  커밋합니다. `.gitignore`의 포괄적인 `datasets/derived/` 제외 규칙은 이 경로를
  허용하도록 변경합니다.
- Error record에는 허용된 error code와 정제된 message만 저장하며 raw HTML, 환경
  변수, credential과 stack trace를 커밋하지 않습니다.
- 후속 Spring 운영 환경에서는 같은 contract를 DB의 append-only audit table에
  영속화합니다. Git은 운영 DB를 대신하지 않지만 committed baseline과 CI fixture는
  clone 후에도 검증 가능해야 합니다.

### 변경 판정

추출 성공 후 같은 상품의 관측 시간축에서 직전 성공 결과와 비교한
ChangeDetectionResult를 append-only로 기록합니다. 최초 성공 결과에는
`BASELINE_ESTABLISHED`를 사용합니다.

- Stable term hash가 바뀌면 `PRODUCT_TERMS_CHANGED`
- 직전 관측과 광고 금리 문구가 바뀌면 terms 변경 여부와 독립적으로
  `RATE_QUOTE_CHANGED`
- 광고 금리 문구는 같고 quote reference date만 바뀌면 terms 변경 여부와 독립적으로
  `QUOTE_REFRESHED`
- 위 terms, 금리 문구와 reference date가 모두 같으면 `NO_SEMANTIC_CHANGE`

한 Observation에서 terms와 quote가 함께 바뀔 수 있으므로 변경 분류는 단일 enum이
아니라 중복 없는 classification 목록으로 저장합니다. `EXTRACTION_FAILED`는 상품
변경 분류가 아니라 ExtractionAttempt 결과입니다.

`QUOTE_REFRESHED`와 `NO_SEMANTIC_CHANGE`는 감사 기록에는 남기지만 규정담당자 변경
검토 알림을 만들지 않습니다. 실제 알림 구현은 후속 Batch 범위입니다.

- 변경 순서는 `attempted_at`이 아니라 Observation의 `observed_at`을 기준으로 합니다.
- 저장 date-time은 UTC `Z`로 정규화하지만 정렬과 비교는 문자열이 아니라 파싱한
  instant를 사용합니다. 동일 시각은 Observation ID로 안정적으로 정렬합니다.
- 이미 더 나중 Observation의 판정이 존재하는 상태에서 과거 Observation을 처리하면
  삽입 지점 이후를 다시 계산합니다. 기존 결과를 수정하지 않고 새 결과가
  `supersedes_result_id`로 이전 결과를 대체합니다.

### 조회 freshness와 상담 차단

- ProductTermsVersion은 immutable identity이므로 `last_confirmed_at`을 version 파일에
  저장하거나 evidence가 추가될 때 수정하지 않습니다.
- Core service 조회 projection은 최신 VersionEvidence에서 계산한
  `last_confirmed_at`, `freshness_status`, `blocking_reasons`를 1급 필드로 반환합니다.
- `freshness_status`는 최소 `CONFIRMED`, `PENDING_EXTRACTION`, `STALE`,
  `UNCONFIRMED_AFTER_FAILURE`, `UNAVAILABLE`을 구분합니다.
- 새 Observation이 있지만 아직 ExtractionAttempt가 없으면 `PENDING_EXTRACTION`으로
  표시합니다.
- Freshness 입력의 extraction 상태는 상품 전체에서 가장 최근에 실행된 attempt가
  아니라 **최신 Observation에 속한 최신 ExtractionAttempt 상태**입니다. 최신
  Observation에 attempt가 없으면 이전 Observation의 성공 상태를 전달하지 않고
  `null`로 전달합니다.
- 새 Observation의 최신 ExtractionAttempt가 실패하면 이전 terms가 있더라도 즉시
  `UNCONFIRMED_AFTER_FAILURE`로 표시합니다. 이는 판매 상태를 포함한 새 원문을 현재
  parser가 해석하지 못했다는 의미입니다.
- 수집 실패만 발생한 경우에는 최신 CollectionAttempt 실패를 응답에 노출하고, 마지막
  성공 확인 후 경과 시간이 설정된 `max_confirmation_age`를 넘으면 `STALE`로
  표시합니다.
- 최신 수집 실패 자체는 확인 age 임계 전까지 AI 확정 차단 사유가 아니므로
  `warning_reasons`에 노출합니다. `blocking_reasons`에는 실제로 대표 freshness 상태를
  차단 상태로 만든 원인만 넣습니다.
- ProductTermsVersion과 성공 VersionEvidence가 한 건도 없으면 `UNAVAILABLE`로
  표시합니다. 저장소 접근 오류는 freshness 상태로 숨기지 않고 API 오류로 반환합니다.
- 여러 조건이 동시에 성립하면 대표 `freshness_status`는 다음 우선순위로 선택합니다.
  `UNAVAILABLE`, `UNCONFIRMED_AFTER_FAILURE`, `PENDING_EXTRACTION`, `STALE`,
  `CONFIRMED` 순서이며 성립한 모든 원인은 `blocking_reasons`에 보존합니다.
- `max_confirmation_age`는 version data가 아니라 version이 명시된 운영 정책
  configuration입니다. Core service는 적용한 threshold와 policy version을 응답 및
  audit에 포함하며 production profile에서 값이 없으면 시작을 거부합니다.
- `PENDING_EXTRACTION`, `STALE`, `UNCONFIRMED_AFTER_FAILURE`, `UNAVAILABLE`에서는
  마지막 확인 terms와 수기 checklist를 경고와 함께 조회할 수 있지만 AI 결과 확정
  경로는 차단합니다.
- 차단 책임은 Core service의 application policy와 승인 API에 있습니다. AI service와
  화면만의 검사에 의존하지 않습니다.

### 상품 단위 실패 격리

- Catalog 또는 schema 자체가 잘못됐거나 저장소에 접근할 수 없는 전역 오류는 실행을
  즉시 중단합니다.
- 한 상품의 수집, 추출 또는 상태 매핑 실패는 해당 상품 결과에 기록하고 다른 상품
  처리는 계속합니다.
- 실행 summary는 상품별 성공과 실패, error code를 구조화된 JSON으로 제공합니다.
- 모든 상품이 성공할 때만 exit code `0`을 반환합니다. 일부 또는 전체 실패는
  non-zero를 반환하고 세부 결과는 summary로 구분합니다.
- 추출 실패가 발생해도 이미 생성된 Observation과 기존 ProductTermsVersion은
  삭제하거나 수정하지 않습니다.

## ID와 파일 원칙

- Snapshot artifact와 ProductTermsVersion은 내용 기반 SHA-256 identity를
  사용합니다.
- CollectionAttempt, Observation과 ExtractionAttempt는 사건 identity이므로
  content-addressed 경로를 사용하지 않습니다.
- 사건 ID에는 고유 run 또는 attempt ID를 포함하고 timestamp만 identity로 사용하지
  않습니다.
- Observation ID와 attempt ID에는 snapshot hash나 응답 내용을 포함하지 않습니다.
- 모든 파일은 immutable write를 사용하며 동일 event ID replay만 멱등 처리합니다.
- 경로와 schema는 `PUBLIC_KB`와 `DERIVED` 분류를 위반하지 않아야 합니다.
- 기존 snapshot manifest 5건을 신규 책임으로 바꾸는 migration은 승인된 일회성
  예외로 기존 파일을 재작성합니다. Migration이 끝난 뒤 일반 collector 실행에서는
  snapshot manifest 불변 규칙을 다시 적용합니다.

정확한 ID 문자열 형식과 파일 경로는 contract schema에서 고정하고 같은 schema를
Python pipeline과 이후 Spring service가 함께 사용합니다.

## 기존 데이터 migration

Quote fact를 ProductTermsVersion에서 제거하면 기존 fact set hash를 유지할 수
없습니다.

- 현재 product version 파일 5개는 모두 active dataset에서 제거합니다.
- 세 상품에 대해 새로운 ProductTermsVersion 3개를 생성합니다.
- 기존 파일을 active legacy 디렉터리에 복제하지 않습니다. 과거 파일은 Git history로
  보존합니다.
- 현재 manifest 5건에서 Observation 5건을 생성합니다. 기록되지 않았던 과거
  재관측은 추정해 만들지 않습니다.
- 기존 snapshot manifest 5건은 신규 snapshot schema에 맞게 같은 경로와 파일명으로
  재작성합니다. `collected_at`, acquisition method와 note는 대응하는 Observation과
  CollectionAttempt로 이관하고 이관 mapping을 evidence에 기록합니다.
- 기존 manifest에 final URL이 기록되지 않았으므로 migration Observation의
  `final_url`은 `null`로 유지합니다. Canonical source URL을 final URL로 복사해 과거
  redirect 결과를 추정하지 않습니다. 신규 collector는 검증한 final URL을 기록합니다.
- Migration으로 복원하는 Observation 5건은 확인 가능한 하한선입니다. 과거 collector가
  같은 snapshot 재관측을 기록하지 않았으므로 상품별 Observation 수를 과거 실제
  수집 횟수로 해석하지 않습니다.
- 기존 manifest의 각 성공 수집에 대응하는 CollectionAttempt도 생성합니다. 과거 실패
  attempt는 근거가 없으므로 추정해 만들지 않습니다.
- Migration으로 만든 ExtractionAttempt 5건은 실제 추출 시각을 복원할 근거가 없어
  `attempted_at`에 Observation의 `observed_at`을 복사하고
  `attempted_at_source=BACKFILLED_FROM_OBSERVATION`으로 표시합니다.
- 각 Observation에서 VersionEvidence와 ObservedRateQuote를 생성합니다.
- 각 상품의 관측 시간축에 ChangeDetectionResult를 생성합니다. 현재 baseline은
  `kb-seller-loan`의 `BASELINE_ESTABLISHED`와 나머지 두 상품의 최초
  `BASELINE_ESTABLISHED` 및 후속 `QUOTE_REFRESHED`를 보존해야 합니다.
- 기존 version ID와 신규 terms version ID의 mapping, Observation ID, quote ID와
  evidence ID를 hardening evidence에 기록합니다.
- Migration은 커밋된 manifest와 SHA-256이 일치하는 private artifact 5개를 입력으로
  실행합니다. Artifact가 없거나 hash가 다르면 실패합니다.
- Migration 명령을 다시 실행한 결과가 커밋된 신규 public dataset과 canonical JSON
  기준으로 일치해야 합니다.
- Migration run ID는 임의 생성하지 않고 version이 고정된 migration ID를 주입합니다.
  Observation과 attempt ID를 포함한 모든 산출물이 재실행마다 동일해야 합니다.

기존 `docs/evidence/DAY_03_EVIDENCE.md`는 당시 실행 결과를 나타내므로 덮어쓰지
않습니다. 정정 안내와 새 evidence 링크만 추가하고, 상세 migration 결과는
`docs/evidence/DAY_03_HARDENING_EVIDENCE.md`에 기록합니다.

## Contract 변경

- 기존 `contracts/public-snapshot-manifest.schema.json` 파일명은 유지하고 Snapshot
  책임에 맞게 내용을 개정합니다.
- `contracts/public-product-fact.schema.json`과
  `contracts/public-product-version.schema.json`은 migration 완료 후 active
  contract에서 제거하고 Git history로 보존합니다.
- 다음 schema 파일명을 사용합니다.
  - `contracts/public-product-term-fact.schema.json`
  - `contracts/public-product-terms-version.schema.json`
  - `contracts/public-rate-quote.schema.json`
  - `contracts/public-observation.schema.json`
  - `contracts/public-version-evidence.schema.json`
  - `contracts/public-collection-attempt.schema.json`
  - `contracts/public-extraction-attempt.schema.json`
  - `contracts/public-change-detection-result.schema.json`
- CollectionAttempt, ExtractionAttempt와 ChangeDetectionResult contract는 record의
  `dataset_class`를 `DERIVED`로 강제합니다.
- ProductTermsVersion에는 단일 source manifest, snapshot 또는 locator를 두지 않습니다.
- 모든 저장 date-time schema는 RFC 3339 UTC `Z` pattern과 실제 format checker를
  함께 적용합니다.
- `rfc3339-validator` dependency를 설치하고 잘못된 timestamp 거부 테스트를
  추가합니다.
- `test_git_baseline_commit_exists_and_contains_day_one_baseline`이 확인하는 기존
  `public-snapshot-manifest.schema.json`은 rename하지 않습니다. Product contract
  교체 목록은 migration contract test에서 별도로 검증합니다.

## 테스트와 완료 조건

- 같은 HTML을 두 번 수집하면 Snapshot 한 건과 Observation 두 건이 존재합니다.
- 같은 HTML을 두 번 수집하면 성공 CollectionAttempt와 Observation도 각각 두 건이
  존재합니다.
- 같은 run ID와 attempt sequence replay는 HTTP 요청 없이 기존 attempt와 Observation을
  반환합니다.
- 같은 run ID라도 새 attempt sequence는 새 수집 사건을 만듭니다.
- Attempt sequence는 run 전체가 아니라 product별로 증가합니다.
- A에서 B로 변경된 뒤 A로 돌아오면 Snapshot 두 건과 Observation 세 건이 존재합니다.
- 광고 금리 reference date만 바뀌면 terms version은 재사용되고
  `QUOTE_REFRESHED`가 기록됩니다.
- 광고 금리 문구가 바뀌면 terms version은 재사용되고 `RATE_QUOTE_CHANGED`가
  기록됩니다.
- 한도, 가입 대상, 상환 방법 또는 판매 상태가 바뀌면 새 terms version과
  `PRODUCT_TERMS_CHANGED`가 기록됩니다.
- 판매종료 문구는 `DISCONTINUED`로 저장됩니다.
- 알 수 없는 판매 상태는 실패 attempt를 만들고 기존 terms version을 유지합니다.
- 새 Observation의 extraction 실패 후 조회 projection은
  `UNCONFIRMED_AFTER_FAILURE`이며 AI 확정 경로가 차단됩니다.
- 새 Observation의 extraction이 아직 실행되지 않았으면 `PENDING_EXTRACTION`이며 AI
  확정 경로가 차단됩니다.
- CollectionAttempt 실패는 영속 record로 남고 경과 시간 임계 이후 조회 projection은
  `STALE`이며 AI 확정 경로가 차단됩니다.
- Parser 실패 후 새 parser version으로 재시도하면 실패와 성공 attempt가 모두
  남습니다.
- 한 상품 실패 후에도 나머지 상품의 Observation과 extraction 결과가 생성됩니다.
- `not-a-timestamp`와 timezone 없는 date-time을 schema가 거부합니다.
- Offset date-time 입력은 UTC `Z`로 정규화해 저장하고 변경 판정은 instant 기준으로
  수행합니다.
- 초 단위 UTC와 소수점 이하 초가 있는 UTC가 섞여도 문자열이 아니라 instant로
  정렬되는 회귀 테스트를 둡니다.
- 역순 extraction과 과거 Observation backfill은 superseding 판정으로 시간축을
  재계산합니다.
- Private artifact 5개 전체를 재추출한 결과가 신규 committed dataset과 일치합니다.
- 신규 terms version은 상품당 한 건으로 총 3개이며 일반 불변식 테스트와 정확한 MVP
  baseline inventory 테스트를 모두 통과합니다.
- Public CI 결과와 private artifact validation 결과를 별도로 기록합니다.

## 대안

### 광고 금리 기준일만 hash에서 제외

광고 금리 문구와 기준일은 한 quote의 문맥이며 최초 quote와 locator가 version에
고정되는 문제가 남으므로 선택하지 않습니다.

### 모든 공개 fact 변경을 하나의 product version으로 표현

금리 quote 변경도 탐지할 수 있지만 상품 조건 변경과 시점별 가격 고시 변경을 같은
검토 흐름으로 보내게 됩니다. 별도 quote와 change classification을 사용합니다.

### Observation을 기존 manifest에 누적

기존 immutable record를 수정해야 하고 동시 쓰기와 감사 재생이 어려워지므로
append-only Observation을 분리합니다.

### 정확한 baseline 개수 테스트 제거

일반 불변식만으로는 예상하지 못한 version 증가를 빠르게 발견하기 어렵습니다. 일반
불변식 테스트와 의도한 baseline inventory 테스트를 함께 유지합니다.

## 영향과 제한사항

- 기존 product version ID와 경로를 참조하는 소비자는 신규 ID로 migration해야
  합니다.
- Private artifact가 없으면 raw HTML 기반 migration을 재현할 수 없으므로 ADR-004의
  별도 백업과 접근 권한 관리가 필수입니다.
- Observation과 evidence가 매 수집마다 증가하므로 보존 기간과 운영 저장소 정책은
  Spring Batch 도입 전에 추가로 결정해야 합니다.
- `datasets/derived/public-kb/`의 committed audit record는 공개 가능한 정제 필드만
  포함해야 하며 runtime 전체 audit 보존은 Spring DB와 backup 정책이 담당합니다.
- 공개 페이지의 알려지지 않은 문구와 DOM 변경은 계속 fail-closed 처리합니다.
- 이 결정은 공개 상품 정보의 관측과 교차 검증을 다루며 내부 공문의 업무상 효력을
  대체하지 않습니다.

## 기존 ADR과의 관계

- ADR-004의 비공개 content-addressed raw artifact 정책은 유지합니다.
- 이 ADR이 승인되면 ADR-005의 product version identity와 단일 source locator 결정은
  이 ADR로 대체합니다.
