# ADR 009 Core 업무 DB Oracle 확정과 PostgreSQL 기반 결정의 대체

## 상태

**방향 채택 (조건부). Oracle 구현 수단 후보는 검증 대기. 전환 완료 아님.**

- 작성: AI / 결정자: 사용자
- 검토 대상 revision: 이 ADR의 미커밋 초안
- 승인 범위: 아래 "결정"의 방향과 대체 관계. Oracle SQL과 trigger 후보의 검증 완료나 Oracle 전환 완료를 뜻하지 않는다. 각 후보는 [TASK-003](../tasks/TASK-003_Oracle-위험-검증-spike.md)에서 실험으로 확인한 뒤에만 "보장 수단"으로 기록한다.
- 이 ADR이 적용되면 ADR-007과 ADR-008의 PostgreSQL 전용 절을 대체한다. DB 선택은 다시 논의하지 않는다. Oracle은 [CLAUDE.md](../../CLAUDE.md)와 사용자 결정으로 확정됐다.

## 배경

ADR-007은 Core 업무 DB를 PostgreSQL 18.6으로 정했고, ADR-008은 그 위에 합성 내부 공문 schema를 설계했다. 두 ADR의 **업무 규칙**은 대부분 DB와 무관하지만, 규칙을 **강제하는 수단**은 PostgreSQL 전용 기능에 의존한다. NOLOGIN role 5개, PL/pgSQL `SECURITY DEFINER` 함수, 세션 변수 기반 break-glass 감사, event trigger, `btree_gist` exclusion constraint, partial unique index, `jsonb`, `timestamptz`. Java 코드도 8개 클래스에 PostgreSQL 방언이 있다.

CLAUDE.md는 Core 업무 DB를 Oracle로 정했고 기존 PostgreSQL 구현을 Pre-SDLC Asset으로 분류했다. 문서 갱신만으로 전환 완료를 선언하지 않는다는 원칙에 따라, 이 ADR은 (1) 업무 규칙과 DB별 수단을 분리하고, (2) Oracle에서 각 규칙을 어떻게 강제할지 후보를 정하고, (3) 과거 ADR과 evidence의 대체 관계를 명시한다.

ADR-001부터 006까지는 PostgreSQL에 의존하는 문장이 없다. 검색(Elasticsearch), Redis, FastAPI, LangGraph는 CLAUDE.md에서 방향이 정해졌지만 별도 ADR의 상세 결정이 없다. 이 ADR은 그 영역을 "대체"하지 않고 "상세 결정 부족"으로 기록한다.

## 결정

### 1. Core 업무 DB는 Oracle이다

- 운영과 테스트는 같은 제품군을 쓴다. ADR-007의 "운영 DB와 같은 제품에서 제약과 trigger를 검증한다"는 원칙을 유지하고 대상만 Oracle로 바꾼다.
- **버전과 이미지는 확인 뒤 제안한다.** 확인 없이 특정 버전을 고정하거나 최신이라는 이유로 선택하지 않는다. 19c 호환은 현재 비목표다. 확인 항목과 현재 알려진 사실은 다음과 같다.

| 확인 항목 | 현재 사실 | 확인 방법 (TASK-003) |
|---|---|---|
| 개발 머신 CPU | macOS arm64, Docker linux/arm64 (확인됨) | — |
| CI 러너 CPU | GitHub `ubuntu-latest`는 x86_64로 알려짐 (문서 확인 필요) | 러너에서 `uname -m` 출력 기록 |
| 이미지 공급처 | 후보: 커뮤니티 `gvenzl/oracle-free`, Oracle 공식 `container-registry.oracle.com/database/free`. arm64와 amd64 양쪽 제공 여부 미확인 | 두 아키텍처에서 pull과 기동, digest 기록 |
| Testcontainers Oracle 모듈 | 프로젝트는 Testcontainers 2.0.5. Maven Central에 `org.testcontainers:oracle-free` 1.21.3 존재 확인. 2.x 계열 모듈 이름과 호환 미확인 | 의존성 해석과 컨테이너 기동 |
| Flyway Oracle 모듈 | 프로젝트는 Flyway 12.4.0. `flyway-database-oracle` 존재와 버전 미확인(조회 timeout) | 의존성 해석과 빈 migration 실행 |
| JDBC 드라이버 | `com.oracle.database.jdbc:ojdbc11` 후보, Java 21 호환 미확인 | `OffsetDateTime`, `LocalDate`, `BOOLEAN`, JSON round-trip 테스트 |
| 기능 가정 | `BOOLEAN` 타입, 네이티브 `JSON` 타입, FROM 없는 SELECT, `IS NOT DISTINCT FROM`은 버전에 따라 다름 | 선택한 이미지에서 각 기능 실험 |

- Flyway는 유일한 schema 도구로 유지한다. 기존 PostgreSQL migration V1~V5는 Oracle에서 실행하지 않으며 Oracle 전용 migration을 새 디렉터리에 작성한다. 두 DB용 migration을 한 디렉터리에 섞지 않는다.
- JPA 금지, 명시적 SQL, `JdbcClient` 사용, 저장소에 수정/삭제 메서드 없음은 유지한다.

### 2. 업무 규칙과 DB별 구현 수단을 분리한다

업무 규칙은 DB가 바뀌어도 변하지 않는 불변식이다. 각 규칙은 "어떤 입력에서 무엇이 거부되는가"로 테스트한다. 구현 수단은 DB마다 다르고, Oracle 후보는 TASK-003 실험을 거쳐야 보장 수단으로 기록한다.

#### 2.1 보장 범위의 경계

R-01~R-05의 보호는 **애플리케이션 계정**에 대해 보장한다. 보장 대상과 범위 밖을 구분한다.

| 구분 | 계정 | 보장 |
|---|---|---|
| 보호 대상 | runtime, maintenance, migration, 공개 baseline importer, synthetic importer | DB 제약, trigger, 권한으로 거부를 보장하고 테스트로 증명 |
| 범위 밖 | schema 소유 계정(잠긴 계정으로 운영), SYS/SYSTEM/DBA 권한 계정, OS와 스토리지 수준 접근 | DB 수준 보장 불가. 접근 감사, 직무 분리, 소유 계정 잠금, 변경 승인 절차 같은 운영 통제로 다루며 별도 운영 ADR 대상 |

따라서 "누구도 수정할 수 없다"가 아니라 "보호 대상 계정은 수정할 수 없고, 범위 밖 계정의 접근은 운영 통제와 감사 대상"이다.

#### 2.2 업무 규칙 (DB 무관, 유지)

| ID | 규칙 | 출처 |
|---|---|---|
| R-01 | 업무 record는 append-only다. 보호 대상 계정 중 runtime과 importer는 수정, 삭제, 전체 삭제를 할 수 없다. 정정은 superseding record로 표현한다. | ADR-007 |
| R-02 | 직접 정정(break-glass)은 maintenance 계정이 티켓, 사유, 행위자를 제공한 경우에만 허용되며, 변경 전후 전체 record와 hash가 자동으로 감사 기록에 남는다. | ADR-007 |
| R-03 | 감사 기록은 보호 대상 계정 전부(maintenance 포함)가 수정하거나 삭제할 수 없다. 범위 밖 계정은 2.1절 운영 통제 대상이다. | ADR-007 |
| R-04 | migration 계정은 append-only 가드를 비활성화하거나 제거할 수 없고, 가드가 빠진 상태의 DDL은 성공하지 않는다. 범위 밖 계정은 2.1절 운영 통제 대상이다. | ADR-007 |
| R-05 | 권한은 역할별 최소 권한으로 분리한다: runtime(읽기, 추가), maintenance(정정), migration(DDL), importer(공개 baseline 읽기, 추가), synthetic importer(합성 공문 읽기, 추가), audit owner(감사 기록 작성). importer는 승인과 철회를 기록할 수 없다. | ADR-007, ADR-008 |
| R-06 | 상태별 필드 일관성: 성공이면 결과 참조가 있고 오류가 없으며, 실패면 그 반대다. 수동 취득이면 사유가 필수다. | ADR-006, ADR-007 |
| R-07 | ID는 접두어가 붙은 문자열이며 형식을 강제한다. hash는 `sha256:` 64자 hex다. 공개 원문 URL은 kbstar.com의 https만 허용한다. | ADR-004, ADR-007 |
| R-08 | 수집 시도와 관측은 같은 트랜잭션 안에서 1:1로 생성된다(상호 참조). | ADR-006 |
| R-09 | 공문 family마다 root(최초 version)는 하나다. 한 version을 대체하는 후속 version은 하나뿐이며 대체는 같은 family 안에서만 일어난다. | ADR-008 |
| R-10 | 시행 구간은 `[effective_from, effective_to)` 반열린 구간이며 종료일은 시작일보다 뒤다. `effective_to` 없음은 열린 구간이다. | ADR-008 |
| R-11 | 승인 checklist schedule revision도 family마다 root가 하나이고 대체는 하나뿐이다. 같은 revision을 동시에 대체하려는 두 트랜잭션 중 하나만 성공한다. | ADR-008 |
| R-12 | 한 schedule revision 안에서 같은 family의 시행 구간은 하루도 겹칠 수 없다. 맞닿는 구간(앞의 to = 뒤의 from)은 허용한다. 열린 구간도 겹침 판정에 포함한다. | ADR-008 |
| R-13 | 수신 시각을 업무일로 바꿀 때는 업무 timezone `Asia/Seoul`과 명시된 정책 version을 쓴다. JVM과 DB session 기본 timezone에 의존하지 않는다. 사건 시각은 UTC Instant, 업무일과 시행일은 시각 없는 날짜다. | ADR-008 |
| R-14 | 합성 데이터에는 `synthetic=true`와 면책 문구가 필수이고, dataset class를 공개 데이터와 같은 응답이나 테이블에 표시 없이 섞지 않는다. | ADR-002, ADR-008 |
| R-15 | 같은 ID의 같은 hash 재적재는 멱등이고, 같은 ID의 다른 내용은 충돌로 거부한다. 입력 전체의 canonical SHA-256을 `source_record_hash`로 저장한다. canonical JSON은 부동소수점을 거부한다. | ADR-007, ADR-008 |
| R-16 | rule version마다 구조화 변경(`structured_change`)은 객체 하나이거나 명시적 "없음"이다. SQL NULL과 "없음"을 구분한다. | 최종 기획서 정합화(미커밋 V5) |
| R-17 | importer 동시 실행은 금지한다. 직렬화 lock은 보조 수단이며 무결성 보증은 제약으로 한다. | ADR-007, ADR-008 |
| R-18 | 느린 query는 시간 제한으로 중단한다. readiness는 DB 연결과 schema version 일치를 분리해 보고한다. | ADR-007 |

#### 2.3 PostgreSQL 수단과 Oracle 후보 (전부 검증 대기)

위험도는 설계 난이도다. "낮음"은 기계적 치환으로 보인다는 뜻이며, 빈 문자열과 NULL 동일 취급, `DATE`의 시각 포함, `BOOLEAN`과 `OffsetDateTime` 매핑 같은 의미 차이는 위험도와 별개로 테스트해야 한다.

| 규칙 | PostgreSQL 수단 | Oracle 후보 (검증 대기) | 위험도 | 검증 방법 |
|---|---|---|---|---|
| R-01 | 테이블별 `BEFORE UPDATE OR DELETE` row trigger + `BEFORE TRUNCATE` trigger | PL/SQL row trigger. TRUNCATE는 Oracle에서 DDL이므로 `BEFORE TRUNCATE ON SCHEMA` 시스템 trigger와 권한 미부여로 차단 | 중간 | runtime 계정으로 UPDATE, DELETE, TRUNCATE 시도가 모두 실패 |
| R-02 | `current_setting('trust_agent.maintenance_*')`, `pg_has_role`, `SECURITY DEFINER`, `to_jsonb(OLD/NEW)`, `pg_index`로 PK 탐색 | Application Context(`DBMS_SESSION.SET_CONTEXT`)와 `SYS_CONTEXT`, `AUTHID DEFINER` 패키지(감사 schema 소유), `SESSION_ROLES` 확인. 행 전체 JSON은 범용 함수가 없어 테이블별 `JSON_OBJECT(:OLD.col ...)` trigger. **작은 대표 범위(테이블 1~2개)에서 수작업으로 먼저 검증**하고, 반복량과 유지 비용이 확인되면 생성 방식 도입을 다시 판단 | **높음** | 세 값 없이 UPDATE 거부, 세 값 있으면 허용되고 감사 1행 생성, hash가 session NLS 설정과 무관 |
| R-02 hash | `sha256(convert_to(jsonb::text))` | `STANDARD_HASH(..., 'SHA256')` 또는 `DBMS_CRYPTO`. 직렬화 표기가 달라 PostgreSQL 감사 hash와 호환되지 않음(기존 감사 데이터 없음) | 중간 | 같은 행 두 번 직렬화하면 같은 hash, NLS 변경에도 동일 |
| R-03 | 감사 테이블 거부 trigger, OWNER 분리 | 별도 감사 schema 소유 + 거부 trigger. Immutable Table은 버전 확인 후 선택지 | 낮음 | maintenance 계정으로 감사 행 수정 실패 |
| R-04 | event trigger `ddl_command_start`(DROP TRIGGER 차단), `ddl_command_end`(가드 누락/비활성 시 롤백) | `BEFORE DROP OR ALTER ON SCHEMA` 시스템 trigger, `AFTER DDL ON SCHEMA`에서 `USER_TRIGGERS.STATUS` 검증. **Oracle DDL은 암묵 commit을 수반하므로 trigger 실패 시 해당 DDL이 어떻게 취소되는지, 선행 DML에 미치는 영향은 공식 문서와 실험으로 확인** | **높음** | migration 계정으로 `DROP TRIGGER`, `ALTER TRIGGER ... DISABLE`, `ALTER TABLE ... DISABLE ALL TRIGGERS` 모두 실패하고 가드 상태 불변 |
| R-05 | `CREATE ROLE ... NOLOGIN` 5개, `REVOKE ... FROM PUBLIC`, 테이블 OWNER 분리 | Oracle ROLE은 로그인하지 않음. 소유 schema(잠긴 계정)와 사용자 계정 분리, role별 GRANT, synonym 또는 schema prefix. Flyway 계정에 role 생성 권한 필요 | 중간 | 각 계정으로 허용/금지 DML과 DDL 매트릭스 |
| R-06, R-07 | `CHECK (col ~ '^regex$')` | `CHECK (REGEXP_LIKE(col, '^regex$'))`. 문법 차이 점검 | 낮음 | 기존 거부 case 재사용 |
| R-08 | `DEFERRABLE INITIALLY DEFERRED` 순환 FK | 동일 기능 | 낮음 | 같은 트랜잭션 삽입 성공, 단독 삽입 실패 |
| R-09, R-11 root 1건 | partial unique index `WHERE supersedes_* IS NULL` | 함수 기반 unique index `(CASE WHEN supersedes_id IS NULL THEN family_id END)` | 낮음 | 두 번째 root 삽입 실패 |
| R-09, R-11 단일 대체 | `supersedes_* UNIQUE` + 복합 자기참조 FK | 그대로 | 낮음 | 동시 대체 2건 중 1건 실패 |
| R-12 | `btree_gist` + `EXCLUDE USING gist (... daterange '[)' &&)` | **선언적 대응 없음.** 검증 후보: revision 쓰기 트랜잭션이 부모 revision 행 또는 family 행을 `SELECT ... FOR UPDATE`로 잠근 뒤 compound trigger 또는 PL/SQL 패키지로 겹침 검사. 서비스 계층 검사만으로는 R-12를 충족하지 않는다. **실제 동시성 테스트 전에는 보장 수단으로 기록하지 않는다** | **높음** | 하루 겹침 거부, 맞닿음 허용, 열린 구간 겹침 거부, 두 세션 동시 삽입에서 하나만 성공하고 실패 세션은 rollback |
| R-10 | `CHECK (from < to)`, `date` | 동일 CHECK. `DATE`는 시각을 포함하므로 `CHECK (col = TRUNC(col))` 추가 | 중간 | 시각 포함 값 거부 |
| R-13 | `timestamptz` | `TIMESTAMP WITH TIME ZONE` 저장 전 UTC 정규화를 Java에서 보장, 또는 UTC 고정 `TIMESTAMP(6)`. ojdbc `OffsetDateTime` 매핑 검증 | 중간 | DB session timezone 변경에도 조회 Instant 동일(기존 테스트 재사용) |
| R-14 | `boolean`, `text` | `BOOLEAN`(버전 확인 후) 또는 `NUMBER(1) CHECK IN (0,1)`. 문자열은 `VARCHAR2(4000)` 또는 `CLOB`. 빈 문자열은 NULL로 취급되므로 "빈 문자열 금지" CHECK는 NOT NULL로 대체되며 의미 차이를 문서화 | 중간 | 빈 문자열 삽입이 NOT NULL 위반 |
| R-15 | Java `CanonicalJsonHasher` | 변경 없음 | 없음 | 기존 단위 테스트 |
| R-16 | `jsonb NOT NULL DEFAULT 'null'`, `jsonb_typeof IN ('object','null')` | `JSON` 컬럼 + `IS JSON` + 타입 검사. JSON 스칼라 `null` 기본값 가능 여부 확인. 불가하면 플래그 컬럼 분리 | 중간 | SQL NULL 거부, JSON null 허용, 배열 거부, 객체 허용 |
| R-17 | `pg_advisory_xact_lock` | `DBMS_LOCK.REQUEST(release_on_commit)` 또는 전용 lock 행 `SELECT ... FOR UPDATE`. 추천은 lock 행 | 낮음 | importer 2개 동시 실행 시 하나가 대기 |
| R-18 | Hikari `connectionInitSql = SET statement_timeout` | 제거 후 `queryTimeout` 또는 ojdbc 설정 | 중간 | 10초 query가 제한 시간에 중단(기존 테스트 재사용) |
| readiness | `select 1`, `success = true`, `limit 1` | `select 1 from dual`(버전에 따라 생략 가능), `success = 1`(Flyway Oracle은 `NUMBER(1)`), `FETCH FIRST 1 ROWS ONLY` | 낮음 | 기존 readiness 테스트 |
| JSON 읽기/쓰기 | `cast(:x as jsonb)`, `col::text` | 문자열 바인딩 + `JSON(:x)` 또는 `IS JSON` 컬럼, 읽기 `JSON_SERIALIZE` | 낮음 | round-trip 동일성 |
| 적재 멱등 | `on conflict do nothing` | `MERGE ... WHEN NOT MATCHED THEN INSERT` 또는 `DuplicateKeyException` 처리 | 낮음 | 재적재 시 행 수 불변 |
| 조회 방언 | `select exists`, `join lateral ... on true`, `limit 1`, `is not distinct from` | `CASE WHEN EXISTS(...) THEN 1 ELSE 0 END`, `CROSS APPLY`/`LATERAL`, `FETCH FIRST`, `(a = b OR (a IS NULL AND b IS NULL))` | 낮음 | 기존 통합 테스트 |
| 부분 인덱스 (V3) | `WHERE status = 'SUCCEEDED'` | 함수 기반 인덱스 | 낮음 | 후순위 |

### 3. 전환 범위와 순서

- **2단계 전환 (채택):** 1단계는 우선 흐름에 필요한 것만: R-01~R-05 기반(감사, 권한, 가드)과 내부 공문 테이블 11개(V4, V5 내용). 2단계는 공개 상품 테이블 13개(V1~V3 내용)로 후순위이며 자동 확정하지 않는다. 근거: 중도상환수수료 공문 v1(rule 2개), v2(rule 3개)의 `public_cross_check`가 모두 null이다. 셀러론 공문은 `internal_notice_reference`가 `public_product`와 `public_snapshot`을 FK로 참조하므로 1단계에서는 적재하지 않는다([자산 audit 5절 쟁점 2](../tasks/TASK-000_자산-audit-초안.md) S1-b 채택). 1단계 synthetic baseline은 `SIN-PREPAYMENT-FEE` family만 포함하고 FK 완화는 하지 않는다.
- **전환 기간 공개 기능 비활성 계약 (채택):** 1단계와 2단계 사이에 `/api/v1/public-products/**`와 공개 baseline importer를 명시적으로 비활성화한다.
  - 설정: `trust-agent.public-product.enabled=false`(기본값, 1단계). `true`는 2단계 완료 후에만 허용.
  - 응답: 비활성 상태의 공개 endpoint 호출은 `503`, Problem Detail `code=FEATURE_UNAVAILABLE`, `traceId` 포함, 본문에 활성화 조건 설명 없음(내부 정보 비노출).
  - importer: `baseline-import.enabled=true`로 기동하면 `FEATURE_UNAVAILABLE`로 즉시 종료하고 적재하지 않는다.
  - readiness: 공개 기능 비활성은 `databaseConnectivity`, `schemaCompatibility`, liveness에 영향을 주지 않는다. 내부 공문 endpoint는 정상 동작한다. 이 세 가지를 통합 테스트로 고정한다.
  - 코드 삭제는 하지 않는다. PostgreSQL과 Oracle을 동시에 지원하는 이중 구현은 만들지 않는다.
- Java 변경 범위 **목표**는 SQL을 포함한 8개 클래스(`RuntimeDatasourceConfiguration`, `BaselineImportDatasourceConfiguration`, `SyntheticInternalImportConfiguration`, `ReadinessDatabaseClient`, `SyntheticInternalImporter`, `BaselineImporter`, `InternalPolicyApplicableRepository`, `PublicProductObservedStateRepository`)이다. 이는 목표이며 완료 기준이 아니다. 다른 클래스를 바꿔야 하면 이유와 영향 범위를 Task에 설명한다. 비활성 계약 때문에 컨트롤러와 설정 클래스 변경이 필요할 것으로 예상한다.
- 빌드 의존성은 Oracle JDBC, Flyway Oracle, Testcontainers Oracle 모듈로 교체하고 `gradle.lockfile`과 `gradle/verification-metadata.xml`을 재생성한다. 정확한 좌표와 버전은 TASK-003에서 확인한다.
- 테스트 전략: 기존 Java 통합 테스트 8개 클래스의 업무 단언은 명세로 재사용한다. PostgreSQL catalog와 SQLSTATE를 검증하는 두 클래스는 2.2절 규칙 ID별로 Oracle 테스트를 새로 쓴다. 대체 검증이 통과하기 전에는 기존 검증 근거를 임의로 제거하지 않는다. 각 테스트는 보장하는 R-ID를 이름이나 주석에 적는다.
- 데이터 이행은 없다. 운영 데이터가 없고 baseline은 커밋된 JSON에서 importer로 다시 적재한다.

### 4. 과거 ADR과의 대체 관계 (채택)

| ADR | 관계 | 내용 |
|---|---|---|
| ADR-001 | 유지 | DB 무관 |
| ADR-002 | 유지 + 보완 | dataset class 구분은 Elasticsearch 인덱스 문서와 metadata filter에도 적용 |
| ADR-003 | 유지 | Agent와 LangGraph workflow에도 승인 권한 없음 |
| ADR-004 | 유지 | 저장 backend 중립 |
| ADR-005 | 무관 | 이미 ADR-006으로 대체됨 |
| ADR-006 | 유지 | 개념 계층과 freshness는 DB 무관 |
| ADR-007 | **부분 대체** | 대체: 기술 기준선의 DB와 테스트 항목, "PostgreSQL 18.6 선택 이유" 절 전체, 버전 고정 정책의 PostgreSQL 이미지 문구, DB schema 원칙의 `timestamptz`/`bigint`/`jsonb::text`/`ON CONFLICT` 표현, append-only 정책의 구현 수단(trigger, event trigger, catalog 검사, NOLOGIN audit owner, transaction-local setting, `log_statement`), schema 생성 절의 `ddl_command_end`/`trust_agent_protected_tables()`/PostgreSQL Testcontainer, 운영 전제의 `statement_timeout`/NOLOGIN importer/PostgreSQL 백업, CI의 PostgreSQL Testcontainers, 대안 절의 PostgreSQL 17/H2/JSONB 근거. **유지:** 단일 Spring Boot 모듈 구조, Java 21/Boot/Gradle 선택 기준, JdbcClient와 Flyway 단일 도구 원칙, append-only와 break-glass 요구사항, baseline importer 규칙, 조회 API와 시간 분리 계약, Problem Detail과 trace ID, readiness 분리, 운영 전제의 DB 중립 부분 |
| ADR-008 | **부분 대체** | 대체: "ADR-007에서 이어받는 원칙"의 강제 항목 문구("PostgreSQL constraint와 trigger" → "DB 제약과 trigger"), 사람 검토 결정 절의 schedule 구현 문장(`btree_gist`, exclusion constraint, `daterange`, partial unique index, 관리형 PostgreSQL 확인), 저장 분류 절의 NOLOGIN bundle, 결과 절의 "PostgreSQL 통합 테스트" 완료 조건. **유지:** 권위와 범위, 두 종류의 승인 분리, 시간 모델, 적용 공문 선택 4조건과 AMBIGUOUS, 상태 우선순위와 정답표, rule/proposal/validation/decision/approved checklist 분리, R-09~R-12 불변식, lock은 보조 수단, 인증 없는 단계의 경계, API 계약, 오류와 감사 |

### 5. Evidence 표기

- `docs/evidence/DAY_04A`, `DAY_04B`, `DAY_04C`, `DAY_04`, `DAY_05A`, `DAY_05B`, `FINAL_PROPOSAL_ALIGNMENT`의 Java/Gradle 결과와 PostgreSQL 메커니즘 서술은 "PostgreSQL 18.6 Testcontainers 기준 Pre-SDLC 증거이며 Oracle 기준에서는 재검증되지 않음"으로 표기한다. 문서를 삭제하거나 덮어쓰지 않고 머리에 표기만 추가한다. 커밋된 evidence의 실행 날짜는 유지한다.
- 특히 DAY_05B의 "선형성은 V4의 partial unique와 unique 및 FK가 DB의 모든 쓰기 경로에서 보증하므로 서비스가 chain 전체를 재검증하지 않는다"는 문장은 Oracle에서 R-09, R-11, R-12를 다시 구현하고 테스트하기 전까지 근거가 없다.
- Python 결과(58개)와 계약, fixture는 DB 무관이므로 유효하다.
- 자산 audit 2절의 로컬 재검증(Python 58, Java 82 통과)도 PostgreSQL 기준이다.

### 6. 상세 결정이 부족한 영역 (별도 ADR 채택)

CLAUDE.md에 방향은 있으나 과거 ADR에 상세 결정이 없다. 별도 ADR로 작성한다.

- **ADR-010 (예정):** 검색 기준선. Elasticsearch BM25 + dense vector k-NN + metadata filter + reranker가 baseline이다. 인덱스는 업무 원장이 아니며 Oracle에서 전달되는 방식(Outbox 등), 재색인, 멱등성, dataset class metadata, 골든셋과 Recall@5/MRR 사전 지표, 단순 Hybrid와 Ontology-aware Retrieval 비교. baseline 구성 요소를 임의로 축소하지 않는다.
- **ADR-011 (예정):** Core Tool API 경계. FastAPI AI 서비스는 Oracle에 직접 접근하지 않고 Core의 read-only Tool endpoint만 호출한다. 서비스 인증, allowlist, 입력/출력 schema, 최소 데이터, 감사 추적.
- Redis: 구체적 사용처가 확인될 때까지 보류. DB 제약을 대체하지 않는다는 원칙만 재확인.
- LangGraph: 상태, 분기, 재시도가 필요한 workflow에만 쓴다. 적용 대상은 AI 서비스 Task에서 정한다.

## 검토한 대안

- **PostgreSQL 유지:** CLAUDE.md와 사용자 결정에 반한다. 검토하지 않는다.
- **두 DB 동시 지원:** 이중 구현과 이중 테스트 비용이 1인 범위를 넘고, 제약과 trigger 같은 DB 수준 강제를 추상화할 수 없다.
- **R-12를 서비스 계층 검사로만 보장:** 두 세션이 동시에 겹치는 구간을 넣을 때 서비스 검사는 둘 다 통과시킬 수 있다. DB 수준 lock과 trigger 검사를 요구한다.
- **25개 테이블 일괄 이식:** 우선 흐름과 무관한 13개 테이블과 Java 약 1,700 LOC가 critical path에 들어간다. 2단계 분리를 채택했다.
- **H2 또는 임베디드 DB 테스트:** Oracle 전용 trigger와 권한을 검증할 수 없다. ADR-007의 배제를 유지한다.

## 위험과 제한사항

- R-12 후보는 동시성 테스트 없이는 신뢰할 수 없다. TASK-003에서 먼저 검증한다.
- R-04 후보는 Oracle DDL의 암묵 commit 의미 때문에 trigger 실패 시 동작을 실험으로 확인해야 한다.
- R-02의 행 전체 JSON 감사는 테이블별 trigger가 필요해 테이블이 늘 때마다 migration이 커진다. 대표 범위에서 수작업 검증 후 생성 방식 도입 여부를 다시 판단한다.
- Oracle 컨테이너는 PostgreSQL보다 기동이 느릴 것으로 예상된다. 측정 후 CI timeout(현재 20분)을 조정한다.
- ojdbc의 `OffsetDateTime` 매핑, 빈 문자열과 NULL 동일 취급, `DATE`의 시각 포함, `BOOLEAN` 지원 여부는 기존 테스트가 잡아내지 못하는 의미 차이를 만들 수 있다. 각 항목에 테스트를 둔다.
- 개발 머신(arm64)과 CI(x86_64 예상)의 아키텍처가 달라 같은 이미지 태그라도 digest가 다를 수 있다. 아키텍처별 digest를 기록한다.
- 이 ADR의 Oracle 후보는 일반 지식 기반이며 저장소에서 검증되지 않았다.

## 판단 기록

- 결정자: 사용자 / 검토 대상: 이 ADR 미커밋 초안

| 제안 ID | 내용 | 판단 | 이유 / 승인 범위 |
|---|---|---|---|
| 제안 1 | Oracle 23ai 기능 사용 허용, 19c 비목표 | **수정** | 19c 비목표는 유지. 23ai 고정은 거절. CPU 아키텍처, 이미지 공급처, JDBC/Flyway/Testcontainers 호환을 확인한 뒤 버전과 digest를 제안 |
| 제안 2 | 내부 공문 먼저, 공개 상품 후순위의 2단계 전환 | **채택** | 우선 흐름 집중 |
| 제안 3 | 전환 기간 공개 endpoint와 importer 비활성화 | **채택 + 조건** | 응답과 설정을 계약으로 정하고 내부 공문 기능의 기동과 readiness에 영향 없음을 보장 |
| R-12 수단 | lock + trigger 검사 | **검증 후보로 채택** | 실제 Oracle 동시성 테스트 전에는 보장 수단으로 기록하지 않음 |
| R-02 감사 trigger 생성 방식 | 수작업 vs 생성 | **수정** | 작은 대표 범위에서 수작업 검증 후 재판단 |
| R-01~R-18 | 업무 불변식 목록 | **채택 + 수정** | 절대적 표현 수정. 보호 대상 계정, schema 소유자, 허용 권한, 최고 관리자 경계와 검증 가능한 보장 범위 명시(2.1절) |
| R-04 | Oracle DDL 보호 방식 | **조건** | 공식 문서와 실제 실험으로 검증 |
| 대체 관계 | ADR-007/008 부분 대체 | **채택** | 업무 규칙 유지, PostgreSQL 수단 대체 |
| ADR 분리 | ADR-010 검색, ADR-011 Core Tool API | **채택 + 수정** | "결정 없음"이 아니라 "방향은 있으나 상세 결정 부족"으로 표현 |
| 변경 범위 | 35개 클래스 변경 없음, 8개 클래스 한정 | **수정** | 목표로만 두고 완료 기준에서 제외. 필요한 변경은 이유와 영향 범위 설명 |

인간 검수와 Explainability Gate: **미기록.** 방향 채택은 검수 통과가 아니다.
