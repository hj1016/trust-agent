# 넷째 날 A단계 완료 증거

## 상태

로컬 구현과 검증 완료, GitHub Actions 실행 전

CI 결과가 없는 상태에서는 Day 4a 전체를 최종 완료로 표시하지 않습니다. PR에서
`Python contracts`와 `Gradle tests`가 모두 통과하면 원격 검증까지 완료됩니다.

## 서비스에서 담당하는 역할

Day 4a는 공개 상품 기록을 실제 애플리케이션이 안전하게 저장하고 조회할 수 있도록
Spring Boot 실행 기반과 PostgreSQL schema를 제공합니다. 데이터 적재와 조회 기능보다
먼저 다음 경계를 DB와 실행 환경에 고정합니다.

- Java 21과 Gradle Wrapper 기반 재현 가능한 빌드
- 공개 상품 pipeline record를 보존할 14개 관계형 테이블
- Runtime, migration과 maintenance role 분리
- 일반 경로의 update·delete·truncate 차단과 승인된 정비 변경의 자동 audit
- DB 연결 장애와 schema version 불일치를 구분하는 readiness
- DB와 무관한 liveness

## 필요한 이유

Importer와 조회 API를 먼저 만들면 ID, 외래 키, append-only 원칙과 운영 권한이 Java
코드의 관례에만 남습니다. Day 4a에서 Flyway와 PostgreSQL 권한을 먼저 검증하면 Day 4b
적재 로직이 잘못되더라도 기존 감사 기록을 조용히 바꾸거나 지울 수 없습니다.

## 구현 결과

- Spring Boot 4.1.1, Java toolchain 21과 Gradle Wrapper 8.14.5 골격을 추가했습니다.
- Wrapper distribution과 Wrapper JAR을 공식 SHA-256으로 고정했습니다.
- dependency lockfile과 SHA-256 verification metadata를 커밋 대상으로 생성했습니다.
- PostgreSQL 18.6 Testcontainer image를 multi-platform digest로 고정했습니다.
- Flyway V1에서 14개 테이블, 기본 키, 외래 키, enum·ID·자료형 `CHECK`와
  `source_record_hash`를 정의했습니다.
- Runtime role에는 필요한 select·insert만 허용하고 DDL·update·delete를 허용하지
  않았습니다.
- Migration role은 schema 변경을 수행할 수 있지만 보호 trigger를 disable, enable 또는
  drop하지 못하도록 막았습니다. 모든 DDL이 끝날 때 14개 테이블의 row·truncate guard
  28개를 PostgreSQL catalog에서 검사하며 하나라도 비활성이면 DDL을 rollback합니다.
- Maintenance role은 transaction-local ticket ID, 사유와 작업자 ID가 모두 있을 때만
  update·delete할 수 있습니다. 허용된 변경의 primary key, 전후 JSON과 SHA-256은
  `maintenance_change_audit`에 자동 기록됩니다.
- `maintenance_change_audit`은 별도 NOLOGIN audit owner가 소유하며 maintenance role도
  변경하거나 삭제할 수 없습니다. 감사 JSON 시각은 session timezone과 무관하게 UTC
  ISO 형식으로 고정합니다.
- `databaseConnectivity`와 `schemaCompatibility` readiness component를 분리하고
  liveness에서 DB를 제외했습니다. 별도 management port의 실제 HTTP 응답에 component와
  운영 코드를 노출합니다.
- Runtime datasource 기본값은 connection timeout 2초, validation timeout 1초,
  statement timeout 5초, readiness query timeout 1초와 maximum pool size 5입니다.
- Python과 Java가 함께 읽는 canonical JSON hash fixture를 추가하고 양쪽에서
  부동소수점 입력을 거부했습니다.
- `prod` profile의 DB·schema 필수 설정 누락을 거부하고 설정한 schema version이
  classpath의 최신 Flyway migration과 일치하는지 기동 시 확인합니다.
- Migration baseline ExtractionAttempt 5건에 `attempted_at_source`를 추가했습니다.
  실제 측정값이 아니라 Observation 시각에서 복원한 값임을
  `BACKFILLED_FROM_OBSERVATION`으로 표시했습니다.

## 검토한 대안

- JPA와 Hibernate DDL: 변경 감지와 자동 schema 생성이 append-only 경계를 흐릴 수 있어
  사용하지 않았습니다.
- H2 테스트: PostgreSQL role, trigger, `timestamptz`와 Flyway 동작을 대신할 수 없어
  사용하지 않았습니다.
- 애플리케이션 코드에서만 update·delete 금지: DB 직접 접근을 막지 못하므로 권한과
  trigger를 함께 사용했습니다.
- 모든 readiness 실패를 하나로 표시: 일시적 연결 장애와 재배포가 필요한 schema
  불일치를 구분할 수 없어 component를 나눴습니다.
- Maintenance를 위해 trigger drop: 변경 흔적이 남지 않으므로 transaction 설정과 자동
  audit을 사용하는 break-glass 경로를 선택했습니다.

## 위험과 제한사항

- 최초 role과 event trigger 생성은 PostgreSQL의 높은 권한이 필요한 bootstrap
  작업입니다. 실제 관리형 DB에서 허용되는 권한 모델은 운영 배포 전에 별도로 검증해야
  합니다.
- DB superuser와 audit owner 권한은 보호 trigger와 event trigger를 우회할 수 있습니다.
  Audit owner는 어떤 로그인 계정에도 부여하지 않으며 이 경계는 DB DDL 감사 로그와
  조직 승인 절차로 별도 통제해야 합니다.
- PostgreSQL image digest는 재현성을 높이지만 새 보안 수정 반영 시 의도적으로
  갱신하고 통합 테스트를 다시 실행해야 합니다.
- 현재 API 업무 endpoint와 baseline importer는 없으므로 Day 4b나 Day 4c 완료를
  의미하지 않습니다.
- GitHub Actions 결과는 branch push 전이므로 아직 evidence가 없습니다.

## 테스트 evidence

Private artifact 포함 Python 검증:

```text
Ran 51 tests

OK
```

Public Git 조건 Python 검증:

```text
Ran 51 tests

OK (skipped=3)
```

Skip 3건은 private artifact hash, golden 재추출과 deterministic migration 재현입니다.
나머지 48건은 통과했습니다.

Java clean·offline 검증:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew clean test bootJar --offline --no-daemon
```

```text
BUILD SUCCESSFUL
19 tests completed
```

Suite별 결과는 `PublicProductSchemaIntegrationTest=7`,
`CoreApplicationIntegrationTest=6`, `RuntimeDatasourcePropertiesTest=4`,
`CanonicalJsonHasherTest=2`이며 실패와 skip은 없습니다.

이 실행은 다음 항목을 자동 검증합니다.

- 빈 PostgreSQL에서 Flyway V1 성공 및 두 번째 migration 0건
- 애플리케이션 테이블 정확히 14개와 schema version 1
- 보호 대상 14개 테이블의 row·truncate guard 28개 활성 상태
- Runtime role의 update·delete·DDL 거부와 maintenance role 비상속
- Runtime role이 maintenance 설정값을 흉내 내도 변경 거부
- Maintenance 설정 누락 시 거부, 세 값이 모두 있으면 변경 허용
- Audit의 primary key, ticket, 사유, 작업자, 전후 JSON과 해시 기록
- Primary key를 찾을 수 없는 보호 대상의 변경 거부
- 서로 다른 session timezone에서도 UTC 감사 JSON과 동일 hash 생성
- Maintenance role의 audit record 수정·삭제 거부
- Migration role의 직접·동적 SQL trigger disable, drop과 truncate 거부
- application context 기동, datasource timeout과 pool 설정
- 5초 `statement_timeout`에 의한 10초 query 중단
- liveness의 DB 비의존성과 두 readiness component 분리
- 별도 management port의 readiness HTTP 응답에서
  `SCHEMA_VERSION_MISMATCH`와 component 구분 확인
- `prod` 필수 설정과 classpath 최신 Flyway version 불일치 거부
- 최소 Actuator endpoint와 비밀값 로그 비노출
- Python·Java canonical JSON 및 SHA-256 일치, 양쪽의 부동소수점 거부
- executable Spring Boot jar 생성

공식 Gradle checksum과 Maven Central만 신뢰하는 repository 설정을 확인한 뒤 최초
verification metadata를 생성했습니다. 이후 CI는 새로운 checksum을 자동 승인하지
않으며 dependency 변경 시 lockfile과 verification metadata diff를 함께 검토합니다.

PR 최초 실행에서는 깨끗한 GitHub runner가 plugin classpath의 부모 POM과 BOM module
metadata를 추가로 해석하면서 미등록 artifact 3건을 차단했습니다. 기존 checksum
불일치가 아니라 허용 목록 누락이었습니다. 별도의 빈 `GRADLE_USER_HOME`에서 CI와 같은
`test bootJar` 전체 configuration을 해석해 metadata를 보강했습니다. Diff는 111줄 추가,
삭제 0줄이며 새 항목은 POM과 module metadata뿐이고 JAR 추가는 없습니다. 같은 임시
Gradle home에서 생성 옵션 없이 `clean test bootJar`를 다시 실행해 strict dependency
verification, Java 테스트 19개와 executable jar 생성을 모두 통과했습니다.

2026-09-25 재검토에서 `current_query()` 문자열 검색을 우회해 trigger를 비활성화할 수
있는 문제를 재현했습니다. 문자열 검사를 제거하고 실제 catalog 상태 검사로 교체했으며,
같은 소유권으로 가능한 `TRUNCATE` 우회도 추가로 차단했습니다. 이 보강 전 14개 테스트
결과는 당시 evidence로 남기되 현재 완료 판단은 위 19개 테스트 결과를 기준으로 합니다.

## 다음 작업과의 연결

PR에서 두 CI job이 통과하고 검토가 끝나면 Day 4a를 최종 완료 처리합니다. 그 다음 Day
4b에서 승인된 JSON 경로만 읽는 baseline importer, record hash 비교, 전체 transaction,
정확한 적재 건수와 `BASELINE_MISMATCH`를 구현합니다.
