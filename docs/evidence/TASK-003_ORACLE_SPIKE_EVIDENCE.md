# TASK-003 Oracle 전환 위험 검증 spike 기록 (중단, 실험 미완료)

> 이 실험은 Core 업무 DB를 PostgreSQL로 유지하기로 결정([ADR-009](../adr/ADR-009-oracle-core-database.md))하면서 중단됐다. Oracle 핵심 무결성(R-12, R-04, R-02) 검증은 완료되지 않았다. 산출물은 보존하되 커밋, push, 삭제, 재실행하지 않는다.

## 검증 대상과 환경

- 검증 대상 revision: `origin/main`(커밋 `acd572b`) 기준 로컬 worktree `/Users/faker/Dev/trust-agent-wt/spike`, 브랜치 `spike/oracle-core-risk`. 커밋 0건, 원격 브랜치 없음.
- 환경: 로컬 macOS arm64, Docker Desktop linux/arm64, Java 21(ms-21.0.11), Gradle 8.14.5, Testcontainers 2.0.5.
- 실행 횟수: 3회. 1회차와 2회차는 초기화 단계 실패, 3회차는 초기화 수정 뒤 실행 직전 중단.
- CI(x86_64) 실행: 없음.

## 보존된 파일 목록 (spike worktree, 미커밋)

| 파일 | 내용 |
|---|---|
| `settings.gradle` (수정) | `spikes:oracle-core` 하위 프로젝트 include 2줄 |
| `gradle/verification-metadata.xml` (수정) | Oracle JDBC, Flyway Oracle, Testcontainers oracle-free와 전이 의존성 체크섬 24줄 추가 |
| `spikes/oracle-core/build.gradle` | java 플러그인, Spring Boot 4.1.1 BOM, 의존성 잠금, JUnit 5 |
| `spikes/oracle-core/gradle.lockfile` | 해석된 버전 고정 |
| `spikes/oracle-core/src/test/java/com/trustagent/spike/oracle/OracleSpike.java` | 공용 컨테이너, 실험 계정 생성, 결과 기록 |
| `.../EnvironmentCompatibilityTest.java` | E-01, E-03, E-04, E-05 |
| `.../ScheduleOverlapR12Test.java` | R12-01~07 (compound trigger + FOR UPDATE 후보, 동시성 20회 반복) |
| `.../DdlProtectionR04Test.java` | R04-01~05 (ON SCHEMA와 ON DATABASE DDL trigger 비교) |
| `.../AuditTriggerR02Test.java` | R02-01~06 (application context, JSON_OBJECT, STANDARD_HASH) |
| `.../SemanticDifferencesMTest.java` | M-01~08 |
| `spikes/oracle-core/src/test/resources/db/spike-empty/.gitkeep` | Flyway 빈 location |

실험 코드 안의 계정 비밀번호는 모두 테스트용 합성 상수이며 실제 자격증명이 아니다. 이 문서에는 적지 않는다.

Docker 이미지(보존): `gvenzl/oracle-free:23.26.3-slim-faststart` (멀티 아키텍처 index `sha256:f5ff19033860d662c821cb04eb10483fa94f14f78eae252d054291ea07028093`, amd64 manifest `sha256:d86d09794ae138a8951e97d7ee010778dcd43088e8d0f83a51002abab8c6d7fd`, arm64 manifest `sha256:b0c7703767ca9403b50c8408d4c6aa824ee6bd93fbed8dc765a9073acf087740`), 기존 `gvenzl/oracle-free:slim`.

## 재현 방법

```bash
# worktree 안에서
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :spikes:oracle-core:test --offline --no-daemon
```

실행하지 않는다. 결정 변경 시에만 사용자 승인 뒤 실행한다.

## 검증된 사실 (실제 실행 결과)

| 항목 | 결과 |
|---|---|
| E-01 컨테이너 기동과 JDBC 접속 | 성공. 기동 15~20초. `jdbc:oracle:thin:@localhost:<port>/freepdb1` |
| E-01 아키텍처 | Docker arch aarch64. 이미지 arm64 manifest 사용 |
| E-01 SYSTEM 로그인 | Testcontainers oracle-free 모듈이 `ORACLE_PASSWORD`를 앱 비밀번호로 덮어씀. SYSTEM은 앱 비밀번호로 로그인됨 |
| E-03 의존성 해석 | Spring Boot 4.1.1 BOM 관리: `com.oracle.database.jdbc:ojdbc11` 23.26.3.0.0, `org.flywaydb:flyway-database-oracle` 12.4.0, `org.testcontainers:testcontainers-oracle-free` 2.0.5. lockfile과 verification metadata 기록 성공 |
| E-03 드라이버 | "Oracle JDBC driver 23.26.3.0.0" |
| E-05 DB 버전 | "Oracle AI Database 26ai Free Release 23.26.3.0.0" |
| E-05 기능 probe 지원 | FROM 없는 SELECT, `BOOLEAN` 컬럼, 네이티브 `JSON` 컬럼, `FETCH FIRST`, `LATERAL`, `CROSS APPLY`, `REGEXP_LIKE` CHECK, 함수 기반 unique index(partial unique 대체), `DEFERRABLE INITIALLY DEFERRED` 순환 FK, IDENTITY 컬럼, `STANDARD_HASH` SHA256 |
| E-05 기능 probe 미지원 | `IS NOT DISTINCT FROM` → ORA-00908 |
| 부수 사실 1 | SYSTEM으로 `GRANT SELECT ON dba_role_privs TO <user>` 실행 시 ORA-01031. SYS dictionary view 객체 권한 부여 불가. 대안은 `SELECT ANY DICTIONARY` 시스템 권한 |
| 부수 사실 2 | ojdbc 23은 autocommit 연결에서 `Connection.commit()` 호출 시 ORA-17273 |
| 부수 사실 3 | Flyway 12.4.0은 비어 있지 않은 schema에 history table이 없으면 "Found non-empty schema(s) ... but no schema history table" 오류. `baselineOnMigrate(true)` 필요 |

## 실행되지 않은 항목 (미검증)

- E-02: CI `ubuntu-latest`(x86_64) 기동, amd64 digest 실측, 기동 시간. CI job은 작성되지 않았다.
- E-04: Flyway history table 생성과 `success` 컬럼 타입(3회차 수정 반영 뒤 미실행).
- E-06: 버전과 digest 제안. 결정 철회로 작성하지 않는다.
- R12-01~07: 기간 중첩 금지, 맞닿음, 열린 구간, 두 세션 동시 삽입, 음성 대조, CHECK. **전부 미실행.** compound trigger + `FOR UPDATE` 후보가 동시성에서 동작하는지 확인되지 않았다.
- R04-01~05: DDL 보호. ON SCHEMA trigger가 다른 사용자의 DDL에 발화하는지, ON DATABASE trigger 실패 시 암묵 commit 의미, 보장 범위 경계. **전부 미실행.**
- R02-01~06: application context 기반 break-glass, `JSON_OBJECT` 감사, hash의 NLS 독립성, 감사 불변, 수작업 trigger 규모. **전부 미실행.**
- M-01~08: 빈 문자열과 NULL, TSTZ round-trip, DATE 시각, BOOLEAN round-trip, JSON null 구분, MERGE 멱등, FOR UPDATE 직렬화, query timeout. **전부 미실행.**

## 결론

이 기록은 "Oracle이 가능하다" 또는 "불가능하다"를 증명하지 않는다. 환경 호환(이미지, 드라이버, 모듈 해석)과 SQL 기능 probe만 확인됐고 업무 불변식 강제는 검증되지 않았다. Core 업무 DB는 PostgreSQL 유지로 결정됐으므로 추가 실행은 하지 않는다.
