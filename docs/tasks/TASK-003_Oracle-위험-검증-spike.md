# TASK-003 Oracle 전환 위험 검증 spike

- 상태: **중단, 실험 미완료.** Core 업무 DB PostgreSQL 유지 결정(ADR-009)으로 Oracle 실행 승인이 철회됐다. 산출물은 보존하고 커밋, push, 삭제, 재실행하지 않는다.
- 담당자 / 인간 결정자: AI(실험 설계, 구현, 실행, 기록) / 사용자(범위, AC 확정, 결과 판단, 검수)
- 요구사항 출처: [ADR-009](../adr/ADR-009-oracle-core-database.md) 판단 기록(제안 1 수정, R-12 검증 후보, R-02 대표 범위 수작업, R-04 실험 검증), [PLAN-001](PLAN-001_중도상환수수료-흐름-Task-분할-초안.md) 제안 B 채택
- 관련: ADR-009 2.3절 후보 표, TASK-004

## Goal / 관련 요구사항

- Goal: ADR-009의 Oracle 구현 수단 후보 중 설계 위험이 높은 것과 환경 호환을 core-service를 건드리지 않고 실험으로 확인한다. 결과는 각 후보를 "검증됨 / 대안 필요 / 미지원"으로 바꾸고 TASK-004의 설계 입력이 된다.
- 관련 요구사항: ADR-009 1절 확인 항목 표, 2.3절 R-02, R-04, R-12, 의미 차이 항목.
- 사용자: 사용자(결과 판단), TASK-004 수행자.
- 사전조건: Docker 실행. 네트워크로 컨테이너 이미지와 Maven 의존성 pull 가능.
- 입력: 후보 이미지 2종(커뮤니티 `gvenzl/oracle-free`, Oracle 공식 `container-registry.oracle.com/database/free`), 후보 의존성(Oracle JDBC, Flyway Oracle, Testcontainers Oracle).
- 업무규칙: 실험 코드는 별도 Gradle 하위 프로젝트 `spikes/oracle-core`에 둔다. core-service 소스와 PostgreSQL 테스트는 변경하지 않는다. 결과를 ADR-009 본문에 "보장 수단"으로 쓰는 것은 사용자 판단 뒤다.
- 상태전이: 후보 "검증 대기" → 실험 → "검증됨 / 대안 필요 / 미지원". 금지: 실험 없이 상태 변경.
- 데이터 영향: 실험용 schema만. 업무 데이터 없음.
- API: 없음.
- 트랜잭션: 실험 항목 자체가 트랜잭션 의미 검증.
- 권한: 실험용 Oracle 계정(소유 schema, runtime, maintenance, migration 역할 모사).
- 실패 시나리오: 이미지가 한 아키텍처만 지원 → 다른 공급처 시도, 둘 다 실패면 "미지원" 기록과 대안(x86 에뮬레이션, 원격 DB) 제시. R-12 동시성 실패 → 대안 설계 2개 이상 제시.
- 테스트: 아래 AC가 곧 테스트 목록. 각 테스트 이름에 R-ID.
- Out of Scope: core-service 수정, 운영 edition 결정, 공개 상품 테이블, 성능 측정(기동 시간만 기록).

## 요구사항과 범위

업무 문제: Oracle에 exclusion constraint가 없고, DDL은 암묵 commit을 수반하며, 행 전체 JSON 감사와 세션 변수 모델이 PostgreSQL과 다르다. 이 넷이 TASK-004 설계를 좌우하는데 저장소에서 검증된 바가 없다. 또 개발 머신(arm64)과 CI(x86_64 예상)의 이미지 호환이 확인되지 않았다.

포함: 환경 호환(E), 기간 중첩과 동시성(R-12), DDL 보호(R-04), 감사 trigger 대표 범위(R-02), 의미 차이(M).
제외: R-05 전체 권한 매트릭스(TASK-004), R-16 JSON null 상세(TASK-004에서 C3), Flyway migration 작성(빈 migration 실행만).

기존 자산: 없음(NEW). PostgreSQL `V4` EXCLUDE 정의와 `SyntheticInternalSchemaIntegrationTest`의 거부 case를 실험 명세로 재사용한다.

## Acceptance Criteria (구현 전 고정)

결정자 / 판단 근거 / 검토 대상 revision 또는 PR: 사용자 / ADR-009 판단 기록 / 이 Task 초안. **AC 확정됨.** 각 AC의 결과는 "통과 / 실패 / 미지원"과 evidence 경로로 기록하며 실패와 미지원도 유효한 결과다. 재현 방법, 결과, 보장 범위, 대안을 남긴다. 의존성 버전과 이미지 선택은 공식 자료와 실제 호환 확인으로 결정하고 근거를 남긴다.

### E. 환경과 호환

| ID | 입력/상황 | 기대 결과 | 검증 방법 | 결과/evidence |
|---|---|---|---|---|
| E-01 | 로컬 arm64 Docker | 후보 이미지 중 하나 이상이 arm64에서 pull되고 기동해 JDBC 접속 성공. 이미지 이름, 태그, **arm64 digest** 기록 | Testcontainers 기동 로그, `docker inspect` | 미검증 |
| E-02 | CI `ubuntu-latest` | 같은 이미지 태그가 x86_64에서 기동. 러너 `uname -m` 출력과 **amd64 digest** 기록. 기동 소요 시간 기록 | CI 실행 링크 | 미검증 |
| E-03 | 의존성 해석 | Oracle JDBC, Flyway Oracle, Testcontainers Oracle 모듈의 정확한 좌표와 버전이 Gradle에서 해석되고 lockfile에 기록. Testcontainers 2.0.5 계열 모듈 이름 확인 | `gradle dependencies` 출력 | 미검증 |
| E-04 | Flyway | 빈 migration 디렉터리로 `flyway_schema_history` 생성 성공. `success` 컬럼 타입 기록 | 테스트 | 미검증 |
| E-05 | 버전 기능 | 선택한 이미지의 DB 버전 문자열 기록. `BOOLEAN` 컬럼, 네이티브 `JSON` 타입, FROM 없는 SELECT, `IS NOT DISTINCT FROM` 각각 지원 여부를 실행으로 확인 | 테스트 | 미검증 |
| E-06 | 버전 제안 | E-01~E-05 결과로 ADR-009 1절에 넣을 버전과 digest(아키텍처별) 제안 1건 작성. 19c 비목표 유지 | 문서 | 미검증 |

### R-12. 기간 중첩 금지와 동시성

실험 schema: `family(id)`, `schedule_revision(id, family_id, supersedes_id UNIQUE)`, `schedule_entry(revision_id, family_id, effective_from DATE, effective_to DATE NULL)`. 후보 수단: revision 쓰기 트랜잭션이 family 행(또는 부모 revision 행)을 `SELECT ... FOR UPDATE`로 잠근 뒤 PL/SQL(compound trigger 또는 패키지 프로시저)로 같은 revision 안 겹침 검사.

| ID | 입력/상황 | 기대 결과 | 검증 방법 | 결과/evidence |
|---|---|---|---|---|
| R12-01 | 같은 revision, 같은 family, `[2026-09-15, 2026-10-01)`과 `[2026-10-01, null)` | 삽입 성공(맞닿음 허용) | 테스트 | 미검증 |
| R12-02 | `[2026-09-15, 2026-10-01)`과 `[2026-09-30, null)` | 두 번째 삽입 거부, 오류 식별 가능(사용자 정의 오류 번호) | 테스트 | 미검증 |
| R12-03 | `[2026-09-15, null)`과 `[2026-12-01, 2027-01-01)` | 열린 구간과 겹치므로 거부 | 테스트 | 미검증 |
| R12-04 | 다른 revision 또는 다른 family에 겹치는 구간 | 허용 | 테스트 | 미검증 |
| R12-05 | 두 세션이 같은 revision에 겹치는 entry를 동시에 삽입(둘 다 검사 통과 후 커밋 시도 시나리오 포함) | 정확히 하나만 성공, 다른 하나는 오류 또는 lock 대기 후 거부. 실패 세션의 트랜잭션은 rollback되어 부분 행 없음 | 두 스레드 + 두 커넥션 테스트, 반복 20회 | 미검증 |
| R12-06 | lock 없이 trigger 검사만 | R12-05가 실패함을 보여 lock 필요성 입증(음성 대조) | 테스트 | 미검증 |
| R12-07 | `effective_to < effective_from` 또는 시각 포함 DATE | CHECK로 거부 | 테스트 | 미검증 |

### R-04. DDL 보호

| ID | 입력/상황 | 기대 결과 | 검증 방법 | 결과/evidence |
|---|---|---|---|---|
| R04-01 | migration 역할 계정이 보호 테이블의 가드 trigger `DROP TRIGGER` | 거부. trigger 존재 유지 | 테스트 | 미검증 |
| R04-02 | 같은 계정이 `ALTER TRIGGER ... DISABLE` | 거부. `USER_TRIGGERS.STATUS = 'ENABLED'` 유지 | 테스트 | 미검증 |
| R04-03 | 같은 계정이 `ALTER TABLE ... DISABLE ALL TRIGGERS` | 거부 | 테스트 | 미검증 |
| R04-04 | DDL 시스템 trigger가 예외를 낼 때 같은 세션의 선행 미커밋 DML | 공식 문서 확인 + 실험: 선행 DML이 commit되는지 rollback되는지 결과 기록. 어느 쪽이든 "사실"로 기록하고 TASK-004 설계 제약으로 전달 | 테스트, 문서 인용 | 미검증 |
| R04-05 | 보호 대상 밖 계정(schema 소유자) | 가드 해제가 가능함을 확인하고 "범위 밖"으로 기록(ADR-009 2.1절 경계의 사실 확인) | 테스트 | 미검증 |

### R-02. 감사 trigger 대표 범위

| ID | 입력/상황 | 기대 결과 | 검증 방법 | 결과/evidence |
|---|---|---|---|---|
| R02-01 | runtime 계정 UPDATE/DELETE | 거부 | 테스트 | 미검증 |
| R02-02 | maintenance 계정, application context 세 값(ticket, reason, actor) 중 하나 비움 | 거부 | 테스트 | 미검증 |
| R02-03 | maintenance 계정, 세 값 설정 후 UPDATE 1행 | 허용. 감사 테이블에 1행: table_name, operation, PK JSON, old/new record JSON, 두 hash, actor, ticket, reason, 시각 | 테스트 | 미검증 |
| R02-04 | 같은 행을 NLS와 session timezone을 바꿔 두 번 직렬화 | hash 동일 | 테스트 | 미검증 |
| R02-05 | maintenance 계정이 감사 행 UPDATE/DELETE | 거부 | 테스트 | 미검증 |
| R02-06 | 대표 테이블 2개에 trigger 수작업 작성 | 테이블당 trigger 코드 줄 수와 작성 시간 기록. 25개 테이블로 외삽한 반복량 추정을 Task에 기록(생성 방식 재판단 입력) | 기록 | 미검증 |

### M. 의미 차이

| ID | 입력/상황 | 기대 결과 | 검증 방법 | 결과/evidence |
|---|---|---|---|---|
| M-01 | `VARCHAR2 NOT NULL` 컬럼에 빈 문자열 삽입 | NOT NULL 위반으로 거부(빈 문자열이 NULL로 취급됨을 사실로 기록) | 테스트 | 미검증 |
| M-02 | `TIMESTAMP WITH TIME ZONE`에 Java `OffsetDateTime`(UTC) 저장 후 session timezone 변경해 조회 | `Instant` 동일 | 테스트 | 미검증 |
| M-03 | `DATE` 컬럼에 시각 포함 값 | `CHECK (col = TRUNC(col))`로 거부. Java `LocalDate` round-trip 동일 | 테스트 | 미검증 |
| M-04 | boolean | 선택 이미지에서 `BOOLEAN` 지원 시 Java `boolean` round-trip. 미지원 시 `NUMBER(1)` 대안 round-trip | 테스트 | 미검증 |
| M-05 | JSON 컬럼 | 문자열 바인딩 저장 후 `JSON_SERIALIZE` 읽기가 canonical 비교에서 동일. JSON 스칼라 `null` 저장과 SQL NULL 구분 가능 여부 | 테스트 | 미검증 |
| M-06 | `MERGE ... WHEN NOT MATCHED`로 재적재 | 행 수 불변 | 테스트 | 미검증 |
| M-07 | 전용 lock 행 `SELECT FOR UPDATE`로 importer 직렬화 | 두 세션 중 하나 대기 | 테스트 | 미검증 |
| M-08 | JDBC `queryTimeout`으로 10초 query | 제한 시간에 중단 | 테스트 | 미검증 |

### 완료 기준 변경 이력

변경 없음.

## Implementation Plan

1. `settings.gradle`에 `spikes:oracle-core` 추가. 자체 `build.gradle`(Testcontainers Oracle, JDBC, Flyway Oracle, JUnit). core-service 의존 없음. 루트 lockfile 정책에 맞게 하위 프로젝트 lockfile 생성.
2. E 그룹부터. 이미지 2종 × 아키텍처 2종 결과표.
3. R-12 schema와 PL/SQL 후보 작성, R12-01~07.
4. R-04 역할과 시스템 trigger 작성, R04-01~05. 공식 문서 URL 인용.
5. R-02 대표 테이블 2개, R02-01~06.
6. M 그룹.
7. 결과를 `docs/evidence/TASK-003_ORACLE_SPIKE_EVIDENCE.md`에 기록: 환경(이미지, digest, 아키텍처, 드라이버 버전), 명령, AC별 결과, 실패 원인. ADR-009 2.3절 갱신안을 별도 제안으로 제시(사용자 판단 후 반영).
8. CI에 spike job 추가(E-02). 기존 PostgreSQL job은 그대로.

대안: core-service 안에서 바로 실험 → 기존 82개 테스트와 섞여 PostgreSQL 검증 상태가 흔들림. 거절. 로컬에서만 실험 → CI 아키텍처 확인 불가. 거절.
위험: 이미지 pull 크기와 기동 시간이 CI 20분을 넘길 수 있음. 측정해 보고. R-12 후보가 실패하면 대안(entry 단위 lock 테이블, 전체 revision 직렬화 등) 2개 이상 설계안 제시.
인간의 계획 판단 / 승인 범위: **승인.** 1~8단계. commit, push, PR 생성까지. 병합은 사용자. 현재 core-service와 PostgreSQL 검증 상태 보존. 검증된 사실과 미검증 후보 상태는 문서에 바로 반영 가능. 새로운 설계 선택이나 승인 범위 변경은 갱신안 제시 후 판단. spike 코드는 TASK-004 완료 후 삭제 또는 보존을 별도 판단.

## AI 제안 및 인간 판단 기록

### 제안 1: spike를 별도 하위 프로젝트로 분리

**AI 제안**
내용: `spikes/oracle-core` 하위 프로젝트. core-service와 PostgreSQL 테스트 불변.
대안: core-service 테스트 소스 안에 Oracle 테스트 추가.
기대 효과: 기존 검증 상태 유지, 실패 실험이 main 빌드를 깨지 않음.
위험: 하위 프로젝트 1개 추가로 빌드 설정 증가.

**판단**
- [x] 채택
- 판단자 / 검토 대상 revision 또는 PR: 사용자 / 이 Task 초안
- 이유: 현재 core-service와 PostgreSQL 검증 상태 보존

### 제안 2: 결과 반영은 사용자 판단 뒤

**AI 제안**
내용: spike 결과로 ADR-009 2.3절을 바로 고치지 않고 "갱신안"을 제시한다.
**판단**
- [x] 수정
- 판단자 / 검토 대상 revision 또는 PR: 사용자 / 이 Task 초안
- 이유 / 승인 범위: 검증된 사실과 미검증 후보 상태는 문서에 바로 반영해도 된다. 새로운 설계 선택이나 승인된 범위 변경만 갱신안을 제시하고 판단을 받는다.

## Implementation Result (중단 시점)

실제 변경 파일: 로컬 worktree `/Users/faker/Dev/trust-agent-wt/spike`(브랜치 `spike/oracle-core-risk`, 커밋 0건, 원격 없음)의 `settings.gradle`, `gradle/verification-metadata.xml`, `spikes/oracle-core/**` 12개 파일. CI yml 변경은 실행되지 않았다. 파일 목록과 결과는 [TASK-003 spike evidence](../evidence/TASK-003_ORACLE_SPIKE_EVIDENCE.md).

| AC ID | 검증 대상 revision | 실행 명령/절차 | 환경/버전 | 결과(실패/skip 포함) | evidence 경로 |
|---|---|---|---|---|---|
| E-01 | spike worktree (acd572b 기준 미커밋) | `./gradlew :spikes:oracle-core:test --offline` | 로컬 arm64, Java 21, Testcontainers 2.0.5 | **통과.** 기동 15~20초, JDBC 접속, arm64 manifest | evidence 문서 "검증된 사실" |
| E-02 | — | CI 미실행 | — | **미실행** | — |
| E-03 | 같음 | `:spikes:oracle-core:dependencies --write-locks --write-verification-metadata sha256` | Gradle 8.14.5 | **통과.** ojdbc11 23.26.3.0.0, flyway-database-oracle 12.4.0, testcontainers-oracle-free 2.0.5 | 같음 |
| E-04 | 같음 | 테스트 | 같음 | **미실행** (2회 실패 후 수정, 재실행 전 중단) | 같음 |
| E-05 | 같음 | 테스트 | 같음 | **통과.** 지원/미지원 목록 기록. `IS NOT DISTINCT FROM` 미지원 | 같음 |
| E-06 | — | — | — | **미작성** (결정 철회) | — |
| R12-01~07 | 같음 | 테스트 | 같음 | **미실행** (초기화 단계 실패로 본 실험 미도달) | 같음 |
| R04-01~05 | 같음 | 테스트 | 같음 | **미실행** | 같음 |
| R02-01~06 | 같음 | 테스트 | 같음 | **미실행** | 같음 |
| M-01~08 | 같음 | 테스트 | 같음 | **미실행** | 같음 |

## AI self-review

- 검사 범위: 3회 실행 로그, worktree 상태, 컨테이너와 이미지 상태.
- 발견 사항: (1) `GRANT SELECT ON dba_*`는 SYSTEM으로도 ORA-01031이라 `SELECT ANY DICTIONARY`로 수정했다. (2) ojdbc 23의 autocommit `commit()` ORA-17273으로 초기화가 실패해 `commit()` 호출을 autocommit 확인 뒤로 바꿨다. (3) Flyway는 비어 있지 않은 schema에 `baselineOnMigrate`가 필요했다. 세 수정은 파일에 반영됐지만 재실행하지 않았다.
- 미해결: R-12, R-04, R-02, M 그룹 전부. **Oracle 핵심 무결성 검증은 완료되지 않았다.** 이 Task의 어떤 결과도 "Oracle에서 업무 규칙을 보장할 수 있다"는 근거가 아니다.

## 인간 검수와 Explainability Gate

미기록. 중단 결정은 사용자가 했다.

## 결정 기록과 완료

- 최종 결정: **중단.** 결정자: 사용자. 이유: Core 업무 DB PostgreSQL 유지(ADR-009). 승인 범위: 산출물 보존, 커밋/push/삭제/재실행 금지.
- 이전 착수 승인(제안 1, 2 채택)은 이 결정으로 대체됐다. 기록은 위에 그대로 남긴다.
- 후속 Task: 없음. TASK-004(Oracle 전환 1단계)는 취소되며 번호는 재사용하지 않는다.
