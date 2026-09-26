# Core Service

Spring Boot 기반 핵심 서비스입니다. Day 4a의 애플리케이션 골격, PostgreSQL schema,
append-only 권한·감사 trigger, datasource timeout과 readiness component에 이어 Day
4b의 baseline importer를 구현했습니다.

아직 구현하지 않은 범위는 공개 상품 조회 endpoint, freshness와 confirmation
policy입니다. 일반 서버 기동 중에는 baseline importer bean을 만들거나 데이터를 자동
적재하지 않습니다.

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
`docs/evidence/DAY_04A_EVIDENCE.md`와 `docs/evidence/DAY_04B_EVIDENCE.md`에 기록합니다.

Management endpoint는 기본적으로 `127.0.0.1:8081`에 별도로 열립니다. 배포 환경에서
address를 바꿀 때는 외부 ingress에 노출하지 않고 내부 probe와 운영자 경로만 허용해야
합니다. `prod` profile은 DB URL, username, password와 기대 schema version이 모두
명시되지 않으면 기동하지 않습니다.
