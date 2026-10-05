# PLAN-001 중도상환수수료 공문 변경 흐름의 Task 분할과 의존 순서

- 성격: "중도상환수수료 공문 변경 → 검수/승인 → 행원 업무 제공" 흐름을 검수 가능한 Task로 나눈 계획. 사용자 판단을 반영했다. 각 Task는 승인 뒤 [Task 템플릿](../development/TASK_TEMPLATE.md)으로 정식 작성한다.
- 작성: AI / 결정자: 사용자
- 상태: **계획 채택(조건부).** TASK-001, TASK-002 실행 승인. TASK-003 중단, TASK-004 취소([ADR-009](../adr/ADR-009-oracle-core-database.md) PostgreSQL 유지). TASK-005 이후 착수는 각각 별도 승인.
- 전제: Core 업무 DB는 PostgreSQL(ADR-009). [자산 audit](TASK-000_자산-audit-초안.md) 판정.
- 관련: [ADR-008](../adr/ADR-008-internal-notice-effective-policy-and-review.md) Day 5c 범위, README MVP 목표 4~6단계와 8~9단계

## 0. 번호 이력

| 번호 | 현재 내용 | 이력 |
|---|---|---|
| TASK-001 | 미커밋 Day 5 자산 보존 | 승인, PR #11 |
| TASK-002 | 일회성 migrate 스크립트 DROP 실행 | 승인, TASK-001 병합 뒤 실행 |
| TASK-003 | Oracle 위험 검증 spike | **중단, 실험 미완료.** 번호와 문서 보존 |
| TASK-004 | (Oracle 전환 1단계) | **취소.** 번호 재사용 금지 |
| TASK-005 | proposal 생성 | 이전 초안과 같음 |
| TASK-006 | 자동 검증 | 같음 |
| TASK-007 | 사람 검수 결정과 승인 checklist 발행 | 같음 |
| TASK-008 | Core Tool API | 같음 |
| TASK-009 | Elasticsearch 검색 기준선 | 같음 |
| TASK-010 | (공개 상품 테이블 Oracle 이식) | **취소.** PostgreSQL 유지로 불필요. 번호 재사용 금지 |
| TASK-011 | 대표 E2E | 같음 |

## 1. 흐름을 업무 사건으로 풀어 쓴 것

대표 시나리오는 합성 공문 family `SIN-PREPAYMENT-FEE`다. v1은 중도상환수수료율 1.2퍼센트(2026-09-15 시행), v2는 0.8퍼센트(2026-10-01 시행, v1 대체)이며 조건 2개, 예외 1개, "고객 계약일 확인" 항목 추가가 구조화돼 있다.

| 단계 | 업무 사건 | 시스템이 만드는 것 | 현재 상태 (PostgreSQL) |
|---|---|---|---|
| S1 | v2 공문을 수신하고 구조화한다 | InternalNoticeVersion, Receipt, PolicyExtractionAttempt, RuleVersion(structured_change) | 구현됨 (PR #11로 보존) |
| S2 | 업무일과 인지 시각 기준으로 적용 공문을 고른다 | applicable 조회, AMBIGUOUS fail-closed | 구현됨 (PR #11로 보존) |
| S3 | 새 rule과 직전 승인 checklist의 차이를 변경 후보로 만든다 | ChecklistChangeProposal + item (DERIVED), revision | 미구현 |
| S4 | 숫자와 시행일, 대상 상품, 조건을 자동 검증한다 | AutomatedValidationResult + issue | 미구현 |
| S5 | 공문 담당 부서 검수자가 승인, 수정, 반려한다 | HumanReviewDecision, 승인 시 ApprovedChecklistVersion + ScheduleRevision/Entry | 미구현 (테이블과 exclusion constraint는 V4에 있음) |
| S6 | 행원이 상담 전에 적용 checklist를 조회한다 | applicable 조회가 AVAILABLE, `internalChecklistUseAllowed=true` | 조회는 있으나 AVAILABLE 경로 미검증 |
| S7 | AI 서비스가 Core Tool API로 같은 결과를 읽는다 | Core Tool API(read-only) | 미구현 |
| S8 | 행원이 근거 원문을 검색한다 | Elasticsearch 인덱스 | 미구현 |
| S9 | AI 중단 시에도 수기 checklist를 제공하고 AI 확정 경로를 차단한다 | S6가 Core만으로 동작하면 충족 | S6에 포함 |

**S1~S6 완료는 "Core 승인과 조회 흐름 완료"다.** FastAPI 상담 준비안, 검색, 화면까지 포함한 전체 MVP 완료와 구분한다.

### 공개 상품 의존성 확인 (사실)

- 중도상환수수료 v1(rule 2개), v2(rule 3개)의 `public_cross_check`는 모두 null이다. 공개 KB 교차 검증 없이 S3~S6을 검증할 수 있다.
- `structured_change.applicable_product_keys`는 `["kb-seller-loan"]` 문자열 배열이며 DB FK가 아니다. S4의 "대상 상품 존재" 검증은 `public_product` 테이블 조회 또는 catalog JSON 중 하나로 할 수 있다. PostgreSQL 유지로 `public_product`가 적재돼 있으므로 TASK-006에서 선택한다.
- 셀러론 공문(보조 사례)은 `internal_notice_reference`가 `public_product`, `public_snapshot`을 FK로 참조한다(V4 56~57행). PostgreSQL 유지로 공개 상품 테이블이 그대로 있어 공문 4개 전부 적재된다. 이전 쟁점 2(S1-b)는 소멸했다.

## 2. Task 분할

역할은 Task별로 정한다. 기본 제안: AI가 조사, 초안, 구현, 자동 검증, self-review를 맡고 사용자가 범위, AC 확정, 제안 판단, 검수, Explainability Gate를 맡는다.

### TASK-001 미커밋 Day 5 자산 보존

정식 문서: [TASK-001](TASK-001_미커밋-Day-5-자산-보존.md). PR #11. PostgreSQL 기준 Pre-SDLC 기준선.

### TASK-002 일회성 migrate 스크립트 DROP 실행

정식 문서: [TASK-002](TASK-002_일회성-migrate-스크립트-DROP-실행.md). TASK-001 병합 뒤 실행.

### TASK-003 Oracle 위험 검증 spike (중단)

정식 문서: [TASK-003](TASK-003_Oracle-위험-검증-spike.md). 중단, 실험 미완료. 환경 호환과 SQL 기능 probe만 확인됐고 핵심 무결성 실험은 실행되지 않았다. 산출물은 보존.

### TASK-004 (취소)

Oracle 전환 1단계. ADR-009 PostgreSQL 유지로 취소. 번호 재사용 금지. 이전 초안의 "한 PR, C1~C4 체크포인트" 전환 경로는 3절에 대체 기록으로 남긴다.

### TASK-005 checklist 변경 후보(proposal) 생성

- 업무 결과: v2 rule과 직전 승인 checklist의 차이가 결정적 proposal로 생성되고 DERIVED로 저장된다. S3.
- 포함: Flyway V6 proposal과 item 테이블(append-only 가드와 `trust_agent_protected_tables()` 갱신, V2 주석의 보호 테이블 추가 절차 준수), 결정적 generator(LLM 미사용, generator version), base checklist와 target notice와 rule 참조, 추가/수정/삭제와 before/after, `supersedes_proposal_id`와 before/after hash, 같은 입력은 같은 canonical 내용. 생성 command(test/demo profile 한정).
- **v1 승인 checklist bootstrap (제안 A 채택):** v1 승인 checklist와 schedule을 합성 fixture로 둔다(V4 테이블 사용). fixture는 test/demo 전용이고 실제 인간 승인 기록이 아님을 데이터와 응답에 명시한다. 실제 승인 경로(S5)의 검증은 TASK-007에서 별도로 포함한다.
- AC 초안: (1) v1 fixture가 있고 v2가 수신된 상태에서 proposal 1건이 생성되고 item에 `prepayment_fee_rate_percent` "1.2" → "0.8", `effective_on` 2026-10-01, `customer_contract_date_check` 추가가 들어 있음. (2) 같은 입력으로 두 번 생성하면 proposal hash가 같고 실행 사건 ID만 다름. (3) 직전 승인 checklist가 없으면 proposal을 만들지 않고 `NO_BASE_CHECKLIST`로 기록. (4) proposal UPDATE/DELETE는 가드 trigger로 거부되고 새 revision만 가능. (5) 응답과 저장에 dataset class DERIVED와 fixture 표시. (6) 기존 82개 테스트 통과 유지.
- 의존: TASK-001 병합.

### TASK-006 자동 검증(validation)

- 업무 결과: proposal에 대해 숫자, 시행일, 대상 상품, 조건 일관성 검증이 PASS/WARN/FAIL과 issue로 append-only 저장된다. MVP 5단계. S4.
- 포함: validation result와 issue 테이블(V7), validator version, proposal hash, `validated_at`, 사용한 evidence ID. 검증 규칙: after 값이 공문 `structured_change`와 일치, `effective_on`이 공문 `effective_from`과 일치, 대상 상품 key가 `public_product`에 존재, 단위 일치, 조건과 예외 누락 없음. 공개 교차 검증은 `public_cross_check`가 있는 항목에만 `PublicEvidenceConfirmationPolicy`로 적용(ADR-008)하고 없으면 `PUBLIC_CROSS_CHECK_NOT_APPLICABLE` 기록. 재검증은 새 결과 추가.
- AC 초안: (1) 정상 proposal은 PASS. (2) after 값을 "0.08"로 바꾼 fixture는 `FAIL VALUE_MISMATCH`. (3) `effective_on`을 2026-10-02로 바꾼 fixture는 `FAIL EFFECTIVE_DATE_MISMATCH`. (4) 대상 상품 오타는 FAIL. (5) `public_cross_check` 없는 항목은 `PUBLIC_CROSS_CHECK_NOT_APPLICABLE`. (6) 셀러론 20억원을 2억원으로 만든 proposal fixture는 `FAIL PUBLIC_FACT_MISMATCH`(ADR-008). (7) 재검증 시 결과 2건이 남고 `validated_at <= knownAt`인 것만 보임.
- 의존: TASK-005.

### TASK-007 사람 검수 결정과 승인 checklist 발행

- 업무 결과: 검수자가 APPROVE, MODIFY, REJECT를 기록하고, APPROVE 시 ApprovedChecklistVersion과 ScheduleRevision/Entry가 생성되어 S6 조회가 `AVAILABLE`, `internalChecklistUseAllowed=true`를 반환한다. S5와 S6. 여기까지가 "Core 승인과 조회 흐름 완료"다.
- 포함: HumanReviewDecision 테이블(V8)과 command(test/demo profile, 합성 actor, production 비활성). FAIL issue, validation 없음, proposal hash 변경 시 APPROVE 거부. `max_validation_age` 경계. WARN 승인 시 사유 필수. MODIFY는 새 revision과 재검증 요구. 승인 시 기존 schedule 복사 + 이전 구간 종료일 제한 + 새 entry 추가한 새 revision(V4 exclusion constraint와 unique가 R-11, R-12 강제). 동시 승인 충돌. applicable 조회의 AVAILABLE 경로와 `InternalChecklistUsePolicy` 호출. v1 fixture 대신 실제 승인 경로로 v1을 승인하는 검증도 포함.
- **분할 여부 (제안 C):** 정식 AC와 크기를 보고 결정한다. 분할하더라도 승인 결정, checklist 발행, schedule revision 발행은 하나의 트랜잭션으로 원자성을 유지한다. 반쪽 승인 상태가 생기지 않는다.
- AC 초안: (1) FAIL이 있는 proposal의 APPROVE 거부. (2) PASS proposal의 APPROVE 뒤 2026-10-01 조회가 v2 checklist를 AVAILABLE로 반환하고 `internalChecklistUseAllowed=true`. (3) 2026-09-30 조회는 v1 checklist 반환. (4) MODIFY는 승인 상태를 만들지 않고 새 revision 생성, 재검증 전 APPROVE 불가. (5) 같은 revision을 동시에 대체하는 두 승인 중 하나만 성공(`supersedes_schedule_revision_id` unique). (6) `validated_at`이 `max_validation_age` 초과 시 `VALIDATION_STALE` 차단. (7) 과거 `knownAt` 조회는 승인 입력 불가. (8) production profile에서 review command bean 없음. (9) 모든 결정이 actor, 사유, 시각, hash와 함께 append-only 저장. (10) 승인 트랜잭션 중간 실패 시 decision, checklist, schedule 어느 것도 남지 않음.
- 의존: TASK-006.

### TASK-008 Core Tool API (AI 서비스용 read-only 조회 경계)

- 업무 결과: FastAPI AI 서비스가 업무 DB에 접근하지 않고 Core의 Tool endpoint로 적용 checklist와 근거를 읽을 수 있다. S7의 Core 쪽. ADR-011 승인 포함. **제안 D 채택: 검색(TASK-009)보다 먼저.**
- 포함: read-only Tool endpoint(applicable checklist, notice rule evidence). 서비스 인증(demo 수준 명시), Tool allowlist, 입력/출력 schema, 최소 데이터(공문 본문 전체 미노출), 감사 로그. 응답에 `internalChecklistUseAllowed`와 blocking reason 포함. 쓰기 경로 없음.
- AC 초안: (1) 인증 없는 호출 401. (2) allowlist 밖 tool 404. (3) 응답 schema가 contract fixture와 일치. (4) Core policy가 계산한 `internalChecklistUseAllowed`가 응답에 포함되고 AI 쪽 재계산에 의존하지 않음. (5) 쓰기 endpoint 없음. (6) 감사 로그에 공문 본문 미포함.
- 의존: TASK-007.

### TASK-009 Elasticsearch 검색 기준선

- 업무 결과: 승인 checklist와 공문 rule 원문이 dataset class metadata와 함께 색인되고, 골든셋 기준 사전 지표를 넘는다. S8. ADR-010 승인 포함.
- 포함: **최종 범위는 CLAUDE.md baseline 전체(BM25 + dense vector k-NN + metadata filter + reranker)다.** BM25 + metadata filter만 쓴 구성은 비교 기준으로 사용한다. 단순 Hybrid와 Ontology-aware(경량 관계 metadata) 비교. PostgreSQL → ES 전달(Outbox 또는 재색인 job)과 멱등성. 미승인, 구버전, 철회 공문 제외 filter. 골든셋과 Recall@5/MRR 사전 정의. compose에 ES 추가.
- **범위 축소 규칙:** dense k-NN이나 reranker를 최종 범위에서 빼려면 같은 골든셋의 평가 결과와 대안을 제시하고 별도 인간 판단을 받는다.
- AC 초안: (1) 승인된 checklist만 검색 결과에 포함되고 구버전은 filter로 제외. (2) 골든셋 질의에서 v2 rule이 상위 5위 안. (3) 재색인 두 번 실행 시 문서 수 불변. (4) 색인 문서에 dataset_class와 synthetic 표시. (5) 비교 기준과 baseline 전체 구성의 지표 비교표와 인간 선택 기록.
- 의존: TASK-007. TASK-008 뒤.

### TASK-010 (취소)

공개 상품 테이블 Oracle 이식. PostgreSQL 유지로 불필요. 번호 재사용 금지.

### TASK-011 대표 E2E와 evidence

- 업무 결과: S1~S6를 한 번에 재현하는 E2E 테스트와 evidence. **"Core 승인과 조회 흐름 완료"의 근거이며 전체 MVP 완료가 아니다.** README의 MVP 4~6단계와 8~9단계 중 Core 범위 완료 표시 근거.
- 의존: TASK-007. TASK-008 결과는 선택 포함.

## 3. 대체된 계획: Oracle 전환 경로 (기록 보존)

이전 초안 3절은 TASK-004를 한 PR과 C1~C4 체크포인트로 진행하는 전환 경로를 제안했고 사용자가 채택했다. ADR-009 PostgreSQL 유지 결정으로 **대체됐다.** 전환 경로, 공개 기능 비활성 계약, 공개 상품 통합 테스트의 비활성 계약 전환, 공문 4개 → 2개 적재 범위 변경(S1-b)은 모두 실행되지 않았고 필요 없다. 기존 82개 Java 테스트는 그대로 유지한다.

## 4. 의존 순서

```text
audit 판정 ─► TASK-001 보존 (PR #11) ─► TASK-002 DROP 실행 (병합 뒤)
                 │
              TASK-005 proposal (V6, v1 fixture)
                 │
              TASK-006 validation (V7, 공개 교차 검증 포함)
                 │
              TASK-007 사람 결정 + 발행 (V8, 원자성) ─► TASK-011 E2E ("Core 승인과 조회 흐름 완료")
                 │
              TASK-008 Core Tool API (ADR-011)
                 │
              TASK-009 Elasticsearch (ADR-010, baseline 전체)
                 │
          AI 서비스 Task (FastAPI 소비, 상담 준비안, 이 계획 밖)

TASK-003 중단(보존), TASK-004 취소, TASK-010 취소
```

- critical path: 001 → 005 → 006 → 007. 전부 기존 PostgreSQL schema 위에서 Flyway migration을 추가하는 방식이다.
- 각 migration은 V2 주석의 보호 테이블 추가 절차(테이블 생성, append-only 가드, TRUNCATE 가드, `trust_agent_protected_tables()` 갱신, OWNER 변경)를 따르고 `PublicProductSchemaIntegrationTest`의 가드 개수 단언을 갱신한다.
- TASK-008은 TASK-009보다 먼저다(제안 D).

## 5. AI 제안 및 인간 판단 기록

결정자: 사용자 / 검토 대상: PR #10 브랜치

| 제안 ID | 내용 | 판단 | 이유 / 승인 범위 |
|---|---|---|---|
| 제안 A | v1 승인 checklist와 schedule을 합성 fixture로 bootstrap | **채택 + 조건** | test/demo 전용이고 실제 인간 승인 기록이 아님을 명시. 실제 승인 경로 검증은 TASK-007에 별도 포함 |
| 제안 B | R-12 기간 중첩과 동시성 spike 선행 | **대체됨** | Oracle 전환 전제. PostgreSQL exclusion constraint가 이미 R-12를 강제하고 `SyntheticInternalSchemaIntegrationTest`가 검증 |
| 제안 C | TASK-007 분할 여부 | **보류 (AC 작성 시 결정)** | 승인 결정, checklist와 schedule 발행의 원자성 유지 |
| 제안 D | Core Tool API를 검색보다 먼저 | **채택** | FastAPI 소비 흐름을 후속 Task로 연결 |
| 수정 지시 1 | 구 TASK-002 충돌과 전환 경로 | **대체됨** | TASK-004 취소로 소멸. 3절에 기록 보존 |
| 수정 지시 2 | TASK-009 범위 | **반영** | baseline 전체 유지. 축소는 평가 결과와 별도 판단 |
| 수정 지시 3 | TASK-011 표현 | **반영** | "Core 승인과 조회 흐름 완료" |
| 수정 지시 4 | 공개 상품 의존성 확인 | **확인 완료** | 중도상환수수료는 비의존. 셀러론 FK 의존은 PostgreSQL 유지로 문제 없음 |
| 실행 승인 | TASK-001, TASK-002 | **승인** | 분리된 PR. 병합은 사용자 |
| 실행 승인 | TASK-003 착수, TASK-004 | **철회 / 취소** | ADR-009 PostgreSQL 유지 |
| 전환 경로 | TASK-004 한 PR, C1~C4 | **대체됨** | 3절 |

인간 검수와 Explainability Gate: **미기록.**

## 6. 이 계획이 하지 않는 것

- TASK-005 이후의 정식 Acceptance Criteria 고정. 각 Task 승인 시 작성.
- 일정 추정.
- FastAPI AI 서비스, LangGraph 적용 범위, 화면.
- TASK-005~011 구현 승인. 각 Task 착수는 별도 승인이다.
