# TASK-000 부속 문서: 7개 영역 자산 audit

- 성격: [TASK-000](TASK-000_초기-수집-자산-기준선-점검.md) "다음 자산 audit 범위"의 실행 문서. 조사, 검증, 판정 근거는 AI가 작성했고, 판정은 사용자가 했다.
- 작성: AI / 결정자: 사용자
- 상태: **판정 반영 완료.** Core 업무 DB PostgreSQL 유지 결정([ADR-009](../adr/ADR-009-oracle-core-database.md))에 따라 Oracle 전환을 전제했던 판정을 재평가했다. 실행은 TASK-001, TASK-002 승인 범위에서 진행 중이다. 인간 검수와 Explainability Gate는 각 실행 Task에서 기록하며 이 문서로 통과를 선언하지 않는다.
- 요구사항 출처: 사용자 지시(7개 영역 audit, 미커밋 합성 공문 조회 작업 포함, 유지나 PR 전제 금지, 가치/재사용/복잡도/검증 기준, 전부 DROP 가능, 삭제는 사본과 목록 확보 후 승인 뒤). 이후 지시: PostgreSQL 결합만을 이유로 MODIFY했던 항목 재평가, 다른 이유의 수정은 유지.
- 검토 대상 revision: 커밋 `35ef9ad`(origin/main과 동일 내용) + 미커밋 작업 트리(현재 PR #11). 판정 기록은 PR #10 브랜치.
- 관련: [CLAUDE.md](../../CLAUDE.md), [개발 규칙](../development/DEVELOPMENT_RULES.md), [PLAN-001](PLAN-001_중도상환수수료-흐름-Task-분할-초안.md), [TASK-001](TASK-001_합성-공문-조회-자산-보존.md), [TASK-002](TASK-002_일회성-migrate-스크립트-DROP-실행.md), [TASK-003](TASK-003_Oracle-위험-검증-spike.md)

## 1. 판정 기준과 표기

| 기준 | 질문 |
|---|---|
| 가치 | 최신 목표(PostgreSQL Core, Elasticsearch, Core Tool API)와 우선 흐름 "중도상환수수료 공문 변경 → 검수/승인 → 행원 업무 제공"에 직접 기여하는가 |
| 재사용 | 후속 Task에서 그대로 쓰이는가, 명세로라도 재사용되는가 |
| 복잡도 | 실제 유지 비용(CI 시간, 의존성 갱신, 설명 비용)과 변경 비용이 얼마인가 |
| 검증 상태 | 2절 재검증에서 통과했는가, 어떤 환경 전제인가 |

- **KEEP(동결):** 유지하되 확장하지 않는다. 동결 자산의 유지 비용은 Python CI job 실행, 의존성(beautifulsoup4, certifi, jsonschema) 보안 갱신, 설명 비용이다. 0이 아니다.
- **재평가 표기:** "MODIFY → KEEP(재평가)"는 PostgreSQL 결합만을 이유로 MODIFY였던 항목이 PostgreSQL 유지 결정으로 KEEP이 된 것이다. DB와 무관한 이유의 MODIFY는 유지한다.
- "SQL 없음"은 SQL 문장을 포함하지 않는다는 사실이며 "수정 불필요"를 뜻하지 않는다.

## 2. 재검증 결과

검증 대상 revision: 커밋 `35ef9ad` + 미커밋 작업 트리. 환경: 로컬 macOS(arm64), Python 3.11.8, Java 21(ms-21.0.11), Docker 실행 중(linux/arm64).

| 대상 | 명령 | 결과 | 전제 |
|---|---|---|---|
| Python 계약/단위 | `python3 -m unittest discover -s tests` | 58개 통과, 실패 0, skip 0 | 비공개 artifact 5개 있음 |
| Python (Public Git 조건) | 같은 명령, `TRUSTAGENT_PRIVATE_ARTIFACT_ROOT`를 빈 경로로 | 58개 중 3개 skip, 55개 통과 | CI와 같은 조건 |
| Java Core | `./gradlew clean test bootJar --offline --no-daemon` | 82개 통과, 실패 0, skip 0, executable jar 생성 | PostgreSQL 18.6 Testcontainers |

같은 검증을 PR #11 브랜치(커밋 대상만 포함한 worktree)에서 반복해 같은 결과를 얻었다(TASK-001 AC-05, AC-06).

복구용 사본: 미커밋 작업 전체를 `/Users/faker/Dev/trust-agent-backups/2026-10-05-uncommitted-day5/`에 보존했다(대상 목록 25항목, 추적 파일 diff, 미추적 파일 17개 tar, 기준 커밋, TASK-001 파일 목록 JSON). 삭제는 하지 않았다.

## 3. 결론

1. **우선 흐름의 핵심 자산은 이미 있고 PR #11로 보존된다.** 합성 중도상환수수료 공문 v1/v2, `structured_change` 계약, 적용 공문 선택 로직, 시간 정책, checklist 사용 정책, canonical hash. 전부 KEEP.
2. **PostgreSQL 구현은 KEEP이다.** Flyway V1~V5, role, trigger, 제약, SQL, Testcontainers PostgreSQL, 통합 테스트 8개 클래스. 이전 초안의 MODIFY(규칙 유지, 수단 재작성)는 Oracle 전환 전제였고 ADR-009로 대체됐다. 업무 규칙 R-01~R-18과 현재 PostgreSQL 수단의 대응은 ADR-009 2절.
3. **공개 상품 KB 파이프라인은 우선 흐름의 critical path에 없다.** 중도상환수수료 공문의 `public_cross_check`는 모두 null. KEEP(동결). 셀러론 공문의 공개 테이블 FK 의존은 PostgreSQL 유지로 문제가 아니다(이전 쟁점 2 소멸).
4. **DROP 판정은 하나다.** 일회성 `migrate_public_kb_pipeline_v2.py`와 전용 테스트 1개. 삭제 범위에 KEEP 테스트가 쓰는 상수 이동 포함(TASK-002).
5. **NEW가 필요한 영역:** 로컬 PostgreSQL compose와 `.env.example`, 검색(Elasticsearch), Core Tool API, 대표 E2E. Redis는 보류.
6. **미커밋 합성 공문 조회 작업은 처리안 1로 PR #11에 보존됐다.**

## 4. 영역별 판정

"인간 판정" 열은 사용자의 판정이다. 재평가된 행은 이전 판정과 현재 판정을 함께 적는다.

### 4.1 데이터 (datasets, contracts, 비공개 artifact)

| 자산 | 도입 구분 | 근거 | 인간 판정 |
|---|---|---|---|
| `contracts/synthetic-internal-notice.schema.json` (`structured_change` 추가 포함) | Pre-SDLC | 우선 흐름의 원천 계약. 소수는 문자열 십진수로 저장해 canonical hash 원칙 유지 | **KEEP** |
| `contracts/synthetic-internal-notice-receipt.schema.json`, `synthetic-internal-policy-extraction.schema.json` | Pre-SDLC | `receivedAt`과 `businessDate` 분리, extraction attempt append-only의 계약 | **KEEP** |
| `contracts/fixtures/internal-checklist-availability-policy-cases.json` | Pre-SDLC | checklist 사용 허용 판정의 정답표 11개 | **KEEP** |
| `contracts/fixtures/canonical-json-hash-cases.json` | Pre-SDLC | Python과 Java 공용 hash fixture | **KEEP** |
| prepayment-fee notice v1/v2 + receipts 2개 + extraction attempts 2개 | Pre-SDLC (PR #11) | 유일한 대표 시나리오 데이터. v1 1.2퍼센트(2026-09-15 시행) → v2 0.8퍼센트(2026-10-01 시행), 조건 2개, 예외 1개, 고객 계약일 확인 항목 | **KEEP** |
| seller-loan-checklist v1/v2 + receipts/extraction 4개 | Pre-SDLC | 내부 공문과 공개 KB를 잇는 유일한 `public_cross_check` 사례(법인 한도 20억원). ADR-008의 FAIL 경로 검증에 필요. `internal_notice_reference`의 `public_product`, `public_snapshot` FK는 PostgreSQL 유지로 그대로 충족 | **KEEP** (보조 사례). 이전 쟁점 2 S1-b는 대체됨 |
| `contracts/public-*.schema.json` 10개 | Pre-SDLC | 공개 상품 4계층과 이력 계약. critical path 밖 | **KEEP(동결).** 신규 확장 없음 |
| `datasets/public/kb/**` 24파일, `datasets/derived/public-kb/**` 15파일 | Pre-SDLC | 재생성에 비공개 원문 필요 | **KEEP(동결)** |
| `.private-artifacts/public-kb/sha256/*.html` 5개 (git 밖) | Pre-SDLC | 재취득 불가. 삭제 시 private golden 테스트 3개 영구 skip | **KEEP(동결)** |
| `contracts/fixtures/public-product-confirmation-policy-cases.json` | Pre-SDLC | 공개 근거 freshness 정답표 | **KEEP(동결)** |
| `contracts/synthetic-work-*.schema.json` 2개, `datasets/synthetic/work/**` 2파일 | Pre-SDLC | MVP 7단계 상담 준비안에서 필요 | **KEEP** (후속 사용) |
| `datasets/synthetic/README.md`, `.gitignore`의 datasets 규칙 | Pre-SDLC | 합성 고지와 비공개 경로 차단 | **KEEP.** 현재 상태 설명은 승인된 Task에서 갱신 |

### 4.2 수집 스크립트 (`scripts/`)

| 자산 | 근거 | 인간 판정 |
|---|---|---|
| `collect_public_kb_snapshots.py` (738 LOC) | kbstar.com 전용 수집기. 11개 테스트 | **KEEP(동결)** |
| `extract_public_kb_product_facts.py` (837 LOC) | 약관 fact 추출과 변경 감지. 26개 테스트 | **KEEP(동결)** |
| `migrate_public_kb_pipeline_v2.py` (272 LOC) | 공개 pipeline v2 전환용 일회성 도구. 입력 경로 부재. 전용 테스트 1개는 CI에서 항상 skip. 참조: `tests/contract/test_public_product_versions.py` 12행 import, 227행 `migration.EXTRACTION_RUN_ID`(KEEP 테스트 사용), 295행 | **DROP.** 삭제 실행은 TASK-002 |
| `public_product_freshness.py` (128 LOC) | Java 정책과 같은 fixture를 쓰는 Python 참조 구현 | **KEEP(동결)** |
| `requirements.txt` 4개 고정 | hash 고정 없음 | **KEEP.** 의존성 보안 개선은 별도 범위 |

### 4.3 기존 코드 (`apps/`)

Java 4,547 LOC, 43개 클래스.

| 자산 | 근거 | 인간 판정 |
|---|---|---|
| 정책: `InternalChecklistUsePolicy`, `BusinessTimePolicy`, `PublicEvidenceConfirmationPolicy`, `CanonicalJsonHasher`, `AppendOnlyBootstrapChecks` | 우선 흐름의 판단 규칙. 단위 테스트 통과 | **KEEP** |
| `internalpolicy/query/` 중 `Service`(285), `Controller`, `State`, `Exception`, `ExceptionHandler` (PR #11) | 적용 공문 선택 로직 | **KEEP** |
| `internalpolicy/query/InternalPolicyApplicableRepository` (207, PR #11) | PostgreSQL SQL(`select exists`, `join lateral`, `limit`, `::text`). 조회 의도 명확 | **MODIFY → KEEP(재평가).** PostgreSQL 결합 외 수정 이유 없음 |
| `internalpolicy/bootstrap/SyntheticInternalImporter` (692) + `SyntheticInternalImportConfiguration` | PostgreSQL SQL. 컬럼 미지정 `INSERT ... VALUES`(7곳)는 DDL 컬럼 순서 의존이라 취약 | **MODIFY → KEEP(재평가)** + **MODIFY 유지(컬럼 명시 INSERT, DB 무관 이유).** 후속 Task에서 처리 |
| `config/RuntimeDatasourceConfiguration`, `health/ReadinessDatabaseClient` | PostgreSQL `SET statement_timeout`, `select 1`, `limit 1` | **MODIFY → KEEP(재평가)** |
| `config/*` 나머지, `health/*HealthIndicator`, `web/RequestTraceFilter`, 예외 핸들러 2개, DTO record | SQL 없음 | **KEEP** |
| `publicproduct.baseline` (`BaselineImporter` 700, `BaselineDatasetLoader` 723 등) | 공개 상품 baseline 적재. PostgreSQL에서 동작 | **KEEP(동결)** |
| `publicproduct.query` (`Repository` 251 등) | `/api/v1/public-products/{productKey}/observed-state`. 동작 중 | **KEEP(동결).** 이전 "전환 기간 비활성 계약"은 대체됨 |
| `apps/core-service/README.md` (PR #11) | 구현 상태 반영. PostgreSQL 18.6 기준 표기 | **처리안 1 범위 KEEP** |
| `apps/ai-service/README.md`, `apps/frontend/README.md` | 코드 없음 | **KEEP.** 구현 완료로 표현하지 않음 |
| `build.gradle` 의존성, `gradle.lockfile`, `gradle/verification-metadata.xml` | PostgreSQL 드라이버, Flyway PostgreSQL, Testcontainers PostgreSQL. 잠금 원칙 | **MODIFY → KEEP(재평가)** |

### 4.4 DB 및 검색 설정

| 자산 | 근거 | 인간 판정 |
|---|---|---|
| Flyway `V1` (감사/권한/공개 상품 14 테이블) | R-01~R-08을 PostgreSQL trigger, event trigger, NOLOGIN role, CHECK로 강제. 9개 통합 테스트 | **MODIFY → KEEP(재평가)** |
| `V2`, `V3` | importer role, 성능 인덱스 4개 | **MODIFY → KEEP(재평가)** |
| `V4` (내부 공문 11 테이블, btree_gist EXCLUDE, partial unique) | R-09~R-14 강제. 7개 통합 테스트. 후속 Task(V6~V8)가 이 위에 추가 | **MODIFY → KEEP(재평가)** |
| `V5` (`structured_change` jsonb, PR #11) | R-16 강제 | **MODIFY → KEEP(재평가).** PR #11로 보존 |
| `application.yml` (expected-version 5), `application-prod.yml` | PostgreSQL URL, `statement_timeout`, 세 벌 자격증명 | **MODIFY → KEEP(재평가)** |
| 검색 구성 | 없음. CLAUDE.md에 방향은 있으나 상세 ADR 없음 | **NEW 인정** (ADR-010, TASK-009) |
| Redis 구성 | 우선 흐름에 필수 지점 미확인 | **보류** |

### 4.5 Docker 및 환경

| 자산 | 근거 | 인간 판정 |
|---|---|---|
| `infra/docker/README.md` ("구성 예정") | compose 없음. 로컬 DB는 수동 PostgreSQL 전제 | **NEW 인정.** PostgreSQL 18.6 compose(digest 고정)와 이후 ES |
| `.env.example` | 파일 없음 | **NEW 인정** |
| Testcontainers PostgreSQL digest 고정 | 동작 중 | **MODIFY → KEEP(재평가)** |
| Gradle wrapper sha 고정, `verification-metadata.xml` | 공급망 보호 | **KEEP** |

### 4.6 테스트

| 자산 | 근거 | 인간 판정 |
|---|---|---|
| Python 58개 | DB 무사용. `test_private_migration_is_deterministic_with_fixed_run_ids` 1개는 DROP | **KEEP**, 1개 DROP |
| `tests/contract/test_synthetic_notice_contracts.py` (PR #11) | 우선 흐름 계약 테스트 | **KEEP** |
| Java 단위 11개 클래스 23개 | SQL 없음 | **KEEP** |
| `SyntheticInternalSchemaIntegrationTest` (7), `PublicProductSchemaIntegrationTest` (9) | PostgreSQL 메커니즘과 SQLSTATE 검증. R-ID 불변식 명세 그 자체 | **MODIFY → KEEP(재평가).** 이전 "불변식만 재사용, Oracle 검증 신규"는 대체됨 |
| `SyntheticInternalImporterIntegrationTest` (8), `InternalPolicyApplicableIntegrationTest` (8, PR #11) | 업무 단언. PostgreSQL fixture SQL | **MODIFY → KEEP(재평가)** |
| `CoreApplicationIntegrationTest` (7), `BaselineImportCommandIntegrationTest` (1) | 동작 중 | **MODIFY → KEEP(재평가)** |
| `BaselineImporterIntegrationTest` (13), `PublicProductObservedStateIntegrationTest` (6) | 공개 상품 | **KEEP(동결)** |
| `AppendOnlyBootstrapChecksTest` (3, PR #11) | 순수 단위 | **KEEP** |
| `tests/e2e/` (README만) | 대표 E2E 없음 | **NEW 인정** (TASK-011) |
| 메서드명 `...VersionFour` 잔존 | 기대값 5, 이름 4. DB 무관 | **MODIFY 유지.** 관련 테스트 변경 Task에서 정리 |

### 4.7 CI

| 자산 | 근거 | 인간 판정 |
|---|---|---|
| `python-contracts` job | 3개 skip은 의도된 동작 | **KEEP** |
| `gradle-tests` job | PostgreSQL Testcontainers로 20분 timeout 안에 동작 | **MODIFY → KEEP(재평가)** |
| 금지 파일 검사 | `.env.example` 예외 포함 | **KEEP** |
| CI 공백: e2e, lint, pip hash, 원격 실행 evidence 기록 | evidence는 모두 로컬 실행 결과 | **E2E NEW 인정**, 나머지 별도 범위 |

## 5. 미커밋 합성 공문 조회 작업 처리 (처리안 1 채택, PR #11)

대상은 복구용 사본의 `target-list.txt` 25항목(파일 30개)이다.

| 묶음 | 파일 | 성격 |
|---|---|---|
| A. 우선 흐름 데이터와 계약 | prepayment-fee 6개, notice schema diff, seller-loan `structured_change: null` diff, `test_synthetic_notice_contracts.py` diff | 대표 시나리오 |
| B. 적용 공문 조회 (SQL 없는 부분) | `internalpolicy/query/` 중 Service, Controller, State, Exception, Handler, `AppendOnlyBootstrapChecksTest` | 서비스 로직 |
| C. PostgreSQL 부분 | `InternalPolicyApplicableRepository`, `SyntheticInternalImporter` diff, `V5` SQL, `application.yml` diff, 통합 테스트 4개 diff, `InternalPolicyApplicableIntegrationTest` | 현재 구현 수단. 재작성 대상 아님(재평가) |
| D. 문서 | `INTERNAL_POLICY_APPLICABLE_QUERY_EVIDENCE.md`, `FINAL_PROPOSAL_ALIGNMENT_EVIDENCE.md`, README 3종, AGENTS.md diff | evidence와 현황 설명 |

처리안 1(전부 보존)은 [TASK-001](TASK-001_합성-공문-조회-자산-보존.md)로 실행 중이며 PR #11에 있다. 허용된 변경은 evidence 2건의 Pre-SDLC 표기와 core README의 PostgreSQL 기준 문장이며, PostgreSQL 유지 결정에 맞춰 문구를 정정했다(TASK-001 변경 이력). 기각된 처리안 2, 3의 이유는 이전과 같다.

이전 쟁점 2(전환 1단계 셀러론 공문 처리, S1-b 채택)는 PostgreSQL 유지로 **소멸**했다. 공문 4개 전부 적재를 유지한다.

## 6. 공개 상품 파이프라인 묶음

KEEP(동결). 신규 확장은 하지 않는다. 이전 "Oracle 이식 후순위"와 "전환 기간 비활성 계약" 문구는 대체됐다. PostgreSQL에서 현재대로 동작한다.

## 7. AI 제안 및 인간 판단 기록

결정자는 사용자. 사용자의 실행 지시와 결과물 검수 통과는 구분한다. 아래 판단은 "판정 채택"이며 결과물 검수 통과가 아니다. 이전 기록은 삭제하지 않고 대체 관계를 적는다.

| 제안 ID | 내용 | 판단 | 이유 / 승인 범위 | 검토 대상 |
|---|---|---|---|---|
| 제안 1 | 공개 상품 파이프라인 KEEP(동결) | **채택** | critical path 밖, 검증된 자산 | 초안 revision |
| 제안 2 | 미커밋 합성 공문 조회 작업 처리안 1 | **조건부 채택** | 하나의 Task(TASK-001). 구현과 관련 문서 동반은 정상 범위 | 초안 revision, PR #11 |
| 제안 3 | `migrate_public_kb_pipeline_v2.py`와 전용 테스트 1개 DROP | **채택** | 삭제 실행은 TASK-002 승인 범위 | 초안 revision |
| 제안 4 | PostgreSQL 메커니즘 테스트 2개는 불변식 명세만 재사용, Oracle 검증 신규 | **대체됨** | PostgreSQL 유지로 테스트 그대로 KEEP | ADR-009 |
| 추가 판정 | 대표 합성 데이터, 계약, 정책, canonical hash, 정답표 KEEP | **채택** | — | 초안 revision |
| 추가 판정 | PostgreSQL SQL, migration, 통합 테스트, 환경 설정 MODIFY | **대체됨 → KEEP(재평가)** | PostgreSQL 결합만이 이유였음. DB 무관 이유의 MODIFY(컬럼 명시 INSERT, `VersionFour` 메서드명)는 유지 | ADR-009, PR #10 |
| 추가 판정 | 검색, Core Tool API, E2E, PostgreSQL compose와 `.env.example` NEW | **인정** | 각 Task에서 범위와 AC 확정 | 초안 revision |
| 추가 판정 | Redis | **보류** | 사용처 확인 시 재판단 | 초안 revision |
| 추가 판정 | 남은 행 10건(synthetic/work KEEP, .gitignore/README KEEP, requirements KEEP, SQL 없는 클래스와 단위 테스트 KEEP, placeholder KEEP, wrapper KEEP, `VersionFour` MODIFY, CI python job과 금지 파일 검사 KEEP) | **채택** | 4절에 기록 | 초안 revision |
| 쟁점 2 | 전환 1단계 셀러론 처리 S1-b | **대체됨(소멸)** | PostgreSQL 유지 | ADR-009 |
| 실행 범위 | 문서 정리 PR, TASK-001, TASK-002, TASK-003을 각각 분리해 commit, push, PR 생성 | **승인 → TASK-003 부분 철회** | 병합은 사용자. TASK-003 Oracle 실행 승인 철회, 산출물 보존 | PR #10, #11 |
| 수정 지시 | 단정 표현("비용 0", "DB 무관이므로 수정 불필요", "위험 적음") | **수정 완료** | 실제 유지 비용과 의미 차이 반영 | PR #10 |

## 8. AI self-review

- 검사 범위: 저장소 전체 인벤토리, Git 상태와 diff, ADR 8건과 evidence 11건, Python 58개와 Java 82개 재실행(메인 작업 트리와 PR #11 worktree), migrate 스크립트 참조 전수 조사, 합성 공문의 공개 참조 전수 조사, PostgreSQL 유지 결정에 따른 판정 재평가.
- 발견 사항: (1) `application.yml`과 테스트 4개가 V5를 전제하므로 V5와 함께 보존해야 한다(PR #11에 포함). (2) `.env.example`이 없다. (3) evidence에 CI 원격 실행 결과가 없다. (4) `PublicProductSchemaIntegrationTest` 메서드명이 기대값과 어긋난다. (5) V1 event trigger는 Flyway 실행 계정에 superuser 권한을 요구하는데 README에 없다. (6) KEEP 테스트가 DROP 대상 스크립트의 상수를 쓴다. (7) `SyntheticInternalImporter`의 컬럼 미지정 INSERT는 DB와 무관하게 취약하다.
- 미해결 위험: (5)와 (7)은 후속 Task에서 다룬다.

## 9. 인간 검수와 다음 단계

- 이 문서의 인간 검수 / Explainability Gate: **미기록.**
- 실행: TASK-001(PR #11 병합 대기), TASK-002(TASK-001 병합 뒤). TASK-005 착수는 별도 승인.
