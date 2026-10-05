# ADR 009 Oracle 전환 검토 종료와 Core 업무 DB PostgreSQL 유지

## 상태

**결정: Core 업무 DB는 PostgreSQL을 유지한다. Oracle 전환 검토는 종료됐다.**

- 작성: AI / 결정자: 사용자
- 검토 대상 revision: 이 ADR의 PR #10 브랜치
- 이 결정은 같은 문서의 이전 판단 기록(3절 "대체된 판단 기록")을 대체한다. 이전 기록은 삭제하지 않고 "대체됨"으로 남긴다.
- ADR-007과 ADR-008은 **대체되지 않는다.** 두 ADR의 PostgreSQL 전용 구현 수단은 현재 구현 수단으로 그대로 유효하다.
- Oracle 전환을 전제했던 TASK-003(Oracle 위험 검증 spike)은 중단, TASK-004(Oracle 전환 1단계)는 취소다. 자세한 내용은 [PLAN-001](../tasks/PLAN-001_중도상환수수료-흐름-Task-분할-초안.md)과 [TASK-003](../tasks/TASK-003_Oracle-위험-검증-spike.md).

## 배경

CLAUDE.md 초안은 Core 업무 DB를 Oracle로 정했고, 이 ADR의 첫 초안은 그 방향을 받아 Oracle 전환의 업무 규칙과 구현 수단 분리, 대체 관계, 전환 순서를 제안했다. 사용자가 방향을 조건부로 채택한 뒤 Oracle 위험 검증 spike(TASK-003)가 착수됐다.

spike 착수 직후 사용자는 Core DB 선택을 재검토했고, PostgreSQL 유지로 결정했다. 판단 근거는 다음과 같다.

- **원장 API 연계와 TrustAgent DB 제품 선택은 별개다.** TrustAgent는 은행 원장(여신, 고객, 승인 데이터)을 소유하지 않는다. 원장과의 연계는 API 경계를 통해 이루어지며, 원장 시스템이 어떤 DB 제품을 쓰는지가 TrustAgent 업무 DB의 제품을 결정하지 않는다. TrustAgent DB는 공문 version, 구조화 rule, proposal, validation, 사람 결정, 승인 checklist, 감사 기록을 담는 독립 업무 DB다.
- **기존 PostgreSQL 구현이 업무 규칙을 DB 수준에서 강제하고 있고 검증돼 있다.** Flyway V1~V5, append-only trigger, break-glass 감사, event trigger, exclusion constraint, partial unique index, NOLOGIN role이 R-01~R-18(2절)을 강제하며 Java 82개 테스트가 PostgreSQL 18.6에서 통과한다.
- **전환 비용과 위험이 우선 흐름과 무관하다.** 전환은 migration 재작성, 감사와 권한 계층 재설계, 통합 테스트 8개 클래스 재작성을 요구하고, 특히 기간 중첩 금지(R-12)는 Oracle에 선언적 대응이 없어 lock + trigger 설계와 동시성 검증이 필요했다. 이 비용은 "중도상환수수료 공문 변경 → 검수/승인 → 행원 업무 제공" 흐름에 기여하지 않는다.
- **1인 2주 MVP 범위**에서 완결된 E2E 흐름이 넓고 미완성인 기술 전환보다 우선한다(AGENTS.md).

## 결정

### 1. Core 업무 DB는 PostgreSQL이다

- 현재 PostgreSQL 18.6 기반 구현(Flyway V1~V5, role, trigger, 제약, JdbcClient SQL, Testcontainers PostgreSQL)을 유지한다. ADR-007의 기술 기준선과 ADR-008의 schema 설계는 그대로 유효하다.
- 자산 audit에서 "PostgreSQL 결합"만을 이유로 MODIFY였던 항목은 KEEP으로 재평가한다. DB와 무관한 이유의 MODIFY(예: 테스트 메서드명)는 유지한다. 재평가 결과는 [자산 audit](../tasks/TASK-000_자산-audit-초안.md) 4절.
- Pre-SDLC Asset 분류는 유지한다. "Pre-SDLC"는 도입 시점 구분이며 교체 대상이라는 뜻이 아니다.
- 다음 기능은 기존 PostgreSQL schema 위에서 proposal → 자동 검증 → 사람 검수와 승인 → checklist 제공 흐름으로 이어간다. V4에 이미 있는 `approved_checklist_version`, `approved_checklist_schedule_revision`, `approved_checklist_schedule_entry`와 exclusion constraint를 그대로 사용한다.

### 2. 업무 규칙 목록은 유지한다 (DB 무관)

Oracle 검토 과정에서 분리한 업무 규칙 R-01~R-18은 DB와 무관한 불변식이며 ADR-007, ADR-008의 요구사항을 한 곳에 정리한 것이다. 이 목록은 후속 Task의 테스트 명세로 유지한다. 각 규칙을 현재 강제하는 PostgreSQL 수단을 함께 적는다.

| ID | 규칙 | 현재 PostgreSQL 수단 | 출처 |
|---|---|---|---|
| R-01 | 업무 record는 append-only다. runtime과 importer는 수정, 삭제, 전체 삭제를 할 수 없다. 정정은 superseding record로 표현한다. | 테이블별 `BEFORE UPDATE OR DELETE` row trigger, `BEFORE TRUNCATE` trigger, runtime role에 SELECT/INSERT만 GRANT | ADR-007 |
| R-02 | break-glass 정정은 maintenance 권한자가 티켓, 사유, 행위자를 제공한 경우에만 허용되고 전후 record와 hash가 자동 감사된다. | `pg_has_role`, `current_setting('trust_agent.maintenance_*')`, `SECURITY DEFINER` 가드 함수, `to_jsonb(OLD/NEW)`, `maintenance_change_audit` | ADR-007 |
| R-03 | 감사 기록은 보호 대상 계정(maintenance 포함)이 수정하거나 삭제할 수 없다. | 감사 테이블 거부 trigger, `trust_agent_audit_owner` NOLOGIN 소유 | ADR-007 |
| R-04 | migration 계정은 가드 trigger를 비활성화하거나 제거할 수 없고 가드가 빠진 DDL은 commit되지 않는다. | event trigger `ddl_command_start`(DROP TRIGGER 차단), `ddl_command_end`(가드 누락/비활성 검사) | ADR-007 |
| R-05 | 역할별 최소 권한: runtime, maintenance, migration, importer, synthetic importer, audit owner. importer는 승인과 철회를 기록할 수 없다. | NOLOGIN role 5개 + GRANT/REVOKE, 테이블 OWNER 분리 | ADR-007, ADR-008 |
| R-06 | 상태별 필드 일관성(성공/실패, 수동 취득 사유). | CHECK 제약 | ADR-006, ADR-007 |
| R-07 | ID 접두어 형식, `sha256:` 64자 hex, kbstar.com https URL. | 정규식 CHECK | ADR-004, ADR-007 |
| R-08 | 수집 시도와 관측은 같은 트랜잭션에서 1:1 생성. | `DEFERRABLE INITIALLY DEFERRED` 순환 FK | ADR-006 |
| R-09 | family별 root 1건, 선형 supersedes, 같은 family 안 대체. | partial unique index `WHERE supersedes_notice_id IS NULL`, `supersedes_notice_id UNIQUE`, 복합 자기참조 FK | ADR-008 |
| R-10 | `[effective_from, effective_to)` 반열린 구간, 종료일은 시작일보다 뒤. | CHECK, `date` 타입 | ADR-008 |
| R-11 | schedule revision도 family별 root 1건, 단일 대체, 동시 대체는 하나만 성공. | partial unique index, `supersedes_schedule_revision_id UNIQUE` | ADR-008 |
| R-12 | 한 schedule revision 안 같은 family 구간 중첩 금지(맞닿음 허용, 열린 구간 포함). | `btree_gist` + `EXCLUDE USING gist (..., daterange(from, to, '[)') WITH &&)` | ADR-008 |
| R-13 | 업무 timezone `Asia/Seoul`과 정책 version으로 업무일 변환. 사건 시각은 UTC Instant, 업무일은 날짜. | `timestamptz`, `date`, Java `BusinessTimePolicy` | ADR-008 |
| R-14 | 합성 표시와 면책 문구 필수, dataset class 혼합 금지. | CHECK(`synthetic=true`, `dataset_class`), 길이 CHECK | ADR-002, ADR-008 |
| R-15 | 같은 ID 같은 hash 재적재는 멱등, 다른 내용은 충돌. canonical JSON은 부동소수점 거부. | `source_record_hash` 비교(importer), Java `CanonicalJsonHasher` | ADR-007, ADR-008 |
| R-16 | rule version마다 `structured_change`는 객체 하나이거나 명시적 "없음". SQL NULL과 구분. | `jsonb NOT NULL DEFAULT 'null'`, `jsonb_typeof IN ('object','null')` (V5) | 최종 기획서 정합화 |
| R-17 | importer 동시 실행 금지. lock은 보조 수단. | `pg_advisory_xact_lock` | ADR-007, ADR-008 |
| R-18 | 느린 query 중단, readiness는 연결과 schema version 분리. | `SET statement_timeout`, Flyway version 비교 | ADR-007 |

보장 범위: R-01~R-05는 애플리케이션 계정(runtime, maintenance, migration, importer 2종)에 대해 DB 수준으로 보장하고 테스트로 증명한다. superuser와 OS 수준 접근은 보장 범위 밖이며 운영 통제 대상이다. ADR-007의 "누구도 수정할 수 없다"는 표현은 이 경계로 읽는다.

### 3. 대체된 판단 기록 (Oracle 전환 방향, 이번 결정으로 대체)

이전 초안에서 사용자가 조건부로 채택했던 항목이다. 삭제하지 않고 "대체됨"으로 남긴다.

| 제안 ID | 이전 판단 | 이번 결정에 따른 상태 |
|---|---|---|
| 제안 1 | Oracle 버전과 이미지는 확인 후 제안, 19c 비목표 | **대체됨.** 확인 결과(E 그룹 사실)는 4절에 기록, 선택은 하지 않음 |
| 제안 2 | 내부 공문 먼저, 공개 상품 후순위의 2단계 전환 | **대체됨.** 전환 없음 |
| 제안 3 | 전환 기간 공개 endpoint와 importer 비활성 계약 | **대체됨.** 비활성 계약 불필요. 공개 상품 endpoint와 importer는 현재 PostgreSQL 구현대로 동작 |
| R-12 수단 | lock + trigger 검증 후보 | **대체됨.** PostgreSQL exclusion constraint 유지 |
| R-02 감사 trigger 생성 방식 | 대표 범위 수작업 검증 후 재판단 | **대체됨.** PostgreSQL `to_jsonb(OLD/NEW)` 범용 함수 유지 |
| R-01~R-18 | 불변식 목록 채택, 절대 표현 수정 | **유지.** 2절 |
| R-04 | Oracle DDL 보호 실험 검증 | **대체됨.** PostgreSQL event trigger 유지 |
| 대체 관계 | ADR-007/008 부분 대체 | **대체됨.** ADR-007/008 전부 유효 |
| ADR 분리 | ADR-010 검색, ADR-011 Core Tool API | **유지.** 5절 |
| 변경 범위 | Java 8개 클래스 목표 | **대체됨.** DB 방언 변경 없음 |
| 쟁점 2 (audit) | 전환 1단계 셀러론 공문 S1-b | **대체됨.** 공개 상품 테이블이 있으므로 공문 4개 전부 적재 유지 |
| 전환 경로 (PLAN) | TASK-004 한 PR, C1~C4 | **대체됨.** TASK-004 취소 |

### 4. Oracle spike에서 확인된 사실 (중단, 미완료)

TASK-003은 중단됐고 실험은 미완료다. 아래는 실제로 실행되어 확인된 사실과 실행되지 않은 항목의 구분이다. **Oracle 핵심 무결성(R-12, R-04, R-02) 검증은 완료되지 않았다.** 상세는 [TASK-003 spike evidence](../evidence/TASK-003_ORACLE_SPIKE_EVIDENCE.md).

확인된 사실(로컬 macOS arm64, Docker linux/arm64, Java 21):
- 이미지 `gvenzl/oracle-free:23.26.3-slim-faststart`는 amd64와 arm64 manifest를 가진 멀티 아키텍처 index다. arm64에서 pull과 기동 성공, 기동 15~20초.
- Spring Boot 4.1.1 BOM이 Oracle JDBC 23.26.3.0.0, Flyway Oracle 12.4.0, Testcontainers oracle-free 2.0.5의 버전을 관리하며 Gradle 의존성 해석과 lockfile, verification metadata 기록에 성공했다.
- DB 버전 문자열 "Oracle AI Database 26ai Free Release 23.26.3.0.0". `BOOLEAN`, 네이티브 `JSON`, FROM 없는 SELECT, `FETCH FIRST`, `LATERAL`, `CROSS APPLY`, `REGEXP_LIKE` CHECK, 함수 기반 unique index, deferrable FK, IDENTITY, `STANDARD_HASH` 지원. `IS NOT DISTINCT FROM` 미지원(ORA-00908).
- Testcontainers oracle-free 모듈은 `ORACLE_PASSWORD`를 앱 비밀번호로 덮어쓴다. SYSTEM으로도 SYS dictionary view에 대한 객체 GRANT는 ORA-01031이며 `SELECT ANY DICTIONARY` 시스템 권한이 필요하다. ojdbc 23은 autocommit 연결의 `commit()`에 ORA-17273을 낸다. Flyway는 비어 있지 않은 schema에 history table이 없으면 `baselineOnMigrate` 없이 실패한다.

실행되지 않은 항목: E-02(CI x86_64), E-04(Flyway 재실행), R-12 7개, R-04 5개, R-02 6개, M 8개.

### 5. 유지하는 방향 (Oracle과 무관)

- **Elasticsearch 검색:** BM25 + dense vector k-NN + metadata filter + reranker가 baseline. pgvector/PostgreSQL 검색은 baseline이 아니다. 검색 인덱스는 업무 원장이 아니며 PostgreSQL과 ES 사이에 단일 ACID를 가정하지 않는다(Outbox 등은 ADR-010에서 결정). dataset class 구분을 인덱스 문서와 filter에 적용한다(ADR-002 보완).
- **FastAPI AI 서비스:** 업무 DB 직접 접근과 자격증명 보유 금지. Core Tool API로만 조회(ADR-011에서 상세 결정).
- **Redis:** 단기 상태, 중복 방지, 캐시로 제한. DB 제약을 대체하지 않는다. 구체적 사용처가 확인될 때까지 보류.
- **LangGraph:** 상태, 분기, 재시도가 필요한 workflow에만 선택적으로 사용.

## 검토한 대안

- **Oracle 전환 계속:** 원장 연계와 무관한 제품 전환에 MVP 자원을 쓰게 되고, R-12 대체 설계와 동시성 검증이라는 새 위험을 만든다. 기각.
- **두 DB 동시 지원:** 이중 구현과 이중 테스트. 기각(이전 초안과 같음).
- **결정 보류:** 다음 기능 Task(proposal, validation, 사람 결정)가 schema를 추가해야 하므로 DB가 정해지지 않으면 착수할 수 없다. 기각.

## 위험과 제한사항

- 운영 환경에서 관리형 PostgreSQL이 `btree_gist` extension과 event trigger(superuser 권한)를 허용하는지는 ADR-007/008의 기존 운영 전제 그대로 확인 대상이다.
- V1 event trigger는 Flyway 실행 계정에 superuser 권한을 요구한다. README에 명시돼 있지 않으며 운영 ADR에서 다룬다.
- 원장 연계 API의 실제 형태는 미정이다. 이 ADR은 "연계가 API 경계로 이루어진다"는 전제만 둔다.
- Oracle spike 산출물(spike worktree, 이미지)은 보존하되 커밋, push, 재실행하지 않는다.

## 결과

- CLAUDE.md, 개발 규칙, 검수 체크리스트의 Core 업무 DB 표기를 PostgreSQL로 고친다(PR #10).
- 자산 audit에서 PostgreSQL 결합만을 이유로 한 MODIFY를 KEEP으로 재평가한다(PR #10).
- PLAN-001에서 TASK-003은 중단, TASK-004는 취소로 기록하고 다음 기능 Task를 PostgreSQL 기반으로 연결한다(PR #10).
- 합성 공문 조회 자산 보존(PR #11)의 Oracle 전제 문구를 정정한다. 기능 범위는 바꾸지 않는다.

## 판단 기록

- 결정자: 사용자 / 검토 대상: PR #10 브랜치

| 제안 ID | 내용 | 판단 | 이유 / 승인 범위 |
|---|---|---|---|
| DB-결정 | Core 업무 DB PostgreSQL 유지, Oracle 전환 검토 종료 | **채택** | 원장 API 연계와 TrustAgent DB 제품 선택은 별개. Oracle 전환 결정과 TASK-003/004의 Oracle 실행 승인 철회 |
| 유지 방향 | Elasticsearch 검색, FastAPI의 Core Tool API 조회, Redis 제한적 용도, 선택적 LangGraph | **유지** | — |
| 이전 기록 | 3절의 Oracle 방향 판단 | **대체됨** | 삭제하지 않고 대체 관계 기록 |

인간 검수와 Explainability Gate: **미기록.** 결정 채택은 검수 통과가 아니다.
