# 공개 상품 감사 저장 ERD

이 문서는 Flyway V1~V3를 사람이 빠르게 확인하기 위한 관계 요약입니다. 실제 column,
constraint, trigger와 index의 기준은
`apps/core-service/src/main/resources/db/migration`의 SQL입니다.

```mermaid
erDiagram
    PUBLIC_PRODUCT ||--o{ PUBLIC_SNAPSHOT : owns
    PUBLIC_PRODUCT ||--o{ COLLECTION_ATTEMPT : collects
    PUBLIC_PRODUCT ||--o{ PUBLIC_OBSERVATION : observes
    PUBLIC_PRODUCT ||--o{ PRODUCT_TERMS_VERSION : versions
    PUBLIC_PRODUCT ||--o{ OBSERVED_RATE_QUOTE : quotes
    PUBLIC_PRODUCT ||--o{ EXTRACTION_ATTEMPT : extracts
    PUBLIC_PRODUCT ||--o{ CHANGE_DETECTION_RESULT : detects

    COLLECTION_ATTEMPT o|--o| PUBLIC_OBSERVATION : creates
    PUBLIC_SNAPSHOT ||--o{ PUBLIC_OBSERVATION : captured_as
    PUBLIC_OBSERVATION ||--o| VERSION_EVIDENCE : supports
    PUBLIC_OBSERVATION ||--o| OBSERVED_RATE_QUOTE : reports
    PUBLIC_OBSERVATION ||--o{ EXTRACTION_ATTEMPT : processed_by
    PRODUCT_TERMS_VERSION ||--|{ PRODUCT_TERM_FACT : contains
    PRODUCT_TERMS_VERSION ||--o{ VERSION_EVIDENCE : confirmed_by
    VERSION_EVIDENCE ||--|{ FACT_EVIDENCE_LOCATOR : locates
    PRODUCT_TERM_FACT ||--o{ FACT_EVIDENCE_LOCATOR : explained_by
    EXTRACTION_ATTEMPT o|--o| VERSION_EVIDENCE : produces
    EXTRACTION_ATTEMPT o|--o| OBSERVED_RATE_QUOTE : produces
    CHANGE_DETECTION_RESULT ||--|{ CHANGE_DETECTION_CLASSIFICATION : classifies
    CHANGE_DETECTION_RESULT o|--o{ CHANGE_DETECTION_RESULT : supersedes

    PUBLIC_PRODUCT {
        text product_key PK
        text source_url
        text source_record_hash
    }
    PUBLIC_SNAPSHOT {
        text snapshot_hash PK
        text product_key FK
        text snapshot_object_key
    }
    COLLECTION_ATTEMPT {
        text collection_attempt_id PK
        text product_key FK
        timestamptz attempted_at
        text status
        text observation_id FK
    }
    PUBLIC_OBSERVATION {
        text observation_id PK
        text collection_attempt_id FK
        text snapshot_hash FK
        timestamptz observed_at
    }
    PRODUCT_TERMS_VERSION {
        text product_terms_version_id PK
        text product_key FK
        text terms_hash
    }
    PRODUCT_TERM_FACT {
        text product_terms_version_id PK,FK
        text fact_id PK
        text fact_key
    }
    VERSION_EVIDENCE {
        text version_evidence_id PK
        text observation_id FK
        text product_terms_version_id FK
    }
    FACT_EVIDENCE_LOCATOR {
        text version_evidence_id PK,FK
        text fact_id PK,FK
        int locator_order PK
    }
    OBSERVED_RATE_QUOTE {
        text rate_quote_id PK
        text observation_id FK
        date advertised_rate_reference_date
    }
    EXTRACTION_ATTEMPT {
        text extraction_attempt_id PK
        text observation_id FK
        timestamptz attempted_at
        text status
    }
    CHANGE_DETECTION_RESULT {
        text change_detection_result_id PK
        text observation_id FK
        text supersedes_result_id FK
    }
    CHANGE_DETECTION_CLASSIFICATION {
        text change_detection_result_id PK,FK
        int classification_order PK
    }
    BASELINE_IMPORT_RUN {
        text baseline_import_run_id PK
        text baseline_fingerprint
        text status
    }
    MAINTENANCE_CHANGE_AUDIT {
        bigint maintenance_change_audit_id PK
        text table_name
        text operation
        jsonb primary_key
    }
```

`baseline_import_run`은 bootstrap 실행 사건이고 업무 record와 직접 FK로 연결하지
않습니다. `maintenance_change_audit`은 승인된 break-glass 변경을 기록하는 별도 감사
원장이며 보호 대상 업무 테이블의 FK에 의존하지 않습니다.

V3 index는 조회 API의 시간축에 맞춰 다음 경로를 지원합니다.

- 상품별 최신 Observation: `product_key, observed_at, observation_id`
- Observation별 최신 ExtractionAttempt: `observation_id, attempted_at, extraction_attempt_id`
- 상품별 성공 추출: `product_key, attempted_at, extraction_attempt_id` partial index
- 상품별 최신 CollectionAttempt: `product_key, attempted_at, collection_attempt_id`
