# PLAN-001 중도상환수수료 공문 변경 흐름의 Task 분할과 의존 순서

- 성격: "중도상환수수료 공문 변경 → 검수/승인 → 행원 업무 제공" 흐름을 검수 가능한 Task로 나눈 계획. 사용자 판단을 반영했다. 각 Task는 승인 뒤 [Task 템플릿](../development/TASK_TEMPLATE.md)으로 정식 작성하며, TASK-001~003은 사전 Acceptance Criteria까지 작성했다.
- 작성: AI / 결정자: 사용자
- 상태: **계획 채택(조건부).** TASK-001, 002, 003 실행 승인. TASK-004 착수는 TASK-003 결과를 반영한 정식 계획과 AC 검토 뒤 별도 승인. 전체 Task 구현을 일괄 승인한 것은 아니다.
- 전제: [ADR-009](../adr/ADR-009-oracle-core-database.md) 방향 채택, [자산 audit](TASK-000_자산-audit-초안.md) 판정.
- 관련: [ADR-008](../adr/ADR-008-internal-notice-effective-policy-and-review.md) Day 5c 범위, README MVP 목표 4~6단계와 8~9단계

## 0. 번호 변경

이전 초안 대비 Task 번호가 바뀐 곳이다. 이전 초안의 TASK-002(환경), TASK-003(schema), TASK-004(이식)는 하나의 PR로만 main에 들어갈 수 있어 TASK-004로 합쳤고(3절 전환 경로), 삭제 실행과 Oracle 위험 검증 spike를 새로 넣었다.

| 이전 | 현재 | 내용 |
|---|---|---|
| TASK-001 | TASK-001 | 미커밋 Day 5 자산 보존 |
| (없음) | **TASK-002** | 일회성 migrate 스크립트 DROP 실행 |
| (없음) | **TASK-003** | Oracle 위험 검증 spike |
| TASK-002, 003, 004 | **TASK-004** | Oracle 전환 1단계 (환경 + 기반 schema + 내부 공문 테이블 + 이식 + 공개 기능 비활성 계약) |
| TASK-005~011 | 동일 | proposal, validation, 사람 결정, Core Tool API, Elasticsearch, 공개 상품 이식, E2E |

## 1. 흐름을 업무 사건으로 풀어 쓴 것

대표 시나리오는 합성 공문 family `SIN-PREPAYMENT-FEE`다. v1은 중도상환수수료율 1.2퍼센트(2026-09-15 시행), v2는 0.8퍼센트(2026-10-01 시행, v1 대체)이며 조건 2개, 예외 1개, "고객 계약일 확인" 항목 추가가 구조화돼 있다.

| 단계 | 업무 사건 | 시스템이 만드는 것 | 현재 상태 |
|---|---|---|---|
| S1 | v2 공문을 수신하고 구조화한다 | InternalNoticeVersion, Receipt, PolicyExtractionAttempt, RuleVersion(structured_change) | 구현됨 (PostgreSQL, 미커밋) |
| S2 | 업무일과 인지 시각 기준으로 적용 공문을 고른다 | applicable 조회, AMBIGUOUS fail-closed | 구현됨 (PostgreSQL, 미커밋) |
| S3 | 새 rule과 직전 승인 checklist의 차이를 변경 후보로 만든다 | ChecklistChangeProposal + item (DERIVED), revision | 미구현 |
| S4 | 숫자와 시행일, 대상 상품, 조건을 자동 검증한다 | AutomatedValidationResult + issue | 미구현 |
| S5 | 공문 담당 부서 검수자가 승인, 수정, 반려한다 | HumanReviewDecision, 승인 시 ApprovedChecklistVersion + ScheduleRevision/Entry | 미구현 (테이블은 V4에 있음) |
| S6 | 행원이 상담 전에 적용 checklist를 조회한다 | applicable 조회가 AVAILABLE, `internalChecklistUseAllowed=true` | 조회는 있으나 AVAILABLE 경로 미검증 |
| S7 | AI 서비스가 Core Tool API로 같은 결과를 읽는다 | Core Tool API(read-only) | 미구현 |
| S8 | 행원이 근거 원문을 검색한다 | Elasticsearch 인덱스 | 미구현 |
| S9 | AI 중단 시에도 수기 checklist를 제공하고 AI 확정 경로를 차단한다 | S6가 Core만으로 동작하면 충족 | S6에 포함 |

**S1~S6 완료는 "Core 승인과 조회 흐름 완료"다.** FastAPI 상담 준비안, 검색, 화면까지 포함한 전체 MVP 완료와 구분한다.

### 공개 상품 의존성 확인 (사실)

- 중도상환수수료 v1(rule 2개), v2(rule 3개)의 `public_cross_check`는 모두 null이다. 공개 KB 교차 검증 없이 S3~S6을 검증할 수 있다.
- `structured_change.applicable_product_keys`는 `["kb-seller-loan"]` 문자열 배열이며 DB FK가 아니다. S4의 "대상 상품 존재" 검증은 공개 상품 테이블(`public_product`)에 의존하지 않도록 catalog JSON의 product_key 목록 또는 형식 검증으로 한정한다(TASK-006).
- 셀러론 공문(보조 사례)은 `internal_notice_reference`가 `public_product`, `public_snapshot`을 FK로 참조한다(V4 56~57행). 전환 1단계에서 셀러론 공문을 적재하려면 공개 상품 테이블 일부가 필요하다. 처리 방식은 [자산 audit 5절 쟁점 2](TASK-000_자산-audit-초안.md)에서 **S1-b로 채택**됐다. 전환 1단계 synthetic baseline은 `SIN-PREPAYMENT-FEE` family만 적재하고, 셀러론 자료는 저장소에 유지하며, FK 완화(S1-c)는 하지 않는다. importer 테스트의 공문 4개 → 2개 변경은 승인된 적재 범위 변경이다.

## 2. Task 분할

역할은 Task별로 정한다. 기본 제안: AI가 조사, 초안, 구현, 자동 검증, self-review를 맡고 사용자가 범위, AC 확정, 제안 판단, 검수, Explainability Gate를 맡는다. Task마다 다르게 정할 수 있다.

### TASK-001 미커밋 Day 5 자산 보존

정식 문서: [TASK-001](TASK-001_미커밋-Day-5-자산-보존.md). 처리안 1 조건부 채택. 하나의 PR. 구현에 필요한 README, 계약, 테스트, evidence 동반은 정상 범위.

### TASK-002 일회성 migrate 스크립트 DROP 실행

정식 문서: [TASK-002](TASK-002_일회성-migrate-스크립트-DROP-실행.md). DROP 판정은 채택됐고 삭제 실행은 사용처, 문서 참조, 사본, 목록 확인 후 별도 승인. KEEP 테스트가 쓰는 상수 이동이 범위에 포함된다.

### TASK-003 Oracle 위험 검증 spike

정식 문서: [TASK-003](TASK-003_Oracle-위험-검증-spike.md). core-service를 건드리지 않는 별도 Gradle 하위 프로젝트에서 버전/이미지/호환 확인, R-12 기간 중첩과 동시성, R-04 DDL 보호, R-02 대표 범위 JSON 감사 trigger, 의미 차이(빈 문자열, DATE, BOOLEAN, OffsetDateTime)를 실험한다. 결과가 ADR-009 2.3절의 "검증 대기"를 "검증됨" 또는 "대안 필요"로 바꾼다.

### TASK-004 Oracle 전환 1단계

- 업무 결과: core-service가 Oracle에서 기동하고, 합성 공문이 적재되고, `GET /api/v1/internal-policy/checklists/SIN-PREPAYMENT-FEE/applicable`이 2026-10-01 업무일에 v2와 구조화 변경(1.2 → 0.8 PERCENT)을 반환한다. 공개 상품 기능은 비활성 계약으로 응답한다.
- 포함: 네 체크포인트(3절). (C1) 환경: compose(Oracle, digest 고정, 양 아키텍처), `.env.example`, 빌드 의존성 교체와 lockfile/verification metadata 재생성, DataSource와 readiness의 Oracle 대응, Oracle migration 디렉터리. (C2) 기반 schema: R-01~R-05 감사/권한/가드와 규칙 ID별 schema 테스트. (C3) 내부 공문 테이블 11개와 `structured_change`(V4, V5 내용), R-09~R-14, R-16 테스트. (C4) `SyntheticInternalImporter`, `InternalPolicyApplicableRepository` 방언 교체, 통합 테스트 fixture 교체, 공개 기능 비활성 계약(설정, 503 `FEATURE_UNAVAILABLE`, importer 거부, readiness 무영향)과 그 테스트, 공개 상품 통합 테스트의 비활성 계약 테스트 전환.
- 제외: 공개 상품 테이블 13개(TASK-010). proposal, validation, 사람 검토.
- AC 초안: (1) C4 완료 시점의 전체 Java 테스트가 Oracle Testcontainer에서 통과하고 skip 0. (2) 테스트 수 변화를 "삭제된 테스트, 대체된 테스트, 추가된 테스트" 표로 evidence에 기록. 공개 상품 업무 단언 20개(`BaselineImporterIntegrationTest` 13, `PublicProductObservedStateIntegrationTest` 6, `BaselineImportCommandIntegrationTest` 1)는 명세 문서로 보존하고 TASK-010에서 복원. (3) 기존 `SyntheticInternalImporterIntegrationTest` 8개와 `InternalPolicyApplicableIntegrationTest` 8개의 업무 단언이 Oracle에서 통과. 공문 수 단언은 S1-b에 따라 4개 → 2개로 조정하고 승인된 적재 범위 변경으로 evidence에 기록. (4) 공개 endpoint 호출이 503 `FEATURE_UNAVAILABLE`과 trace ID를 반환하고 readiness와 내부 공문 endpoint는 정상. (5) `baseline-import.enabled=true` 기동이 적재 없이 `FEATURE_UNAVAILABLE`로 종료. (6) R-01~R-05, R-09~R-14, R-16 각각에 대응하는 테스트가 R-ID를 명시. (7) R-12 동시성 테스트(두 세션 동시 삽입, 하나만 성공, 실패 세션 rollback)가 core-service에서도 통과. (8) PostgreSQL 의존성이 빌드에서 제거. (9) CI gradle job 통과와 소요 시간 기록. (10) Java 변경 클래스 목록과 8개 목표 밖 변경의 이유를 evidence에 기록.
- 검증: Oracle Testcontainer 통합 테스트, compose 기동, CI 실행 링크.
- 위험: 가장 큰 Task. TASK-003 결과에 따라 C2, C3 설계가 바뀐다. 아키텍처별 digest 차이. 기동 시간.
- 의존: TASK-001, TASK-003. 착수는 TASK-003 결과를 반영한 정식 계획과 AC 검토 뒤 별도 승인.

### TASK-005 checklist 변경 후보(proposal) 생성

- 업무 결과: v2 rule과 직전 승인 checklist의 차이가 결정적 proposal로 생성되고 DERIVED로 저장된다. S3.
- 포함: proposal과 item 테이블(Oracle migration 추가), 결정적 generator(LLM 미사용, generator version), base checklist와 target notice와 rule 참조, 추가/수정/삭제와 before/after, `supersedes_proposal_id`와 before/after hash, 같은 입력은 같은 canonical 내용. 생성 command(test/demo profile 한정).
- **v1 승인 checklist bootstrap (제안 A 채택):** v1 승인 checklist와 schedule을 합성 fixture로 둔다. fixture는 test/demo 전용이고 실제 인간 승인 기록이 아님을 데이터와 응답에 명시한다. 실제 승인 경로(S5)의 검증은 TASK-007에서 별도로 포함한다.
- AC 초안: (1) v1 fixture가 있고 v2가 수신된 상태에서 proposal 1건이 생성되고 item에 `prepayment_fee_rate_percent` "1.2" → "0.8", `effective_on` 2026-10-01, `customer_contract_date_check` 추가가 들어 있음. (2) 같은 입력으로 두 번 생성하면 proposal hash가 같고 실행 사건 ID만 다름. (3) 직전 승인 checklist가 없으면 proposal을 만들지 않고 `NO_BASE_CHECKLIST`로 기록. (4) proposal 수정 시도는 거부되고 새 revision만 가능. (5) 응답과 저장에 dataset class DERIVED와 fixture 표시.
- 의존: TASK-004.

### TASK-006 자동 검증(validation)

- 업무 결과: proposal에 대해 숫자, 시행일, 대상 상품, 조건 일관성 검증이 PASS/WARN/FAIL과 issue로 append-only 저장된다. MVP 5단계. S4.
- 포함: validation result와 issue 테이블, validator version, proposal hash, `validated_at`, 사용한 evidence ID. 검증 규칙: after 값이 공문 `structured_change`와 일치, `effective_on`이 공문 `effective_from`과 일치, 대상 상품 key가 catalog JSON 목록에 존재(공개 상품 테이블 비의존), 단위 일치, 조건과 예외 누락 없음. 공개 교차 검증은 `public_cross_check`가 있는 항목에만 적용하고 없으면 `PUBLIC_CROSS_CHECK_NOT_APPLICABLE` 기록. 재검증은 새 결과 추가.
- 제외: 공개 KB 교차 검증의 실제 조회(TASK-010 이후).
- AC 초안: (1) 정상 proposal은 PASS. (2) after 값을 "0.08"로 바꾼 fixture는 `FAIL VALUE_MISMATCH`. (3) `effective_on`을 2026-10-02로 바꾼 fixture는 `FAIL EFFECTIVE_DATE_MISMATCH`. (4) 대상 상품 오타는 FAIL. (5) `public_cross_check` 없는 항목은 `PUBLIC_CROSS_CHECK_NOT_APPLICABLE`. (6) 재검증 시 결과 2건이 남고 `validated_at <= knownAt`인 것만 보임.
- 의존: TASK-005.

### TASK-007 사람 검수 결정과 승인 checklist 발행

- 업무 결과: 검수자가 APPROVE, MODIFY, REJECT를 기록하고, APPROVE 시 ApprovedChecklistVersion과 ScheduleRevision/Entry가 생성되어 S6 조회가 `AVAILABLE`, `internalChecklistUseAllowed=true`를 반환한다. S5와 S6. 여기까지가 "Core 승인과 조회 흐름 완료"다.
- 포함: HumanReviewDecision 테이블과 command(test/demo profile, 합성 actor, production 비활성). FAIL issue, validation 없음, proposal hash 변경 시 APPROVE 거부. `max_validation_age` 경계. WARN 승인 시 사유 필수. MODIFY는 새 revision과 재검증 요구. 승인 시 기존 schedule 복사 + 이전 구간 종료일 제한 + 새 entry 추가한 새 revision. 동시 승인 충돌. applicable 조회의 AVAILABLE 경로와 `InternalChecklistUsePolicy` 호출. TASK-005의 v1 fixture 대신 실제 승인 경로로 v1을 승인하는 검증도 포함.
- **분할 여부 (제안 C 채택):** 정식 AC와 크기를 보고 결정한다. 분할하더라도 승인 결정, checklist 발행, schedule revision 발행은 하나의 트랜잭션으로 원자성을 유지한다. 반쪽 승인 상태(decision만 있고 schedule 없음, 또는 반대)가 생기지 않는다.
- AC 초안: (1) FAIL이 있는 proposal의 APPROVE 거부. (2) PASS proposal의 APPROVE 뒤 2026-10-01 조회가 v2 checklist를 AVAILABLE로 반환하고 `internalChecklistUseAllowed=true`. (3) 2026-09-30 조회는 v1 checklist 반환. (4) MODIFY는 승인 상태를 만들지 않고 새 revision 생성, 재검증 전 APPROVE 불가. (5) 같은 revision을 동시에 대체하는 두 승인 중 하나만 성공. (6) `validated_at`이 `max_validation_age` 초과 시 `VALIDATION_STALE` 차단. (7) 과거 `knownAt` 조회는 승인 입력 불가. (8) production profile에서 review command bean 없음. (9) 모든 결정이 actor, 사유, 시각, hash와 함께 append-only 저장. (10) 승인 트랜잭션 중간 실패 시 decision, checklist, schedule 어느 것도 남지 않음.
- 의존: TASK-006.

### TASK-008 Core Tool API (AI 서비스용 read-only 조회 경계)

- 업무 결과: FastAPI AI 서비스가 Oracle에 접근하지 않고 Core의 Tool endpoint로 적용 checklist와 근거를 읽을 수 있다. S7의 Core 쪽. ADR-011 승인 포함. **제안 D 채택: 검색(TASK-009)보다 먼저 진행한다.** FastAPI 소비 흐름은 후속 AI 서비스 Task로 연결한다.
- 포함: read-only Tool endpoint(applicable checklist, notice rule evidence). 서비스 인증(demo 수준임을 명시), Tool allowlist, 입력/출력 schema, 최소 데이터(공문 본문 전체 미노출), 감사 로그. 응답에 `internalChecklistUseAllowed`와 blocking reason 포함. 쓰기 경로 없음.
- AC 초안: (1) 인증 없는 호출 401. (2) allowlist 밖 tool 404. (3) 응답 schema가 contract fixture와 일치. (4) Core policy가 계산한 `internalChecklistUseAllowed`가 응답에 포함되고 AI 쪽 재계산에 의존하지 않음. (5) 쓰기 endpoint 없음. (6) 감사 로그에 공문 본문 미포함.
- 의존: TASK-007. TASK-004 뒤 설계 착수 가능.

### TASK-009 Elasticsearch 검색 기준선

- 업무 결과: 승인 checklist와 공문 rule 원문이 dataset class metadata와 함께 색인되고, 골든셋 기준 사전 지표를 넘는다. S8. ADR-010 승인 포함.
- 포함: **최종 범위는 CLAUDE.md baseline 전체(BM25 + dense vector k-NN + metadata filter + reranker)다.** BM25 + metadata filter만 쓴 구성은 비교 기준으로 사용한다. 단순 Hybrid와 Ontology-aware(경량 관계 metadata: 공문-상품-조건-예외-시행일) 비교. Oracle → ES 전달(Outbox 또는 재색인 job)과 멱등성. 미승인, 구버전, 철회 공문 제외 filter. 골든셋과 Recall@5/MRR 사전 정의. compose에 ES 추가.
- **범위 축소 규칙:** dense k-NN이나 reranker를 최종 범위에서 빼려면 같은 골든셋의 평가 결과와 대안을 제시하고 별도 인간 판단을 받는다. AI가 임의로 제외하지 않는다.
- AC 초안: (1) 승인된 checklist만 검색 결과에 포함되고 구버전은 filter로 제외. (2) 골든셋 질의에서 v2 rule이 상위 5위 안. (3) 재색인 두 번 실행 시 문서 수 불변. (4) 색인 문서에 dataset_class와 synthetic 표시. (5) 비교 기준(BM25 + filter)과 baseline 전체 구성의 지표 비교표와 인간 선택 기록.
- 의존: TASK-007. TASK-008 뒤.

### TASK-010 공개 상품 테이블 Oracle 이식 (후순위, 자동 확정 아님)

- 업무 결과: 공개 상품 13개 테이블과 baseline importer, observed-state 조회가 Oracle에서 동작하고 비활성 계약이 해제된다. TASK-004에서 명세로 보존한 업무 단언 20개 복원. 셀러론 공문 적재와 20억원 교차 검증 FAIL 경로가 TASK-006에 연결된다.
- 착수 여부는 별도 판단이다. 자산 audit의 KEEP(동결) 판정은 이식을 확정하지 않는다.
- 의존: TASK-004.

### TASK-011 대표 E2E와 evidence

- 업무 결과: S1~S6를 한 번에 재현하는 E2E 테스트와 evidence. **"Core 승인과 조회 흐름 완료"의 근거이며 전체 MVP 완료가 아니다.** README의 MVP 4~6단계와 8~9단계 중 Core 범위 완료 표시 근거.
- 의존: TASK-007. TASK-008 결과는 선택 포함.

## 3. Oracle 전환 경로 (TASK-004의 PR과 CI 구성)

문제: "빈 Oracle schema에서 기동"과 "PostgreSQL 의존성 제거 후 기존 통합 테스트 통과"는 한 PR에서 동시에 만족할 수 없다. 테스트 제외나 skip으로 미구현을 숨기지 않는다.

사실: core-service의 통합 테스트 8개 클래스(59개)는 모두 하나의 Testcontainer에 묶여 있다. 드라이버와 컨테이너를 Oracle로 바꾸는 순간 Oracle schema와 이식된 SQL이 없으면 59개가 실패한다. 중간 상태를 main에 넣으면 CI가 빨간 채로 병합되거나 테스트를 꺼야 한다. 둘 다 규칙 위반이다.

**경로 (AI 추천, 판단 대기):** TASK-004를 하나의 PR로 main에 넣는다. 작업 브랜치 안에서 네 체크포인트를 두고 각 체크포인트에서 사용자가 커밋 단위로 검수한다. 체크포인트별 빌드 상태는 다음과 같이 Task에 기록하며, CI는 PR 생성 시점(C4)에 전체 통과를 요구한다.

| 체크포인트 | 구성 | 로컬 상태 | 기록 |
|---|---|---|---|
| C1 환경 | Oracle 드라이버, Testcontainer, 빈 migration 디렉터리, DataSource/readiness 수정 | 단위 23개 통과. `CoreApplicationIntegrationTest` 중 readiness/liveness/actuator 통과. 나머지 통합 테스트는 schema 없음으로 실패 | 실패 목록과 원인을 Task에 그대로 기록. skip 처리 금지 |
| C2 기반 schema | R-01~R-05 migration과 신규 schema 테스트 | C1 + 기반 schema 테스트 통과. 내부 공문 테스트 실패 | 같음 |
| C3 내부 공문 schema | R-09~R-14, R-16 migration과 테스트 | C2 + 내부 공문 schema 테스트 통과. importer/조회 테스트는 SQL 방언으로 실패 | 같음 |
| C4 이식과 비활성 계약 | importer/Repository 방언 교체, 공개 기능 비활성 계약과 테스트 전환 | 전체 통과, skip 0 | PR 생성. CI 통과 필수 |

공개 상품 통합 테스트 처리(C4): `PublicProductSchemaIntegrationTest`(9)는 R-01~R-05 부분이 C2의 신규 테스트로 대체되고 공개 테이블 부분은 TASK-010 명세로 이동. `BaselineImporterIntegrationTest`(13), `PublicProductObservedStateIntegrationTest`(6), `BaselineImportCommandIntegrationTest`(1)는 비활성 계약 테스트(503 응답, importer 거부, readiness 무영향)로 전환하고 원래 업무 단언 20개는 `docs/tasks/` 아래 명세 문서로 보존한다. 테스트 수 변화는 "삭제/대체/추가" 표로 evidence에 남긴다. 이는 "숨김"이 아니라 "명시적 대체"다.

대안: 통합 브랜치 `feat/oracle-stage-1`에 C1~C3을 각각 PR로 넣고 CI 실패를 PR 본문에 선언한 뒤 C4에서 main으로 병합. 사용자 검수 단위가 PR로 남는 장점이 있지만 "병합 전 CI 통과" 규칙의 예외를 세 번 기록해야 한다. 추천하지 않는다.

- 결정자: 사용자 / 제안 ID: 전환 경로 / 판단: **채택** / 이유: 규칙 예외 없이 CI 통과 상태로만 main에 병합 / 승인 범위: 중간 실패 상태는 작업 브랜치에서 투명하게 기록하고 최종 PR은 승인된 기능 범위의 CI를 통과해야 함. 기존 테스트의 삭제, 대체, 보류 매핑을 남김. 구현 착수는 별도 승인

## 4. 의존 순서

```text
audit 판정 ─► TASK-001 보존 ─► TASK-002 DROP 실행 (독립, 소규모)
                 │
ADR-009 방향 ─► TASK-003 Oracle 위험 검증 spike (별도 하위 프로젝트)
                 │
              TASK-004 Oracle 전환 1단계 (C1 → C2 → C3 → C4, 한 PR)
                 │                                   │
              TASK-005 proposal (v1 fixture)           └─► TASK-010 공개 상품 이식 (후순위, 별도 판단)
                 │
              TASK-006 validation
                 │
              TASK-007 사람 결정 + 발행 (원자성) ─► TASK-011 E2E ("Core 승인과 조회 흐름 완료")
                 │
              TASK-008 Core Tool API (ADR-011)
                 │
              TASK-009 Elasticsearch (ADR-010, baseline 전체)
                 │
          AI 서비스 Task (FastAPI 소비, 상담 준비안, 이 계획 밖)
```

- critical path: 001 → 003 → 004 → 005 → 006 → 007.
- TASK-003이 설계 위험의 대부분을 안는다. R-12 실패 시 TASK-004 C3 설계가 바뀐다.
- TASK-008은 TASK-009보다 먼저다(제안 D).

## 5. AI 제안 및 인간 판단 기록

결정자: 사용자 / 검토 대상: 이 문서의 미커밋 초안

| 제안 ID | 내용 | 판단 | 이유 / 승인 범위 |
|---|---|---|---|
| 제안 A | v1 승인 checklist와 schedule을 합성 fixture로 bootstrap | **채택 + 조건** | test/demo 전용이고 실제 인간 승인 기록이 아님을 명시. 실제 승인 경로 검증은 TASK-007에 별도 포함 |
| 제안 B | TASK-004 전에 R-12 기간 중첩과 동시성 spike 선행 | **채택** | 겹침, 맞닿음, 열린 구간, 두 세션 동시 삽입과 rollback 검증. TASK-003으로 분리 |
| 제안 C | TASK-007 분할 여부 | **보류 (AC 작성 시 결정)** | 분할하더라도 승인 결정, checklist와 schedule 발행의 원자성 유지. 반쪽 승인 상태 금지 |
| 제안 D | Core Tool API를 검색보다 먼저 | **채택** | FastAPI 소비 흐름을 후속 Task로 연결 |
| 수정 지시 1 | TASK-002(구) 충돌 | **수정 완료** | 3절 전환 경로 작성. 경로 자체는 판단 대기 |
| 수정 지시 2 | TASK-009 범위 | **수정 완료** | BM25 + filter는 비교 기준. dense k-NN과 reranker는 최종 범위에서 임의 제외 금지. 축소는 평가 결과와 대안 제시 후 별도 판단 |
| 수정 지시 3 | TASK-011 표현 | **수정 완료** | "Core 승인과 조회 흐름 완료"로 표현. 전체 MVP 완료와 구분 |
| 수정 지시 4 | 공개 상품 의존성 확인 | **확인 완료** | 1절 "공개 상품 의존성 확인". 중도상환수수료는 비의존. 셀러론은 FK 의존이라 쟁점 2 → S1-b 채택 |
| 전환 경로 | TASK-004 한 PR, C1~C4 체크포인트 | **채택** | 3절 조건 |
| 실행 승인 | TASK-001, TASK-002, TASK-003 | **승인** | 각각 분리된 PR. 검증, secret 점검, self-review 후 commit, push, PR 생성. 병합은 사용자 |

인간 검수와 Explainability Gate: **미기록.**

## 6. 이 계획이 하지 않는 것

- TASK-004 이후의 정식 Acceptance Criteria 고정. 각 Task 승인 시 작성.
- 일정 추정.
- FastAPI AI 서비스, LangGraph 적용 범위, 화면.
- 전체 TASK-001~011 구현 승인. 각 Task 착수는 별도 승인이다.
