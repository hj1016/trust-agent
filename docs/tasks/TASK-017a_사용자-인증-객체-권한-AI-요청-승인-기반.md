# TASK-017a 사용자 인증·객체 권한·AI 요청 승인(grant) 기반

- 상태: **계획 승인**, 첫 구현 PR 완료·검수 대기(PR #48), 두 번째 PR(상담 건·grant, 신청·기업 자료 Core 적재 A안) 착수 승인 (결정자 사용자, PR #47. 채택: 제어 DB 별도 데이터베이스, 합성 사용자 3명, 보유·활성 역할 모두 검사, 담당자 아니면 404와 내부 감사, 읽기 상한 50 설정값, Core가 사용자·상담 건·workspace 범위 소유, AI 서비스는 서비스 토큰과 grant 함께 검증, cloud/prod에서 require-grant 강제, TASK-015 흐름·계약 보존. 이전 상태: 합성 사용자 3명(STAFF, REVIEWER, 겸임) 구성, 역할 보유와 활성 역할 검사 구분, 상담 건별 권한과 AI grant 검증 포함. ADR-014는 보완 2건 반영 뒤 승인 대기(PR #45). 구현 착수는 계획·ADR 검수 뒤 별도 승인)
- 담당자 / 인간 결정자: AI 계획·구현·검증 / 사용자 범위·판정·검수
- 요구사항 출처: ADR-014(초안), 사용자 지시(브라우저 → Core → FastAPI 단일 진입, Spring Security 세션, 역할별 권한, 상담 건별 객체 권한, AI 요청 Grant에 workspace 범위, 서비스 토큰 우회 방지, 합성 직원으로 STAFF·REVIEWER를 각각 검증하되 불필요한 사용자 관리 기능 금지, TASK-017을 017a·017b·017c로 분할), DEVELOPMENT_RULES(API/Tool마다 권한 검사, 클라이언트 actor 불신, fail-closed), ADR-011·012
- 관련 Task / ADR: TASK-015(완료), TASK-017b(화면·최종 확인 기록, 이 Task 뒤), TASK-017c(검색 패널), TASK-026(체험 코드·workspace 복제, 이 기반 위), TASK-027(검수자 HTTP 경로), ADR-013·014

## Goal / 관련 요구사항

- Goal: 비인증 업무 API 접근, 상담 ID 변경 조회, 역할 우회, 서비스 토큰 유출을 통한 범위 밖 조회·기록을 Core가 막는 기반을 만든다. 화면 없이 HTTP와 테스트로 검증한다.
- 사용자: 합성 직원(STAFF, REVIEWER), 후속 Task 구현자
- 사전조건: TASK-015 상태, 제어 DB용 PostgreSQL 데이터베이스
- 입력: 로그인(사용자 ID, 비밀번호), 상담 건 생성(신청 ID), 준비안 요청(상담 ID, 업무일)
- 업무규칙: ADR-014 결정 1~10항. 요약: 세션 쿠키(HttpOnly, Secure, SameSite=Lax), CSRF 쿠키 토큰, 역할 STAFF·REVIEWER, 활성 역할 서버 관리, 상담 건 담당자 검사, grant 발급·검사·사용 처리, require-grant 모드, AI 서비스 수신 토큰.
- 상태전이: 상담 건 `OPEN`만 둔다(종료·재배정은 범위 밖). grant `ISSUED → CONSUMED | EXPIRED`.
- 데이터 영향: 제어 DB(신규, 별도 Flyway history): `app_user`, `app_user_role`, `security_event`(첫 PR, V1), `ai_request_grant`, `ai_request_grant_use`(두 번째 PR, V2). 업무 DB(두 번째 PR, V11): `synthetic_work_application`·`synthetic_work_company`(기존 합성 JSON을 bootstrap runner가 적재, append-only, `source_hash` 포함. A안)와 `consultation`(append-only, 신청 FK). 기존 표 변경 없음.
- API: 아래 "API".
- 트랜잭션: grant 발급은 제어 DB 짧은 트랜잭션. 기록 경로의 grant 사용 처리는 제어 DB `CONSUMING` → 업무 DB 기록 트랜잭션(기존) → 제어 DB `CONSUMED`. 외부 호출(AI 서비스)은 트랜잭션 밖.
- 권한: 경로별 역할 요구 표(아래). Tool·기록 경로는 서비스 토큰 + grant.
- 실패 시나리오: 아래 "grant 수용 기준"과 "실패 처리".
- Out of Scope: 화면·정적 자원(017b), 최종 확인 기록(017b), 체험 코드·workspace 복제·라우팅 DataSource의 다중 대상(026), 검수자 쓰기 경로(027), 가입·비밀번호 재설정·관리 화면·재배정, OIDC 연동, 요청 제한(프록시, TASK-023), AI 서비스 HTTP 진입점의 사용자 인증(받지 않음, 수신 토큰만).

## 요구사항과 범위

업무 문제: 현재 Core의 조회 경로에 사용자 인증이 없고, AI 서비스는 서비스 토큰만으로 모든 공문군을 읽고 임의 신청 건을 기록할 수 있다. 화면(017b)과 체험(026)은 이 기반 없이는 재작업이 된다.
포함 범위: Spring Security 도입, 제어 DB와 Flyway 분리, 합성 사용자 적재 runner, 상담 건 표와 API, 활성 역할, grant 발급·검사·사용·감사, Core → AI 서비스 호출 경로(준비안 1개), AI 서비스 수신 토큰과 grant 헤더 전달, 기존 조회 경로 인증, workspace 컨텍스트(기본 `main`), 테스트와 문서.
제외 범위: 위 Out of Scope.
기존 자산: `ToolAuthenticationFilter`, `PreparationRecordAuthenticationFilter`(grant 검사 추가), `RequestTraceFilter`, `tool_call_audit`(변경 없음, grant 사용은 별도 표), AI 서비스 `CoreClient`(헤더 추가), `app.py`(수신 토큰).

## 설계

### 합성 사용자 (불필요한 관리 기능 없음)

| 사용자 ID | 역할 | 용도 |
|---|---|---|
| `SYN-STAFF-01` | STAFF | 행원 경로 검증 |
| `SYN-REVIEWER-01` | REVIEWER | 검수자 경로 검증(TASK-007 검수자 ID와 같은 값) |
| `SYN-STAFF-REVIEWER-01` | STAFF, REVIEWER | 활성 역할 전환 검증(ADR-014 6항) |

적재는 runner `--trust-agent.demo-users.enabled=true`가 환경변수 `TRUST_AGENT_DEMO_PASSWORD_<사용자>`의 값을 bcrypt로 저장한다. 비밀번호 값은 파일·로그·테스트 코드에 없고 테스트는 실행 중 생성한 임시 값을 쓴다. 가입·재설정·관리 API는 없다.

### workspace 컨텍스트

세션 속성 `workspace_id`(이 Task에서는 항상 `main`)와 요청 범위 `WorkspaceContext`. 업무 DataSource는 라우팅 DataSource로 감싸되 대상은 `main` 하나다. TASK-026이 대상을 늘린다. grant 행도 `workspace_id`를 가진다.

### API

| 경로 | 역할 | 설명 |
|---|---|---|
| `POST /login`, `POST /logout` | 공개 | Spring Security form 처리. 로그인 성공 시 세션 ID 재발급 |
| `GET /api/v1/session` | 인증 | principal, 보유 역할, 활성 역할, workspace |
| `POST /api/v1/session/active-role` | 인증 | `{"role": "STAFF"\|"REVIEWER"}`. 보유하지 않은 역할 403. 전환은 `security_event`에 기록 |
| `GET /api/v1/consultations` | STAFF 활성 | 내 상담 건 목록 |
| `POST /api/v1/consultations` | STAFF 활성 | `{"applicationId"}` → 담당자 = principal. 신청은 업무 DB `synthetic_work_application`에 등록된 것만 허용(없으면 422 `APPLICATION_NOT_REGISTERED`), 상품 키로 허용 공문군을 매핑에서 뽑는다(A안) |
| `GET /api/v1/consultations/{id}` | STAFF 활성, 담당자 | 아니면 404(존재 노출 방지) |
| `POST /api/v1/consultations/{id}/preparation` | STAFF 활성, 담당자 | grant 발급 → AI 서비스 호출 → 준비안 응답 그대로 반환(HTTP 상태 매핑은 TASK-015 표 유지) |
| `GET /api/v1/internal-policy/checklists/{familyId}/applicable` | STAFF 또는 REVIEWER 활성 | 기존 경로에 인증 추가 |
| `GET /api/v1/public-products/{productKey}/observed-state` | STAFF 또는 REVIEWER 활성 | 기존 경로에 인증 추가 |
| `GET /api/v1/reviews/proposals` | REVIEWER 활성 | 변경안 목록(읽기). 역할 분리 검증용 최소 경로. 쓰기 경로는 TASK-027 |
| `POST /api/v1/tools/{toolName}` | 서비스 토큰 + grant(require-grant=true) | 기존 Tool |
| `POST /api/v1/consultation-preparations` | 기록 토큰 + grant | 기존 기록 경로 |
| AI 서비스 `POST /api/v1/ai/consultation-preparations` | 수신 토큰 | 본문에 `grantId` 추가(선택. 있으면 Tool·기록 호출에 헤더로 전달) |

### grant

발급: Core가 상담 건 검사 뒤 `ai_request_grant`(grant_id 32 hex, workspace_id, consultation_id, application_id, allowed_family_ids(승인된 매핑에서 상품의 공문군), business_date, user_id, active_role, issued_at, ttl_seconds(설정 `trust-agent.ai-grant.ttl`, 초기 60초, 발급 시점 값을 행에 기록), expires_at, read_calls, state ISSUED|CONSUMING|CONSUMED|EXPIRED). AI 서비스는 `X-TrustAgent-Grant` 헤더로 보낸다.

검사 순서(Tool): 서비스 토큰 → grant 존재 → 만료 → workspace 유효 → `consultationId` = grant → `familyId` ∈ allowed → read_calls < 50(원자 증가). 검사 순서(기록): 기록 토큰 → grant → 만료 → workspace → `consultation_id`·`application_id` = grant → `UPDATE state='CONSUMING' WHERE state='ISSUED'`(0행이면 409 `GRANT_CONSUMED`) → 기존 기록 트랜잭션 → `CONSUMED`. 사용은 `ai_request_grant_use`(append-only)에 남긴다. `require-grant=false`(로컬 CLI·하네스)에서는 헤더가 없어도 통과하되 있으면 검사한다. cloud·prod profile은 true가 아니면 기동 거부.

**grant 수용 기준(ADR-014 9항, 이 Task의 AC)**: 만료 403 `GRANT_EXPIRED`, 50회 초과 403 `GRANT_EXHAUSTED`, 기록 재시도 409 `GRANT_CONSUMED`, 동시 두 기록 중 하나만 201, workspace 불일치 403 `WORKSPACE_UNAVAILABLE`, 범위 밖 공문군·상담 ID·신청 ID 403 `GRANT_SCOPE_MISMATCH`, grant 저장 실패 500(AI 호출 없음), AI 호출 실패 시 grant 미사용 만료.

**정합성(ADR-014 9-1항. 이 Task는 기본 사례, 동시 요청·workspace 정리·재시작 복구는 TASK-026)**: 업무 기록 커밋 뒤 제어 DB `CONSUMED` 갱신 실패 → grant는 `CONSUMING`으로 남아 만료되고, 같은 run_id 재전송은 기존 409 `RUN_ID_CONFLICT`, 새 run_id 재전송은 `ALREADY_RECORDED`(새 grant 필요). 대조 질의가 "기록 결과는 있으나 사용 행이 없는 grant"를 센다. 동시 요청과 workspace 정리 사례는 TASK-026에서 추가한다.

### 실패 처리

인증 없음 401(본문에 경로 정보 외 없음). 역할 불일치 403. 담당자 아님 404. CSRF 실패 403. AI 서비스 연결 실패·시간 초과 → 준비안 경로 502·504(TASK-015 표). AI 서비스 수신 토큰 없음·불일치 401(AI 서비스). 모든 거부는 `security_event`에 principal, 경로, 코드, trace ID로 남기고 본문·토큰은 남기지 않는다.

### 추가 검증 항목 (사용자 요청)

- **서비스 인증과 Spring Security의 공존.** Tool 경로(`/api/v1/tools/**`)와 기록 경로(`/api/v1/consultation-preparations`)는 세션·CSRF 없이 기존 서비스 토큰 필터만으로 동작해야 한다. Spring Security 설정에서 두 경로를 stateless·CSRF 제외로 두고, 토큰 없는 호출은 기존대로 401(JSON problem)이며 로그인 페이지로 가지 않는다. TASK-015 연결 검증(필수 CI)이 그대로 통과하는 것이 증거다(AC-11).
- **API 비인증 응답은 401 JSON.** `/api/**`의 인증 진입점은 HTML 로그인 리다이렉트가 아니라 `401 {"code":"UNAUTHENTICATED"}`다. 302나 `text/html`이 나오면 실패다(AC-01).
- **CSRF.** 쿠키 토큰 방식(`XSRF-TOKEN` 쿠키 + `X-XSRF-TOKEN` 헤더). 토큰 없는 상태 변경 요청은 403이고 사건으로 기록한다. 서비스 토큰 경로는 제외.

### 상담 건 생성 시 신청 존재 검증 (제안 5)

계획 초안은 "신청 ID 형식만 검사"였다. 그러면 존재하지 않는 신청으로 상담 건이 만들어지고, 준비안 요청이 AI 서비스에서 `APPLICATION_NOT_FOUND`로 끝나며, Core는 기록 시 신청 자료 해시를 검증하지 못한다(TASK-015 한계). 대안은 다음과 같다.

| 방법 | 내용 | 장단점 |
|---|---|---|
| (a) Core에 합성 신청·기업 자료 등록(권장) | 기존 매핑 적재와 같은 방식으로 `datasets/synthetic/work/applications`·`companies`를 bootstrap runner가 업무 DB 표 `synthetic_work_application`(append-only, `application_id`, `company_id`, `product_key`, `source_hash`, `dataset_class`)에 적재한다. 상담 건 생성은 이 표에 있는 신청만 허용(없으면 422 `APPLICATION_NOT_REGISTERED`)하고 상품 키로 허용 공문군(grant의 `allowed_family_ids`)을 매핑에서 뽑는다. 기록 경로는 AI 서비스가 보낸 `application.source_hash`를 이 표와 대조한다 | Core가 신청의 존재·상품·해시를 직접 검증. 표 1개와 runner 1개 추가. AI 서비스의 파일 읽기는 그대로(이중 출처이지만 해시로 대조) |
| (b) 상담 건 생성 시 AI 서비스에 조회 | Core → AI 서비스 `GET /api/v1/ai/applications/{id}` | 생성 경로가 AI 서비스 가동에 의존하고 Core가 자료를 소유하지 않음 |
| (c) 형식 검사만(초안) | | 존재하지 않는 신청의 상담 건 허용. 거절 |

**판단: (a) 채택**(결정자 사용자, PR #47 검토). 신청 ID 형식만 검사하는 방식은 채택하지 않는다. 두 번째 PR(상담 건·grant)에 업무 DB 표 `synthetic_work_application`·`synthetic_work_company`와 bootstrap runner(기존 합성 JSON을 초기 자료로 재사용, 계약 검증, canonical `source_hash` 저장, 재실행 멱등)를 추가하고, 상담 건 생성은 등록된 신청만 허용하며, 기록 경로는 AI 서비스가 보낸 `application.source_hash`를 이 표와 대조한다(불일치 422 `APPLICATION_SOURCE_MISMATCH`). 두 원본이 어긋나지 않도록 AI 서비스는 당분간 같은 JSON을 읽되 해시 대조로 일치를 강제하고, 최종적으로 Core가 신청·상담 자료의 원장이 되어 AI 서비스는 허용된 Core API로 필요한 정보만 받는 방향으로 간다(후속 Task). 체험 템플릿 DB(TASK-026)에도 같은 적재가 들어간다.

## Acceptance Criteria (구현 전 고정)

결정자 / 판단 근거 / 검토 대상: 사용자 / 이 계획 PR

| ID | 입력/상황 | 기대 결과 | 검증 방법 | 결과/evidence |
|---|---|---|---|---|
| AC-01 | 세션 없이 `/api/v1/**`(Tool·기록 제외) 호출 | 전부 401 JSON(`UNAUTHENTICATED`), 302·HTML 없음, 업무 데이터 없음 | Java 통합 테스트(MockMvc 또는 RANDOM_PORT) | 미검증 |
| AC-02 | `SYN-STAFF-01` 로그인 | 200, 세션 ID가 로그인 전과 다름, 쿠키 HttpOnly·SameSite=Lax(Secure는 cloud profile). CSRF 토큰 없는 상태 변경 요청 403 | 통합 테스트 | 미검증 |
| AC-03 | STAFF 활성으로 `GET /api/v1/reviews/proposals` | 403. `SYN-STAFF-REVIEWER-01`이 활성 역할을 REVIEWER로 바꾼 뒤 200, `security_event`에 전환 기록 | 통합 테스트 | 미검증 |
| AC-04 | `SYN-REVIEWER-01`로 `POST /api/v1/consultations` | 403(STAFF 활성 아님) | 통합 테스트 | 미검증 |
| AC-05 | 사용자 A의 상담 건을 B 세션으로 조회·준비안 요청 | 404, 거부 사건 기록 | 통합 테스트 | 미검증 |
| AC-06 | 본문에 다른 사용자 ID·역할 문자열 | 무시되고 principal·활성 역할만 쓰임 | 통합 테스트 | 미검증 |
| AC-07 | require-grant=true에서 서비스 토큰만으로 Tool·기록 | 401 `GRANT_REQUIRED`, 감사에 거부 | 통합 테스트 | 미검증 |
| AC-08 | grant 수용 기준 8항 | 각각 지정된 상태 코드, 동시 기록은 하나만 201 | 통합 테스트(동시 요청은 스레드 2개) | 미검증 |
| AC-09 | 준비안 경로 전체 | STAFF 세션 → grant → AI 서비스(수신 토큰) → Tool·기록(grant 헤더) → 준비안 응답. `ai_request_grant_use`에 Tool 5회·기록 1회, grant `CONSUMED` | Java + Python 연결 검증(기존 하네스 확장, 필수 CI) | 미검증 |
| AC-16 | 신청 존재·일치 검증(A안) | 등록되지 않은 신청 ID로 상담 건 생성 422 `APPLICATION_NOT_REGISTERED`. 등록된 신청은 상품 키로 허용 공문군이 정해짐. 기록 경로에서 `application.source_hash`가 등록된 해시와 다르면 422 `APPLICATION_SOURCE_MISMATCH`. 적재 runner 재실행 멱등, 같은 ID 다른 내용 거부 | Java 통합 테스트 | 미검증 |
| AC-10 | AI 서비스 수신 토큰 없음·불일치 | 401, Tool 호출 없음 | Python 테스트 | 미검증 |
| AC-11 | require-grant=false(로컬 CLI)와 서비스 토큰 경로 | TASK-015·021 흐름이 그대로 동작. Tool·기록 경로는 세션·CSRF 없이 토큰만으로 동작하고 토큰 없으면 401 JSON(리다이렉트 없음) | 기존 테스트 + 연결 검증(필수 CI) | 미검증 |
| AC-12 | 정합성 기본 사례(ADR-014 9-1항) | 기록 커밋 뒤 `CONSUMED` 갱신 실패 주입 → 업무 기록 1건, 같은 run_id 재전송 409 `RUN_ID_CONFLICT`, 새 run_id·새 grant 재전송 `ALREADY_RECORDED`, 대조 runner가 `CONSUMING` 만료 grant 1건을 사후 정리. TTL 설정값이 grant 행에 기록 | 통합 테스트(제어 DB 실패 주입) | 미검증 |
| AC-13 | 비밀 | 비밀번호·토큰 값이 저장소·로그·응답에 없음. cloud profile에서 require-grant·수신 토큰 누락 시 기동 거부 | 테스트 + grep | 미검증 |
| AC-14 | 변경 범위 | 기존 승인·일정·변경안·검증 로직과 `tool_call_audit` 구조 변경 없음 | diff 검토 | 미검증 |
| AC-15 | 테스트 수 | 기존 Python·Java 유지, 새 테스트 추가, CI 통과 | CI run | 미검증 |

### 완료 기준 변경 이력

변경 없음.

## Implementation Plan

예상 변경 파일: Core `security/`(SecurityConfig, 사용자 서비스, 활성 역할, 세션 API, 사건 기록), `control/`(제어 DataSource·Flyway `db/control`), `consultation/`(V11 신청·기업·상담 건 표, 적재 runner, repository, controller), `grant/`(제어 DB V2, 발급·검사·사용), `ai/`(AI 서비스 클라이언트), 기존 필터 2개(grant 검사 호출), `application.yml`(제어 DB·수신 토큰·require-grant), `build.gradle`(spring-boot-starter-security), 테스트; AI 서비스 `app.py`(수신 토큰, grantId), `core_client.py`(헤더), `config.py`, 테스트; README 2곳; 이 문서.

구현 순서(PR 단위):

1. **인증 PR**: Spring Security, 제어 DB, 합성 사용자, 세션·활성 역할 API, 기존 조회 경로 인증, `security_event`(AC-01~06, 13).
2. **grant PR**: 합성 신청·기업 적재 runner와 표(A안), 상담 건 표·API, grant 발급·검사·사용, Core → AI 서비스 경로, AI 서비스 수신 토큰·헤더, 기록 경로의 신청 해시 대조, 연결 검증 확장(AC-07~12, 14~16).

위험: Spring Security 도입으로 기존 테스트의 요청 경로가 바뀜(MockMvc 설정). 제어 DB 추가로 로컬 실행 절차가 늘어남(README와 compose에 반영). 동시성 테스트의 불안정성(DB 수준 원자 갱신으로 결정적 검증).
예상 크기: 중간~큼(PR 2개). 의존: ADR-014 승인. TASK-016과 병렬 가능(공유 파일은 `ToolAuthenticationFilter`뿐이며 017a가 먼저 병합되면 016 검색 API는 demo 모드로 영향 없음).

## AI 제안 및 인간 판단 기록

### 제안 1: 제어 DB를 별도 데이터베이스로 둔다

내용 / 대안 / 위험: 같은 DB의 별도 스키마로 두면 설정이 하나로 줄지만, TASK-026의 workspace 복제가 업무 DB 전체를 복제하므로 제어 표가 함께 복제돼 혼란을 준다. 별도 DB는 DataSource 하나가 늘고 compose에 DB 생성 단계가 는다.

**판단** - [x] 채택 [ ] 수정 [ ] 거절 / 판단자 / 검토 대상: 사용자 / PR #47(제어 DB는 별도 PostgreSQL 데이터베이스)

### 제안 2: 활성 역할은 세션 속성으로 두고 모든 역할 경로가 "보유 + 활성" 둘 다 검사한다

내용 / 대안 / 위험: 보유만 검사하면 겸임 사용자가 의도치 않게 검수자 행위를 할 수 있고 감사에 수행 역할이 남지 않는다. 활성만 검사하면 보유 검사가 빠진다. 둘 다 검사하면 전환 호출이 하나 늘지만 재로그인은 없다.

**판단** - [x] 채택 [ ] 수정 [ ] 거절 / 판단자 / 검토 대상: 사용자 / 계획 문서 검토(역할 보유와 활성 역할 검사를 구분)

### 제안 3: 담당자가 아닌 상담 건은 403이 아니라 404로 답한다

내용 / 대안 / 위험: 403은 "존재하지만 권한 없음"을 알려 상담 ID 열거의 단서가 된다. 404는 존재 여부를 숨긴다. 감사에는 거부로 남긴다.

**판단** - [x] 채택 [ ] 수정 [ ] 거절 / 판단자 / 검토 대상: 사용자 / PR #47(외부 404, 내부 감사 기록)

### 제안 4: grant 읽기 호출 상한 50은 설정값으로 두고 초기값을 유지한다

내용: 현재 준비안 1건은 Tool 호출 5회다. 상한은 유출 토큰의 대량 조회를 막는 장치이며 정상 흐름의 10배다. 검색(017c)은 후보 수만큼 Tool 2를 부르므로 topK 10 기준으로도 여유가 있다.

**판단** - [x] 채택 [ ] 수정 [ ] 거절 / 판단자 / 검토 대상: 사용자 / PR #47(설정 가능한 초기값)

## Implementation Result (구현 결과와 자동 검증)

미착수.

## AI self-review

미착수.

## 인간 검수와 Explainability Gate

판단 대기.

## 결정 기록과 완료

판단 대기.
