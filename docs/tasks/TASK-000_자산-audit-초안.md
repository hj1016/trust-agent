# TASK-000 부속 문서: 7개 영역 자산 audit

- 성격: [TASK-000](TASK-000_초기-수집-자산-기준선-점검.md) "다음 자산 audit 범위"의 실행 문서. 조사, 검증, 판정 근거는 AI가 작성했고, 판정은 사용자가 했다.
- 작성: AI / 결정자: 사용자
- 상태: **판정 반영 완료. 실행은 TASK-001~003 승인 범위에서 진행 중.** 이 문서는 audit 판정을 기록한다. 판정의 실행(보존 커밋, 삭제, Oracle 전환)은 각 Task의 승인 범위에서 한다. 인간 검수와 Explainability Gate는 각 실행 Task에서 기록하며 이 문서로 통과를 선언하지 않는다.
- 요구사항 출처: 사용자 지시(7개 영역 audit, 미커밋 Day 5 작업 포함, 유지나 PR 전제 금지, 가치/재사용/복잡도/검증 기준, 전부 DROP 가능, 삭제는 사본과 목록 확보 후 승인 뒤).
- 검토 대상 revision: 커밋 `35ef9ad`(origin/main과 동일 내용) + 미커밋 작업 트리. 사용자 판정은 이 초안의 미커밋 revision을 대상으로 했다.
- 관련: [CLAUDE.md](../../CLAUDE.md), [개발 규칙](../development/DEVELOPMENT_RULES.md), [ADR-009](../adr/ADR-009-oracle-core-database.md), [PLAN-001](PLAN-001_중도상환수수료-흐름-Task-분할-초안.md), [TASK-001](TASK-001_미커밋-Day-5-자산-보존.md), [TASK-002](TASK-002_일회성-migrate-스크립트-DROP-실행.md), [TASK-003](TASK-003_Oracle-위험-검증-spike.md)

## 1. 판정 기준과 표기

| 기준 | 질문 |
|---|---|
| 가치 | 최신 목표(Oracle Core, Elasticsearch, Core Tool API)와 우선 흐름 "중도상환수수료 공문 변경 → 검수/승인 → 행원 업무 제공"에 직접 기여하는가 |
| 재사용 | 기술 전환 뒤에도 그대로 쓰이는가, 명세로라도 재사용되는가 |
| 복잡도 | 실제 유지 비용(CI 시간, 의존성 갱신, 설명 비용, 비활성 상태 관리)과 전환 비용이 얼마인가 |
| 검증 상태 | 2절 재검증에서 통과했는가, 어떤 환경 전제인가 |

표기 변형:

- **KEEP(동결):** 유지하되 확장하지 않는다. Oracle 이식은 후순위의 별도 판단으로 남기며 자동 확정하지 않는다. 동결 자산의 실제 유지 비용은 Python CI job 실행, 의존성(beautifulsoup4, certifi, jsonschema) 보안 갱신, Oracle 전환 기간 비활성 상태의 계약 유지와 설명이다. 0이 아니다.
- **MODIFY(규칙 유지, 수단 재작성):** 지키는 업무 규칙은 유지하고 PostgreSQL 전용 구현을 Oracle로 다시 쓴다. 실행은 후속 Task 범위다.
- "SQL 없음"으로 표시한 클래스는 SQL 문장을 포함하지 않는다는 사실이다. Oracle의 빈 문자열과 NULL 동일 취급, `DATE`의 시각 포함, `BOOLEAN` 매핑, `OffsetDateTime` 매핑 같은 의미 차이가 값 처리에 영향을 줄 수 있으므로 "수정 불필요"를 뜻하지 않는다. 통합 테스트로 확인한다.

## 2. 재검증 결과

검증 대상 revision: 커밋 `35ef9ad` + 미커밋 작업 트리. 환경: 로컬 macOS(arm64), Python 3.11.8, Java 21(ms-21.0.11), Docker 실행 중(linux/arm64).

| 대상 | 명령 | 결과 | 전제 |
|---|---|---|---|
| Python 계약/단위 | `python3 -m unittest discover -s tests` | 58개 통과, 실패 0, skip 0 | 비공개 artifact 5개 있음 |
| Python (Public Git 조건) | 같은 명령, `TRUSTAGENT_PRIVATE_ARTIFACT_ROOT`를 빈 경로로 | 58개 중 3개 skip, 55개 통과 | CI와 같은 조건 |
| Java Core | `./gradlew clean test bootJar --offline --no-daemon` | 82개 통과, 실패 0, skip 0, executable jar 생성 | **PostgreSQL 18.6 Testcontainers.** Oracle 기준 검증 아님 |

Java 82개 중 59개(8개 클래스)는 PostgreSQL 컨테이너에 묶여 있고 23개(11개 클래스)는 SQL 없는 단위 테스트다. Python 58개는 DB를 쓰지 않는다.

복구용 사본: 미커밋 작업 전체를 `/Users/faker/Dev/trust-agent-backups/2026-10-05-uncommitted-day5/`에 보존했다(대상 목록 `target-list.txt` 25항목, 추적 파일 diff `tracked-changes.patch`, 미추적 파일 17개 `untracked-files.tar.gz`, 기준 커밋 `base-commit.txt`). 삭제는 하지 않았다.

## 3. 결론

1. **우선 흐름의 핵심 자산은 이미 있고 SQL을 포함하지 않는다.** 합성 중도상환수수료 공문 v1/v2, `structured_change` 계약, 적용 공문 선택 로직(`InternalPolicyApplicableService`), 시간 정책, checklist 사용 정책, canonical hash가 그것이다. 전부 KEEP으로 판정됐고, 미커밋 상태라 TASK-001로 보존한다.
2. **PostgreSQL 전용 자산은 MODIFY(규칙 유지, 수단 재작성)다.** Flyway V1~V5, Java 8개 클래스의 SQL 방언, Testcontainers PostgreSQL, 통합 테스트 8개 클래스. 업무 규칙은 ADR-009 2.1절에 R-01~R-18로 분리했다. 실행은 PLAN-001의 TASK-003, TASK-004 범위다.
3. **공개 상품 KB 파이프라인은 우선 흐름의 critical path에 없다.** 중도상환수수료 공문 v1(rule 2개), v2(rule 3개)의 `public_cross_check`는 모두 null이다(`datasets/synthetic/internal/notices/prepayment-fee-*.json`). KEEP(동결)로 판정됐다. 단, 보조 사례인 셀러론 공문은 `internal_notice_reference`가 `public_product`와 `public_snapshot`을 FK로 참조하므로(V4 56~57행) 공개 상품 테이블 없이는 적재할 수 없다. 이 의존은 5절에 기록했다.
4. **DROP 판정은 하나다.** 일회성 `migrate_public_kb_pipeline_v2.py`와 그 전용 테스트 1개. 삭제 범위에는 다른 KEEP 테스트가 쓰는 상수 이동이 포함된다(TASK-002).
5. **NEW가 필요한 영역:** Oracle 환경, 검색, Core Tool API, 대표 E2E. Redis는 구체적 사용처가 확인될 때까지 보류.
6. **미커밋 Day 5 작업은 처리안 1을 조건부 채택했다.** 하나의 Task(TASK-001)로 보존한다.

## 4. 영역별 판정

"인간 판정" 열은 사용자의 판정이다. 사용자가 명시하지 않은 행은 "판단 대기"다.

### 4.1 데이터 (datasets, contracts, 비공개 artifact)

| 자산 | 도입 구분 | AI 제안 | 근거 | 인간 판정 |
|---|---|---|---|---|
| `contracts/synthetic-internal-notice.schema.json` (미커밋 `structured_change` 추가 포함) | Pre-SDLC | KEEP (미커밋 변경 포함) | 우선 흐름의 원천 계약. `structured_change`(change_type, field_key, before/after, unit, effective_on, 대상 상품, 조건, 예외)가 대표 시나리오를 구조화. 소수는 문자열 십진수로 저장해 canonical hash 원칙 유지. Python 계약 테스트 통과 | **KEEP 채택** |
| `contracts/synthetic-internal-notice-receipt.schema.json`, `synthetic-internal-policy-extraction.schema.json` | Pre-SDLC | KEEP | `receivedAt`과 `businessDate` 분리, extraction attempt append-only의 계약 | **KEEP 채택** |
| `contracts/fixtures/internal-checklist-availability-policy-cases.json` | Pre-SDLC | KEEP | checklist 사용 허용 판정의 정답표 11개. Java 정책 테스트가 전체 case 검증 | **KEEP 채택** |
| `contracts/fixtures/canonical-json-hash-cases.json` | Pre-SDLC | KEEP | Python과 Java가 같은 fixture로 hash 일치 검증 | **KEEP 채택** |
| prepayment-fee notice v1/v2 + receipts 2개 + extraction attempts 2개 (**미추적 6개**) | Pre-SDLC (미커밋) | KEEP | 유일한 대표 시나리오 데이터. v1 1.2퍼센트(2026-09-15 시행) → v2 0.8퍼센트(2026-10-01 시행, v1 대체), 조건 2개, 예외 1개, 고객 계약일 확인 항목 추가 | **KEEP 채택** |
| seller-loan-checklist v1/v2 (미커밋 diff는 `structured_change: null` 추가만) + receipts/extraction 4개 | Pre-SDLC | KEEP (보조 사례) | 내부 공문과 공개 KB를 잇는 유일한 `public_cross_check` 사례(법인 한도 20억원). ADR-008의 "20억을 2억으로 만든 proposal은 FAIL" 경로 검증에 필요. **의존:** `internal_notice_reference`가 `public_product`, `public_snapshot` FK를 가져 공개 상품 테이블 없이는 적재 불가. 전환 1단계 처리 방식은 5절 쟁점 2 | 판단 대기 (5절 쟁점 2와 함께) |
| `contracts/public-*.schema.json` 10개 | Pre-SDLC | KEEP(동결) | 공개 상품 4계층과 이력 계약. Python 테스트 통과. critical path 밖 | **KEEP(동결) 채택.** 신규 확장 없음. Oracle 이식은 후순위 별도 판단 |
| `datasets/public/kb/**` 24파일, `datasets/derived/public-kb/**` 15파일 | Pre-SDLC | KEEP(동결) | 상품 3종, 관측 5건, 약관 version 3건. 재생성에 비공개 원문 필요 | **KEEP(동결) 채택** |
| `.private-artifacts/public-kb/sha256/*.html` 5개 (git 밖) | Pre-SDLC | KEEP | 재취득 불가. 삭제 시 private golden 테스트 3개가 영구 skip. 백업 위치는 사용자가 확인 | **KEEP(동결) 채택** |
| `contracts/fixtures/public-product-confirmation-policy-cases.json` | Pre-SDLC | KEEP(동결) | 공개 근거 freshness 정답표 | **KEEP(동결) 채택** |
| `contracts/synthetic-work-*.schema.json` 2개, `datasets/synthetic/work/**` 2파일 | Pre-SDLC | KEEP (후속 사용) | 현재 소비처는 계약 테스트만. MVP 7단계 상담 준비안에서 필요 | **KEEP 채택** (후속 사용) |
| `datasets/synthetic/README.md`, `.gitignore`의 datasets 규칙 | Pre-SDLC | KEEP | 합성 고지와 비공개 경로 차단. CI 금지 파일 검사와 짝 | **KEEP 채택.** 현재 상태 설명은 승인된 Task에서 갱신 |

### 4.2 수집 스크립트 (`scripts/`)

| 자산 | AI 제안 | 근거 | 인간 판정 |
|---|---|---|---|
| `collect_public_kb_snapshots.py` (738 LOC) | KEEP(동결) | kbstar.com 전용 수집기. 파일 시스템만 사용. 11개 테스트 통과. 유지 비용: 의존성 갱신, 대상 사이트 구조 변경 시 깨짐(동결 중엔 미대응) | **KEEP(동결) 채택** |
| `extract_public_kb_product_facts.py` (837 LOC) | KEEP(동결) | 약관 fact 추출과 변경 감지. 26개 테스트 | **KEEP(동결) 채택** |
| `migrate_public_kb_pipeline_v2.py` (272 LOC) | DROP | Day 3 일회성 마이그레이션 도구. 입력 경로 `datasets/public/kb/product-versions`는 이미 없음. 전용 테스트 `test_private_migration_is_deterministic_with_fixed_run_ids` 1개는 CI에서 항상 skip. **참조 조사 결과:** 추적 파일 중 참조는 `tests/contract/test_public_product_versions.py` 12행 import와 227행 `migration.EXTRACTION_RUN_ID`(KEEP 테스트 `test_private_artifacts_reextract_to_committed_golden_files`가 사용), 295행(DROP 대상 테스트 안). TASK-000 69행의 목록 언급은 역사 기록으로 유지 | **DROP 채택.** 삭제 실행은 TASK-002에서 사용처, 문서 참조, 사본, 목록 확인 후 별도 승인 |
| `public_product_freshness.py` (128 LOC) | KEEP(동결) | Java 정책과 같은 fixture를 쓰는 Python 참조 구현. 두 언어 중복이지만 fixture 일치 검증 수단 | **KEEP(동결) 채택** |
| `requirements.txt` 4개 고정 | KEEP | hash 고정 없음은 위험 항목으로 기록 | **KEEP 채택.** 의존성 보안 개선은 별도 범위 |

### 4.3 기존 코드 (`apps/`)

Java 4,547 LOC, 43개 클래스. SQL 없음 35개(약 2,380 LOC), SQL 포함 8개(약 2,170 LOC).

| 자산 | AI 제안 | 근거 | 인간 판정 |
|---|---|---|---|
| 정책: `InternalChecklistUsePolicy`, `BusinessTimePolicy`, `PublicEvidenceConfirmationPolicy`, `CanonicalJsonHasher`, `AppendOnlyBootstrapChecks` | KEEP | 우선 흐름의 판단 규칙. SQL 없음. 단위 테스트 통과 | **KEEP 채택** (시간, 선택, 차단 정책과 canonical hash) |
| `internalpolicy/query/` (미추적) 중 `Service`(285), `Controller`, `State`, `Exception`, `ExceptionHandler` | KEEP (미커밋) | 적용 공문 선택(4조건, supersedes chain, AMBIGUOUS fail-closed, 이전 checklist fallback 금지)이 서비스 계층에 있음. SQL 없음 | **KEEP 채택** (처리안 1 범위) |
| `internalpolicy/query/InternalPolicyApplicableRepository` (207, 미추적) | MODIFY | `select exists`, `join lateral ... on true`, `limit 1`, `::text`, boolean 컬럼. 조회 의도 유지, 방언 교체. 교체 후 `OffsetDateTime`과 boolean 매핑의 의미 차이는 통합 테스트로 확인 | **MODIFY 채택**, 실행은 후속 Task |
| `internalpolicy/bootstrap/SyntheticInternalImporter` (692, 미커밋 diff 포함) + `SyntheticInternalImportConfiguration` | MODIFY | `pg_advisory_xact_lock`, `cast(:x as jsonb)`, `on conflict do nothing`, `::text`, 컬럼 미지정 INSERT, `SET statement_timeout`. 미커밋 diff(structured_change 저장/재검증)는 보존 | **MODIFY 채택**, 실행은 후속 Task. 미커밋 diff는 처리안 1로 보존 |
| `config/RuntimeDatasourceConfiguration`, `health/ReadinessDatabaseClient` | MODIFY | `SET statement_timeout` → JDBC query timeout, `select 1` → `from dual`(또는 생략 가능 버전), `success = true` → `1`, `limit` → `FETCH FIRST` | **MODIFY 채택** (환경 설정) |
| `config/*` 나머지, `health/*HealthIndicator`, `web/RequestTraceFilter`, 예외 핸들러 2개, DTO record | KEEP | SQL 없음. readiness와 liveness 분리, trace ID, Problem Detail 계약 | **KEEP 채택.** Oracle 의미 차이로 수정이 필요하면 해당 Task에 근거 기록 |
| `publicproduct.baseline` (`BaselineImporter` 700, `BaselineDatasetLoader` 723 등) | KEEP(동결) / 이식 후순위 | `BaselineImporter`는 advisory lock, jsonb, `is not distinct from` 교체 필요. 전환 기간 비활성 계약 유지 비용 발생 | **KEEP(동결) 채택.** 이식은 후순위 별도 판단 |
| `publicproduct.query` (`Repository` 251 등) | KEEP(동결) / 이식 후순위 | `/api/v1/public-products/{productKey}/observed-state`. 전환 기간 비활성 계약(ADR-009 3절) | **KEEP(동결) 채택** |
| `apps/core-service/README.md` (미커밋 diff) | MODIFY | 구현 상태 반영. "PostgreSQL 기준 구현, Oracle 전환 미완" 표기 필요 | **처리안 1 범위에 포함 채택** |
| `apps/ai-service/README.md`, `apps/frontend/README.md` | KEEP (placeholder) | 코드 없음 | **KEEP 채택.** 구현 완료로 표현하지 않음 |
| `build.gradle` 의존성, `gradle.lockfile`, `gradle/verification-metadata.xml` | MODIFY | Oracle driver, Flyway Oracle, Testcontainers Oracle로 교체 후 재생성. 의존성 잠금 원칙 유지 | **MODIFY 채택** (환경 설정) |

### 4.4 DB 및 검색 설정

| 자산 | AI 제안 | 근거 | 인간 판정 |
|---|---|---|---|
| Flyway `V1` (감사/권한/공개 상품 14 테이블) | MODIFY(규칙 유지, 수단 재작성) | 규칙 R-01~R-08. 수단은 PostgreSQL 전용(ADR-009 2.2절). 공개 상품 테이블 DDL은 후순위 | **MODIFY 채택**, 실행은 후속 Task |
| `V2` (importer role), `V3` (인덱스 4개) | MODIFY (후순위) | 공개 상품 이식과 함께 | **MODIFY 채택** |
| `V4` (내부 공문 11 테이블, btree_gist EXCLUDE, partial unique) | MODIFY, 우선 | 규칙 R-09~R-14. Oracle에 exclusion constraint가 없어 가장 큰 설계 위험 | **MODIFY 채택** |
| `V5` (`structured_change` jsonb, 미추적) | MODIFY | 규칙 R-16. SQL NULL과 JSON null 구분 재설계 | **MODIFY 채택**, V5 자체는 처리안 1로 보존 |
| `application.yml` (미커밋 4→5), `application-prod.yml` | MODIFY | 기본 URL, `statement_timeout`. 세 벌 자격증명 구조 유지 | **MODIFY 채택** |
| 검색 구성 | NEW | 저장소에 검색 구성 없음. CLAUDE.md에 방향은 있으나 상세 결정 ADR 없음 | **NEW 인정** |
| Redis 구성 | NEW (보류) | 우선 흐름에 필수 지점 미확인 | **보류 채택** |

### 4.5 Docker 및 환경

| 자산 | AI 제안 | 근거 | 인간 판정 |
|---|---|---|---|
| `infra/docker/README.md` ("구성 예정") | NEW | compose, Dockerfile, 고정 이미지 없음. 로컬 DB는 수동 PostgreSQL 전제 | **NEW 인정** (Oracle 환경) |
| `.env.example` | NEW | `.gitignore`가 허용하지만 파일 없음 | **NEW 인정** |
| Testcontainers PostgreSQL digest 고정 | MODIFY | Oracle 이미지(digest 고정). 로컬 arm64와 CI x86_64 양쪽 지원 이미지 필요 | **MODIFY 채택** |
| Gradle wrapper sha 고정, `verification-metadata.xml` | KEEP | 공급망 보호 원칙 | **KEEP 채택** |

### 4.6 테스트

| 자산 | AI 제안 | 근거 | 인간 판정 |
|---|---|---|---|
| Python 58개 | KEEP | DB 무사용. 단 `test_private_migration_is_deterministic_with_fixed_run_ids` 1개는 DROP | **KEEP 채택**, 1개 DROP 채택 |
| `tests/contract/test_day_05_contracts.py` (미커밋) | KEEP | 우선 흐름 계약 테스트 | **KEEP 채택** (처리안 1) |
| Java 단위 11개 클래스 23개 | KEEP | SQL 없음. 2개 클래스의 PostgreSQL 문자열 단언만 수정 | **KEEP 채택.** Oracle 의미 차이로 수정이 필요하면 해당 Task에 근거 기록 |
| `SyntheticInternalSchemaIntegrationTest` (7), `PublicProductSchemaIntegrationTest` (9) | 불변식 명세 재사용, Oracle 검증 신규 | PostgreSQL catalog와 SQLSTATE를 검증. 보장하는 불변식은 ADR-009 R-ID로 재사용 | **채택.** 대체 검증 통과 전 기존 검증 근거를 임의 제거하지 않음 |
| `SyntheticInternalImporterIntegrationTest` (8), `InternalPolicyApplicableIntegrationTest` (8, 미추적) | MODIFY | 업무 단언 유지. fixture SQL 교체 | **MODIFY 채택** |
| `CoreApplicationIntegrationTest` (7), `BaselineImportCommandIntegrationTest` (1) | MODIFY | 컨테이너 교체. statement timeout 검증 방식 변경 | **MODIFY 채택** |
| `BaselineImporterIntegrationTest` (13), `PublicProductObservedStateIntegrationTest` (6) | KEEP(동결) / 후순위 MODIFY | 공개 상품 이식과 함께. 전환 1단계에서는 비활성 계약 테스트로 전환(PLAN-001 3절) | **KEEP(동결) 채택** |
| `AppendOnlyBootstrapChecksTest` (3, 미추적) | KEEP | 순수 단위 | **KEEP 채택** (처리안 1) |
| `tests/e2e/` (README만) | NEW | 대표 E2E 없음 | **NEW 인정** |
| 메서드명 `...VersionFour` 잔존 | MODIFY (사소) | 기대값 5, 이름 4 | **MODIFY 채택.** 관련 테스트 변경 Task에서 정리 |

### 4.7 CI

| 자산 | AI 제안 | 근거 | 인간 판정 |
|---|---|---|---|
| `python-contracts` job | KEEP | 3개 skip은 의도된 동작 | **KEEP 채택** |
| `gradle-tests` job | MODIFY | Oracle 컨테이너 pull과 기동 시간 반영. verification metadata 갱신 | **MODIFY 채택** (환경 설정) |
| 금지 파일 검사 | KEEP | `.env.example` 예외 포함 | **KEEP 채택** |
| CI 공백: e2e, lint, pip hash, 원격 실행 evidence 기록 | NEW (후속) | evidence는 모두 로컬 실행 결과 | E2E NEW 인정, 나머지 판단 대기 |

## 5. 미커밋 Day 5 작업 처리 (처리안 1 조건부 채택)

대상은 복구용 사본의 `target-list.txt` 25항목이다.

| 묶음 | 파일 | 성격 |
|---|---|---|
| A. 우선 흐름 데이터와 계약 | prepayment-fee 6개, notice schema diff, seller-loan `structured_change: null` diff, `test_day_05_contracts.py` diff | SQL 없음, 대표 시나리오 |
| B. 적용 공문 조회 (SQL 없는 부분) | `internalpolicy/query/` 중 Service, Controller, State, Exception, Handler, `AppendOnlyBootstrapChecksTest` | 서비스 로직 |
| C. PostgreSQL 결합 부분 | `InternalPolicyApplicableRepository`, `SyntheticInternalImporter` diff, `V5` SQL, `application.yml` diff, 통합 테스트 4개 diff, `InternalPolicyApplicableIntegrationTest` | Oracle에서 재작성 대상 |
| D. 문서 | `DAY_05B_EVIDENCE.md`, `FINAL_PROPOSAL_ALIGNMENT_EVIDENCE.md`, README 3종, AGENTS.md diff | evidence와 현황 설명 |

**채택된 처리안 1:** A~D를 "검증 가능한 Pre-SDLC 기준선 보존"이라는 하나의 업무 결과로 [TASK-001](TASK-001_미커밋-Day-5-자산-보존.md)에서 커밋한다. 구현에 필요한 README, 계약, 테스트, evidence를 함께 포함하는 것은 정상 PR 범위이며 PR #9와 같은 예외를 새로 기록하지 않는다. 조건은 TASK-001의 Acceptance Criteria에 있다(evidence의 PostgreSQL 기준 표기, 82개 테스트 통과 재확인, 승인 범위 밖 변경 없음).

기각된 처리안: 2(C만 제외)는 B가 Repository 없이 컴파일되지 않아 분리 비용과 검증 공백이 생기고, 3(전부 제거)은 대표 시나리오 데이터와 조회 로직을 사본에서 다시 꺼내야 하며 Day 5b 검증 기록이 history에 남지 않는다.

### 쟁점 2: 전환 1단계에서 셀러론 공문의 공개 상품 FK 의존 (판단 대기)

사실: `internal_notice_reference.product_key`와 `snapshot_hash`가 `public_product`, `public_snapshot`을 참조한다(V4). 셀러론 v1/v2는 reference 행을 가지고, 중도상환수수료 v1/v2는 없다. 현재 synthetic importer는 공문 4개를 한 baseline으로 적재하고 fingerprint를 계산한다.

선택지:

- **S1-a:** 전환 1단계에 `public_product`(3행)와 `public_snapshot`(5행) 두 테이블만 함께 이식하고 최소 적재 경로를 둔다. 공개 baseline importer 전체를 이식하는 것은 아니다. 비용: 테이블 2개 DDL과 적재 경로, 공개 데이터 일부가 1단계에 들어옴.
- **S1-b (AI 추천):** 전환 1단계의 synthetic baseline을 `SIN-PREPAYMENT-FEE` family로 한정한다. 셀러론 공문은 저장소에 유지하되 공개 상품 이식(TASK-010) 뒤 적재한다. 비용: importer 테스트의 "공문 4개" 단언이 1단계에서 "2개"로 바뀌고 TASK-010에서 복원된다. 변경은 Task와 evidence에 명시한다. 무결성 규칙은 그대로다.
- **S1-c:** 1단계에서 reference의 FK를 형식 CHECK로 완화하고 TASK-010에서 FK를 복원한다. 비용: ADR-008의 "공개 근거 참조 무결성"이 일시적으로 약해진다. 추천하지 않는다.

- 결정자: 사용자 / 제안 ID: 쟁점 2 / 판단: **S1-b 채택** / 이유: 무결성 규칙을 유지하면서 우선 흐름에 집중 / 승인 범위: Oracle 전환 1단계는 `SIN-PREPAYMENT-FEE` family만 적재. 셀러론 자료는 저장소에 유지. 공개 상품 Oracle 이식은 후속 필요성에 따라 별도 판단. S1-c(FK 완화)는 채택하지 않음. importer 테스트의 공문 4개 → 2개 변경은 승인된 적재 범위 변경으로 기록 / 검토 대상: 이 문서 미커밋 revision

## 6. 공개 상품 파이프라인 묶음

4절에서 KEEP(동결)로 표시한 자산은 하나의 묶음이다. 사용자 판정: **KEEP(동결) 채택.** 신규 확장은 하지 않고 Oracle 이식은 후순위의 별도 판단으로 남긴다. 후속 이식을 자동 확정하지 않는다. "전부 DROP" 대안은 기각됐다.

전환 기간의 처리는 ADR-009 3절의 비활성 계약을 따른다(공개 endpoint와 importer 명시적 비활성, 내부 공문 기능의 기동과 readiness에 영향 없음).

## 7. AI 제안 및 인간 판단 기록

결정자는 사용자, 검토 대상은 이 문서의 미커밋 초안 revision이다. 사용자의 실행 지시와 결과물 검수 통과는 구분한다. 아래 판단은 "판정 채택"이며 결과물 검수 통과가 아니다.

| 제안 ID | 내용 | 판단 | 이유 | 승인 범위 |
|---|---|---|---|---|
| 제안 1 | 공개 상품 파이프라인 KEEP(동결), Oracle 이식 후순위 | **채택** | 우선 흐름 critical path 밖, 검증된 자산 | 동결. 후속 이식은 자동 확정 아님, 별도 판단 |
| 제안 2 | 미커밋 Day 5 작업 처리안 1 | **조건부 채택** | 대표 시나리오, 계약, 조회 로직, 테스트를 검증 가능한 기준선으로 보존 | 하나의 Task(TASK-001)로 정리. 구현과 관련 문서 동반은 정상 범위, 예외 기록 불필요 |
| 제안 3 | `migrate_public_kb_pipeline_v2.py`와 전용 테스트 1개 DROP | **채택 (판정)** | 목적을 다한 일회성 도구 | DROP 판정만. 삭제 실행은 TASK-002에서 사용처, 문서 참조, 사본, 목록 확인 후 별도 승인 |
| 제안 4 | PostgreSQL 메커니즘 테스트 2개 클래스는 불변식 명세만 재사용, Oracle 검증 코드 신규 작성 | **채택** | 메커니즘이 다르므로 구현 재사용 불가 | 대체 검증 통과 전 기존 검증 근거를 임의로 제거하지 않음 |
| 추가 판정 | 대표 합성 데이터, 계약, 시간/선택/차단 정책, canonical hash, 정답표 KEEP | **채택** | 우선 흐름 핵심 | — |
| 추가 판정 | PostgreSQL SQL, migration, 통합 테스트, 환경 설정 MODIFY | **채택** | 규칙 유지, 수단 재작성 | 실행은 후속 Task 범위 |
| 추가 판정 | Oracle 환경, 검색, Core Tool API, E2E NEW | **인정** | 저장소에 없음 | 각 Task에서 범위와 AC 확정 |
| 추가 판정 | Redis NEW | **보류** | 구체적 사용처 미확인 | 사용처 확인 시 재판단 |
| 수정 지시 | "유지 비용 0", "DB 무관이므로 수정 불필요", "방언 치환이라 위험 적음" 단정 | **수정** | 실제 유지 비용과 의미 차이 검증 반영 | 1절과 4절 표기 수정 완료 |
| 추가 판정 | 남은 "판단 대기" 행 10건 | **채택** | 4절 각 행에 기록(synthetic/work KEEP, .gitignore/README KEEP, requirements KEEP, SQL 없는 클래스와 단위 테스트 KEEP, placeholder KEEP, wrapper KEEP, `VersionFour` MODIFY, CI python job과 금지 파일 검사 KEEP) | 수정이 필요하면 해당 Task에 근거 기록 |
| 쟁점 2 | 전환 1단계 셀러론 처리 | **S1-b 채택** | 5절 참조 | FK 완화 금지 |
| 실행 범위 | 문서 정리 PR, TASK-001, TASK-002, TASK-003을 각각 분리해 검증, secret 점검, self-review 후 commit, push, PR 생성 | **승인** | 병합은 사용자. Squash and merge. main 직접 변경, force push, 이력 재작성, 미커밋 작업 임의 삭제 금지 | 전체 Task 구현이나 인간 검수 통과의 일괄 승인은 아님 |

## 8. AI self-review

- 검사 범위: 저장소 전체 파일 인벤토리, Git 상태와 diff, ADR 8건과 evidence 11건, Python 58개와 Java 82개 재실행, migrate 스크립트 참조 전수 조사, 합성 공문의 공개 참조 전수 조사.
- 발견 사항: (1) `application.yml`과 테스트 4개가 미추적 V5를 전제로 수정되어 V5 없이는 main이 깨진다. (2) `.env.example`이 없다. (3) evidence 문서에 CI 원격 실행 결과가 없다. (4) `PublicProductSchemaIntegrationTest` 메서드명이 기대값과 어긋난다. (5) V1 event trigger는 superuser 권한 전제이나 README에 Flyway 실행 계정 요구사항이 없다. (6) KEEP 테스트가 DROP 대상 스크립트의 상수를 쓴다. (7) 셀러론 공문이 공개 상품 테이블에 FK로 의존한다.
- 미해결 위험: Oracle 후보 수단은 일반 지식 기반이며 저장소에서 검증되지 않았다. TASK-003에서 확인한다.

## 9. 인간 검수와 다음 단계

- 이 문서의 인간 검수 / Explainability Gate: **미기록.** 판정 채택은 검수 통과가 아니다.
- 남은 판단: 없음(4절 전 행과 쟁점 2 판정 완료).
- 실행: TASK-001 보존(승인), TASK-002 삭제 실행(승인, TASK-001 병합 뒤), TASK-003 spike(승인). TASK-004 착수는 TASK-003 결과 반영 후 별도 승인.
