# Core Service

Spring Boot 기반 핵심 서비스입니다. 애플리케이션 골격과 append-only 감사 저장 기반,
공개 상품 baseline importer, 공개 상품 관측 상태 조회와 freshness 및 confirmation policy,
합성 공문 적재와 적용 공문 기준일 조회를 구현했습니다.
Flyway V5부터는 최종 기획서의 대표 사례인 중도상환수수료 변경 전후, 시행일, 조건과
예외를 `structuredChange`로 보존하고 적용 공문 조회 API에서 반환합니다. Flyway V6와 checklist 변경안
생성, Flyway V7과 변경안 자동 검증, Flyway V8과 사람 검토 결정·승인 checklist 발행(아래)을 구현했습니다. 자동 검증 통과는 사용 허용이 아니며 사람 승인 기록이 있는 checklist만 사용 허용 후보입니다. 인증과 화면은 구현하지 않았습니다. 일반 서버 기동 중에는
baseline importer bean을 만들거나 데이터를 자동 적재하지 않습니다.

Core 업무 DB는 PostgreSQL이며(ADR-009) 이 README의 구현 설명은 PostgreSQL 18.6 기준입니다.

## 사용자 인증·역할·보안 사건 (TASK-017a 첫 PR, ADR-014)

조회 API(`/api/v1/internal-policy/**`, `/api/v1/public-products/**`)와 세션 API는 Spring Security 세션 로그인이 필요합니다. 비인증 요청은 HTML 로그인 페이지나 리다이렉트가 아니라 `401 application/problem+json`(`code: UNAUTHENTICATED`)입니다. Tool 경로(`/api/v1/tools/**`)와 기록 경로(`/api/v1/consultation-preparations`)는 세션·CSRF 없이 기존 서비스 토큰 필터만으로 동작합니다(stateless). actuator도 세션이 필요 없습니다.

```text
POST /login                      form: username, password (+ 헤더 X-XSRF-TOKEN) → 200 {principal, roles, activeRole, workspaceId}
POST /logout                     → 204
GET  /api/v1/session             → 세션 요약
POST /api/v1/session/active-role {"role":"STAFF"|"REVIEWER"} → 보유하지 않은 역할 403 ROLE_NOT_HELD
GET  /api/v1/reviews/proposals   REVIEWER 활성만. 변경안 목록(읽기)
```

역할은 STAFF와 REVIEWER 두 개입니다. 경로마다 **보유 역할**과 **활성 역할**을 둘 다 검사하며, 전환은 재로그인 없이 서버 호출이고 사건으로 기록됩니다. 사건 기록에 실패하면 전환되지 않고 503입니다(감사 없는 전환 없음). 겸임 계정의 역할 전환은 체험을 위한 단순화이며 실제 금융기관의 직무 분리와 다릅니다. CSRF는 쿠키 토큰 방식(`XSRF-TOKEN` 쿠키 값을 `X-XSRF-TOKEN` 헤더로)이고, 세션 쿠키는 HttpOnly·SameSite=Lax이며 Secure는 `TRUST_AGENT_SESSION_COOKIE_SECURE`(prod 기본 true), 유휴 만료는 `TRUST_AGENT_SESSION_IDLE_TIMEOUT`(기본 8h)입니다.

**필터 순서.** Tool·기록 토큰 필터는 `@Order(HIGHEST_PRECEDENCE + 20·21)`로 Spring Security 필터 체인(순서 -100)보다 먼저 실행됩니다. 보안 체인의 permitAll은 세션 검사를 생략한다는 뜻일 뿐이며 토큰 없는 호출은 그 전에 401로 끝납니다(세션이 있어도 토큰을 대신하지 못함을 테스트가 확인).

**제어 DB.** 사용자·역할·보안 사건은 업무 DB와 분리된 제어 DB(`TRUST_AGENT_CONTROL_DB_URL/USERNAME/PASSWORD`, 별도 Flyway history `flyway_control_schema_history`, migration `db/control`)에 둡니다. 로컬 기본값은 업무 DB 설정으로 대체되지만 이는 **개발 편의용**이며 운영 환경의 분리·격리 검증을 대신하지 않습니다. `prod` profile은 세 설정을 요구하고, 호스트·포트·데이터베이스 이름을 정규화해 업무 DB와 같은 DB를 가리키면(`CONTROL_DB_NOT_SEPARATED`) 또는 같은 계정이면(`CONTROL_DB_ACCOUNT_NOT_SEPARATED`) 기동을 거부합니다. 체험 공간 템플릿(TASK-026)은 제어 표가 없는 업무 DB에서 만들어야 하며 로컬 단일 DB를 템플릿으로 쓰지 않습니다. `security_event`는 append-only이며 본문·토큰·비밀번호를 기록하지 않습니다.

**합성 직원 3명(demo 전용, production 기동 거부).** `SYN-STAFF-01`(STAFF), `SYN-REVIEWER-01`(REVIEWER), `SYN-STAFF-REVIEWER-01`(겸임). 비밀번호는 환경변수 `TRUST_AGENT_DEMO_PASSWORD_SYN_STAFF_01` 등으로만 받아 bcrypt 해시로 저장합니다. 가입·재설정·관리 화면은 없습니다.

```bash
TRUST_AGENT_DEMO_PASSWORD_SYN_STAFF_01='<값>' TRUST_AGENT_DEMO_PASSWORD_SYN_REVIEWER_01='<값>' TRUST_AGENT_DEMO_PASSWORD_SYN_STAFF_REVIEWER_01='<값>' \
TRUST_AGENT_CONTROL_FLYWAY_ENABLED=true ./gradlew :apps:core-service:bootRun --no-daemon \
  --args='--spring.main.web-application-type=none --trust-agent.demo-users.enabled=true'
```

아직 없는 것: 상담 건 표와 객체 권한 검사, AI 요청 승인(grant), Core → AI 서비스 호출(TASK-017a 두 번째 PR), 화면(017b), 체험 코드·workspace 복제(026). 이 PR의 인증은 합성 계정 기반이며 실제 행원 인증이 아닙니다.

## 합성 내부 공문 기준일 조회

```text
GET /api/v1/internal-policy/checklists/{familyId}/applicable
    ?businessDate=YYYY-MM-DD
    &knownAt=RFC3339 instant
```

최종 기획서 대표 family는 `SIN-PREPAYMENT-FEE`입니다. 응답은 합성 고지, 선택된 공문,
구조화 규칙과 원문 JSON Pointer 근거를 포함합니다. 검증과 사람 승인 전에는
`internalChecklistUseAllowed=false`로 유지합니다.

## checklist 변경안 생성 (test/demo 전용)

새 공문의 구조화 규칙과 직전 승인 checklist를 비교해 변경안을 만들어 저장합니다. 승인이 아니며 적용 공문 조회의
사용 허용 판단을 바꾸지 않습니다. production profile에서는 아래 두 설정 중 하나라도 켜면 기동을 거부합니다.

1. 테스트용 승인 checklist 예시 데이터 적재(출처 `FIXTURE`, 실제 승인 기록 아님). 같은 공문군에 사람 검토 승인
   checklist가 있으면 거부하고, 같은 ID의 다른 내용은 거부하며, 재실행은 멱등입니다.

```bash
./gradlew :apps:core-service:bootRun --offline --no-daemon \
  --args='--spring.main.web-application-type=none --trust-agent.fixture-approved-checklist.enabled=true --trust-agent.fixture-approved-checklist.root=/absolute/path/to/trust-agent'
```

2. 변경안 생성. 대상 공문 시행일 전날에 적용되는 승인 checklist가 없으면 `NO_BASE_CHECKLIST`로 실패를 기록합니다.

```bash
./gradlew :apps:core-service:bootRun --offline --no-daemon \
  --args='--spring.main.web-application-type=none --trust-agent.proposal-generation.enabled=true --trust-agent.proposal-generation.family-id=SIN-PREPAYMENT-FEE --trust-agent.proposal-generation.target-notice-id=SIN-PREPAYMENT-FEE-V2'
```

결과는 `checklist_change_proposal`, `checklist_change_proposal_item`, `proposal_generation_run` 테이블에 append-only로 남습니다.

3. 변경안 자동 검증. 변경안을 공문 원문 규칙, 기준 checklist, 공개 상품 근거와 대조해 PASS/WARN/FAIL과 세부 오류(issue)를
   한 트랜잭션으로 저장합니다. FAIL 결과에 FAIL issue가 없거나 WARN 결과에 WARN issue가 없으면 DB가 저장을 거부합니다.
   공개 근거 교차 검증은 공개 상품 관측 상태 조회와 같은 freshness 정책(`trust-agent.public-evidence.max-confirmation-age`)을 씁니다.
   검수자가 설명 문구만 고친 revision은 WARN `INSTRUCTION_EDITED`(원문 문구와 고친 문구 저장)이고, 규칙 키·변경 전후 값·단위·시행일·대상 상품·조건·예외·근거 필요 여부가
   다르면 FAIL `VALUE_MISMATCH`(세부 `mismatched_fields`)입니다. 자동 검증은 문구의 의미를 보장하지 않으므로 검수자가 원문과 대조하고 승인 사유를 남깁니다.

```bash
./gradlew :apps:core-service:bootRun --offline --no-daemon \
  --args='--spring.main.web-application-type=none --trust-agent.proposal-validation.enabled=true --trust-agent.proposal-validation.proposal-id=checklist-proposal:sha256:<64 hex>'
```

결과는 `automated_validation_result`, `automated_validation_issue`, `validation_run` 테이블에 append-only로 남습니다. 적용 공문 조회는
`knownAt`까지 보이는 최신 변경안 revision의 최신 결과로 `checklistAvailabilityStatus`를 정합니다(결과 없음 `PENDING_VALIDATION`,
FAIL `VALIDATION_FAILED`, `trust-agent.validation-policy.max-validation-age` 초과 `VALIDATION_STALE`, PASS/WARN `PENDING_REVIEW`)
그리고 사용한 결과를 `validatedProposalId`, `validationResultId`로 돌려줍니다(없으면 null). 어떤 결과도 `internalChecklistUseAllowed`를
true로 만들지 않습니다. production profile은 `TRUST_AGENT_VALIDATION_MAX_AGE`(0보다 큰 기간)와 `TRUST_AGENT_VALIDATION_POLICY_VERSION`을 요구합니다.

4. 사람 검토 결정. 승인(APPROVE)은 결정 기록, HUMAN_REVIEW 출처 승인 checklist(기준 checklist에 변경안을 적용한 항목), 적용 일정 revision(직전 구간 종료일 제한 + 새 구간)을
   한 트랜잭션으로 저장합니다. 수정(MODIFY)은 고친 내용으로 새 변경안 revision만 만들고(재검증 필요), 반려(REJECT)는 결정만 남깁니다. 승인은 최신 검증 결과가
   PASS 또는 WARN(사유 필수)이고 결정 시점 기준 유효 기간 안이며 변경안 내용 해시가 같을 때만 됩니다. 검수자 ID는 합성 값입니다.

```bash
./gradlew :apps:core-service:bootRun --offline --no-daemon \
  --args='--spring.main.web-application-type=none --trust-agent.human-review.enabled=true --trust-agent.human-review.proposal-id=checklist-proposal:sha256:<64 hex> --trust-agent.human-review.decision=APPROVE --trust-agent.human-review.reviewer-id=SYN-REVIEWER-01'
```

수정은 `--trust-agent.human-review.decision=MODIFY --trust-agent.human-review.reason=<사유> --trust-agent.human-review.revised-rules-json=<규칙 JSON 배열>`, 반려는
`--trust-agent.human-review.decision=REJECT --trust-agent.human-review.reason=<사유>`를 씁니다. 결과는 `human_review_decision`, `human_review_run` 테이블과
기존 승인 checklist·일정 테이블에 append-only로 남습니다. 적용 공문 조회는 사람 결정이 있는 HUMAN_REVIEW checklist에만 사용 허용을 주고
(`approvedChecklist.origin`, `approvedChecklist.decisionId`, 항목 목록 `approvedChecklist.items`), 테스트용 출처는 `FIXTURE_CHECKLIST_NOT_APPROVED`, 결정 없는 HUMAN_REVIEW는 `HUMAN_DECISION_MISSING`,
반려된 변경안은 `UNAVAILABLE` + `PROPOSAL_REJECTED`, 필수 공개 근거 미확인은 `PUBLIC_EVIDENCE_UNCONFIRMED`, 일정 구간은 덮지만 다른 공문용 checklist면
`APPROVED_CHECKLIST_NOTICE_MISMATCH`로 차단합니다. 승인 뒤 검증 결과의 기간 경과만으로는 만료되지 않습니다.

## AI 서비스용 Tool API (읽기 전용, test/demo 인증)

```text
POST /api/v1/tools/applicable_checklist   본문 {"familyId": "...", "businessDate": "YYYY-MM-DD"(생략 시 오늘), "consultationId": "..."(추적용)}
POST /api/v1/tools/rule_evidence          본문 {"familyId": "...", "ruleVersionId": "policy-rule:sha256:...", "consultationId": "..."}
헤더 Authorization: Bearer <TRUST_AGENT_TOOL_SERVICE_TOKEN>
```

사용 허용 여부(`usable`)는 Core의 적용 공문 조회가 결정하며 AI가 바꿀 수 없습니다. `usable=false`면 사유만 주고 `approvedChecklist`는 null입니다(항목과 근거 ID 없음).
근거 Tool은 요청한 공문군의 현재 사용 가능한 승인 checklist 항목의 근거일 때만 원문 문장과 위치를 주고 아니면 403입니다. `knownAt`과 모르는 필드는 400이며 조회는 항상 현재 시각 기준입니다.
쓰기 Tool은 없습니다. 모든 호출(인증 거부 포함)은 `tool_call_audit`에 결과 코드와 사유만 남기고 저장 실패 시 응답도 실패합니다.
토큰은 환경변수 `TRUST_AGENT_TOOL_SERVICE_TOKEN`으로만 제공하고 비어 있으면 모든 호출을 거부합니다(production은 기동 거부). 사용자별 인증·권한은 미구현입니다. FastAPI AI 서비스는 TASK-015 범위(규칙 조립 준비안·보류)까지 구현됐고 아래 준비안 기록 경로를 씁니다. 계약은 `contracts/tool-*.schema.json`.

## AI 서비스 준비안 기록 경로 (Tool 밖, 기록 토큰, TASK-015·ADR-012)

```text
POST /api/v1/consultation-preparations   본문 contracts/consultation-preparation-record.schema.json (snake_case. 근거 원문·메모·토큰 없음)
헤더 Authorization: Bearer <TRUST_AGENT_PREPARATION_RECORD_TOKEN>
201 {"preparationId","runId","status":"RECORDED","recordedAt"} / 200 "ALREADY_RECORDED" / 400·401·409·422·500 problem JSON(code, detail)
```

AI 서비스가 규칙으로 조립한 상담 준비안(READY·PARTIAL·HOLD 모두)을 남기는 경로입니다. 읽기 Tool 토큰과 별개의 **기록 토큰**만 받으며 두 토큰은 서로 바꿔 쓸 수 없습니다(Tool 경로에 기록 토큰, 기록 경로에 Tool 토큰은 401). 쓰는 표는 `consultation_preparation`, `consultation_preparation_section`, `consultation_preparation_run` 세 표뿐이고 승인·일정·변경안·검증·공문 사건 표는 바꾸지 않습니다.
저장 전에 Core가 직접 다시 확인합니다. 활성 매핑과 요청의 `family_mapping_hash`·필수 섹션 유무·`required` 값·전체 상태 계산(M1~M5, 필수 공문군이 없으면 READY 불가 `NO_REQUIRED_FAMILY`), READY 섹션의 선택 공문·승인 version·결정 ID·항목 순서·근거 해시를 현재 시각의 적용 공문 조회와 대조(C1~C5. 사용 불가면 422 `PREPARATION_NOT_USABLE`, 값이 다르면 409 `PREPARATION_STALE`), HOLD 섹션은 항목·근거가 없어야 하고 사유 코드가 허용 목록에 있어야 합니다(400 `HOLD_SECTION_INVALID`). HOLD 섹션은 AI가 보낸 사유와 Core가 지금 본 결과(`recheck_usable`, `recheck_reasons`)를 둘 다 저장하며 `hold_claim_basis`가 CORE_REPORTED(Core 판정)인지 SERVICE_REPORTED(통신 오류 등 서비스 보고)인지 구분합니다.
`preparation_id`는 본문에서 `preparation_id`·`run_id`·`consultation_id`·`sections[].evaluated_at`·`sections[].tool_response_hash`를 뺀 canonical sha256이어야 하며(아니면 400 `PREPARATION_ID_MISMATCH`), 같은 ID 같은 내용은 재확인을 거친 뒤 200 `ALREADY_RECORDED`(저장만 멱등. **같은 ID 재요청에서도 재확인은 실행되며, 동시 요청의 저장 충돌 뒤에도 새 트랜잭션에서 재확인한 뒤에만 기존 기록을 돌려줍니다**), 같은 ID 다른 내용은 409 `PREPARATION_CONFLICT`입니다. 재확인과 저장은 SERIALIZABLE 트랜잭션 하나이며 직렬화 실패(40001)는 재확인부터 1회 재시도하고 그래도 실패하면 500 `SERIALIZATION_FAILED`입니다. 요청마다 실행 기록(`run_id`, RECORDED/ALREADY_RECORDED/REJECTED/FAILED)이 별도 트랜잭션으로 1건 남고 같은 `run_id` 재전송은 409 `RUN_ID_CONFLICT`입니다. 실행마다 달라지는 값(섹션별 평가 시각·Tool 응답 해시·이번 재확인 결과)은 실행 기록의 `section_evaluations`에 실행 단위로 쌓이며 준비안·섹션 행은 첫 기록 그대로입니다.
**기록은 사용 허가가 아닙니다.** 저장된 준비안을 쓰기 전에는 적용 공문 조회로 사용 가능 여부를 다시 확인해야 합니다. 토큰은 환경변수 `TRUST_AGENT_PREPARATION_RECORD_TOKEN`으로만 제공하고 비어 있으면 모든 기록을 거부합니다(production은 기동 거부). 읽기 토큰 `TRUST_AGENT_TOOL_SERVICE_TOKEN`과 같은 값이면 모든 profile에서 기동을 거부합니다(`SERVICE_TOKEN_NOT_SEPARATED`, 오류에 토큰 값은 나오지 않음).

상품별 공문군 매핑(어떤 공문군이 필수인지)은 기록 경로가 아니라 별도 적재 경로로만 들어갑니다. 설정 `trust-agent.consultation-family-mapping.enabled=true`와 `trust-agent.consultation-family-mapping.root=<저장소 루트>`로 기동하면 `datasets/synthetic/work/consultation-family-mapping.json`(현재는 합성 시나리오의 사용자 승인 설정)을 검증해 `consultation_family_mapping`에 적재합니다. 같은 내용(해시)은 다시 넣지 않고, 가장 최근 적재된 해시가 활성 매핑입니다. 매핑 변경은 파일 버전을 올리고 Task에 기록합니다.

## 근거 검색 색인 재색인 (TASK-016, ADR-013)

승인된 근거만 Elasticsearch에 색인합니다. 색인 문서는 사람 결정(APPROVE)이 있는 승인 checklist의 항목이 가리키는 규칙 version 하나당 하나이며, 그 checklist가 공문군의 최신 적용 일정에 있고 공문이 철회되지 않았을 때만 들어갑니다. FIXTURE 출처 checklist, 미승인·반려 변경안, 철회 공문의 규칙은 색인에 없습니다. 문서 필드는 Tool 1·2가 내주는 범위(규칙 문장·위치·해시, 구조화 값 문장화, 공문군·공문·승인 checklist·결정 ID, 시행 기간)를 넘지 않고 공문 본문 전체는 넣지 않습니다. 색인은 업무 원장이 아니며 검색 결과는 항상 Core Tool로 다시 확인합니다.

```bash
TRUST_AGENT_ES_URL=http://127.0.0.1:9200 \
TRUST_AGENT_ES_REINDEX_USERNAME=trustagent_reindex TRUST_AGENT_ES_REINDEX_PASSWORD='<재색인 비밀번호>' \
./gradlew :apps:core-service:bootRun --no-daemon \
  --args='--spring.main.web-application-type=none --trust-agent.search-reindex.enabled=true'
```

재색인은 새 색인 `trustagent-rule-evidence-<workspace>-<내용 해시 12자>`를 만들고 alias `trustagent-rule-evidence-<workspace>-current`를 바꾼 뒤 옛 색인을 지웁니다. 내용 해시와 설정 해시(분석기·mapping)가 같은 색인이 이미 alias를 가리키고 문서 수가 맞으면 아무것도 만들지 않습니다(두 번 실행해도 문서 수와 해시 불변). 분석기를 바꾸면 새 색인입니다. alias가 가리키는 현재 색인은 새 색인의 준비·검증·전환 전에는 지우지 않으며, 실패하면 alias를 바꾸지 않아 옛 색인이 그대로 남습니다. 분석기는 `TRUST_AGENT_ES_ANALYZER`(standard 기본, nori는 플러그인 필요)로 고르며 초기 점검용 자료로 비교해 확정합니다. ES 구성은 `infra/docker/compose.search.yml`, 사용자 분리는 `infra/docker/search/init-users.sh`입니다.

**한계(수동 재색인).** 사람 결정이나 철회 적재 뒤 runner를 실행하기 전까지 새로 승인된 규정은 검색 후보에 없고, 철회된 규정은 색인에 남아 있을 수 있습니다. 남아 있는 쪽은 Core 재확인이 막지만 새 규정의 누락은 막지 못합니다. 승인 체험(TASK-027)은 승인 뒤 색인 갱신까지 검증합니다. 자동 전달은 별도 ADR입니다.

### 검색 평가 하네스 (CI 미포함)

`SearchEvaluationRunner`는 PostgreSQL·Elasticsearch Testcontainer, 고정 시계 2026-10-06T03:00:00Z, TASK-015 상태, 재색인, AI 서비스 HTTP(진단 모드)를 띄우고 `scripts/evaluate_search.py`를 실행합니다. 초기 점검용 10건을 보류 없음으로 돌려 세 방식을 비교·선택한 뒤 그 값을 고정해 초기 점검용과 최종 평가용 27건을 평가합니다. 결과는 `apps/core-service/build/reports/search-evaluation/<분석기>/`에 남습니다.

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :apps:core-service:test --tests '*SearchEvaluationRunner' \
  -PsearchEvaluation=true -PaiServiceIntegration=true [-PsearchAnalyzer=standard|nori] [-PsearchHoldVersion=bm25-hold-v1] --no-daemon
```

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
`docs/evidence/CORE_SERVICE_SCHEMA_AUDIT_EVIDENCE.md`, `docs/evidence/PUBLIC_PRODUCT_BASELINE_IMPORTER_EVIDENCE.md`와
`docs/evidence/PUBLIC_PRODUCT_OBSERVED_STATE_EVIDENCE.md`에 기록합니다.

Management endpoint는 기본적으로 `127.0.0.1:8081`에 별도로 열립니다. 배포 환경에서
address를 바꿀 때는 외부 ingress에 노출하지 않고 내부 probe와 운영자 경로만 허용해야
합니다. `prod` profile은 DB URL, username, password, 기대 schema version,
freshness policy version과 max confirmation age가 모두 명시되지 않으면 기동하지
않습니다.
