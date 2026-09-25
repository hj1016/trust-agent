# Core Service

Spring Boot 기반 핵심 서비스입니다. 현재 Day 4a 범위인 애플리케이션 골격, PostgreSQL
schema, append-only 권한·감사 trigger, datasource timeout과 readiness component를
구현했습니다.

아직 구현하지 않은 범위는 baseline importer, 공개 상품 조회 endpoint, freshness와
confirmation policy입니다. 일반 서버 기동 중에는 baseline을 자동 적재하지 않습니다.

## 검증

Java 21과 Docker daemon이 필요합니다.

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew clean test bootJar --offline --no-daemon
```

테스트는 digest로 고정한 PostgreSQL 18.6 Testcontainer에서 Flyway와 DB 권한을 직접
검증합니다. 운영 DB의 URL, username과 password는 환경 변수로 주입하며 저장소에
커밋하지 않습니다. 구현 범위와 검증 결과는
`docs/evidence/DAY_04A_EVIDENCE.md`에 기록합니다.

Management endpoint는 기본적으로 `127.0.0.1:8081`에 별도로 열립니다. 배포 환경에서
address를 바꿀 때는 외부 ingress에 노출하지 않고 내부 probe와 운영자 경로만 허용해야
합니다. `prod` profile은 DB URL, username, password와 기대 schema version이 모두
명시되지 않으면 기동하지 않습니다.
