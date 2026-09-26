# ADR 007 핵심 서비스와 공개 상품 관측 조회

## 상태

승인

## 배경

Day 3까지 공개 KB 원문을 Snapshot, Observation, ProductTermsVersion,
VersionEvidence, ObservedRateQuote와 처리 감사 기록으로 나눴습니다. Day 4부터는 이
데이터 계약을 실제 애플리케이션과 관계형 DB에 저장하고 조회해야 합니다.

이 ADR은 ADR-006의 데이터 모델을 유지하면서 Core service의 조회 시각과 freshness
판정 방식을 더 구체적으로 정합니다. `asOf`와 현재 평가 시각이 같다는 ADR-006의 암묵적
가정이 있었다면 이 ADR의 `evaluatedAt` 분리가 우선합니다.

이 문서에서 사용하는 주요 이름의 뜻은 다음과 같습니다.

- Snapshot: 수집한 HTML 원문 자체를 해시로 식별한 기록
- Observation: 특정 시각에 특정 상품 페이지를 관측한 사건
- ProductTermsVersion: 가입 대상, 한도, 상환 방법처럼 비교적 안정적인 상품 조건 묶음
- VersionEvidence: 어떤 Observation에서 해당 상품 조건을 확인했는지 잇는 근거
- ObservedRateQuote: 관측 시점에 공개 페이지에 표시된 광고 금리
- CollectionAttempt와 ExtractionAttempt: 수집과 추출을 시도한 결과

이 단계에서 다음 경계를 고정하지 않으면 이후 합성 내부 공문, 상담 준비안과 사람 승인
기능을 추가할 때 DB와 API를 다시 변경할 가능성이 큽니다.

- 공개 페이지에서 관측한 시각과 내부 공문의 업무상 시행일은 다른 시간 의미입니다.
- 마지막으로 확인한 상품 조건이 있어도 더 새로운 Observation의 추출이 실패하거나
  대기 중이면 AI 확정 경로에 사용할 수 없습니다.
- JSON 계약의 ID와 근거 연결을 DB 적재 과정에서 잃으면 Python 처리 흐름부터 Spring
  API까지 감사 이력을 추적할 수 없습니다.
- JPA의 변경 감지나 자동 DDL에 의존하면 추가만 허용해야 하는 감사 기록이 개발자의
  의도와 다르게 수정될 수 있습니다.
- 애플리케이션을 시작할 때마다 저장소 데이터를 자동으로 넣으면 서비스 기동과 데이터
  이전의 책임이 섞입니다.

## 결정

### 애플리케이션 형태

- 하나의 Spring Boot 애플리케이션 안에서 기능별 모듈을 나누는 구조로 시작합니다.
- Day 4에서는 별도 AI 서비스, Spring Batch 실행기와 메시지 브로커를 추가하지
  않습니다.
- 공개 상품 기능은 `publicproduct` 패키지 아래에서 업무 규칙, 유스케이스, API 입력,
  DB 출력을 담당하는 코드를 구분합니다.
- 패키지를 나누는 목적은 나중에 무조건 서비스를 분리하기 위해서가 아니라, 지금 각
  코드의 책임과 의존 방향을 분명히 하기 위해서입니다.
- 핵심 서비스는 대출 승인, 거절, 신용평가, 최종 한도와 최종 금리를 결정하지
  않습니다.

### 기술 기준선

- Java 도구 모음: 21
- Spring Boot: 4.1.1
- 빌드: Gradle Wrapper 8.14.5, Groovy DSL
- DB: PostgreSQL 18.6
- Schema migration: Flyway
- DB 접근: Spring JDBC의 `JdbcClient`와 명시적인 행 변환
- JSON 변환: Jackson
- 설정값 검증: Jakarta Bean Validation
- 테스트: JUnit 5, Spring Boot Test, Testcontainers PostgreSQL
- 운영 상태 확인: Spring Boot Actuator, Micrometer와 구조화 로그

최신 버전이라는 이유만으로 위 기준선을 선택하지 않습니다. 이 프로젝트에서는 다음
순서로 버전을 판단합니다.

1. 감사 근거를 재현할 수 있고 CI에서도 같은 결과를 얻는지
2. 프로젝트가 사용하는 기능과 공식 지원 범위를 충족하는지
3. 보안 수정과 공식 지원 기간이 충분한지
4. 필요한 라이브러리와 테스트 환경이 실제로 호환되는지
5. 새 기능의 이익이 큰 버전 변경과 운영 복잡성보다 큰지

이 기준선은 상용 Spring 지원 계약과 사내 승인 기술 목록이 아직 없다는 전제에서
정합니다. 실제 도입 기업에 승인된 기술 목록, 관리형 DB의 지원 범위 또는 Tanzu
Spring 장기지원 계약이 있다면 그 조직의 기준이 이 초안보다 우선합니다. 기준을
바꿀 때는 이유와 영향을 ADR로 다시 승인합니다. 포트폴리오 시연 환경의 편의가 운영
버전을 결정하는 근거가 되어서는 안 됩니다.

#### Java 21 선택 이유

- Java 21은 장기 지원 버전(LTS)이고 Spring Boot 4.1.1의 공식 지원 범위에 포함됩니다.
- 이 서비스에 필요한 Java의 record, 제한된 타입 계층, 패턴 매칭과 `Instant` 기반 시간 처리를
  제공하므로 더 높은 Java 버전의 기능이 필요하지 않습니다.
- 로컬 컴퓨터의 Java 24는 현재 개발 환경에 설치된 버전일 뿐입니다. Java 도구 모음을
  21로 고정하면 GitHub Actions, 다른 개발자 컴퓨터와 시연 환경에서 같은 바이트코드 기준을
  재현하기 쉽습니다.
- Java 24를 사용해 얻는 이익보다 장기 지원이 아닌 실행 환경을 운영 기준으로 삼는
  비용이 큽니다.

#### Spring Boot 4.1.1 선택 이유

- 이 프로젝트는 기존 Spring 3.x 애플리케이션이나 외부 스타터를 이전하는 작업이
  아니라 핵심 서비스를 새로 만드는 프로젝트입니다. 따라서 4.x 전환 호환성
  비용이 아직 없습니다.
- Day 4 의존성은 Spring Web, JDBC, Validation, Flyway와 Testcontainers처럼 범위가
  작고 공식 지원 경로 위주입니다. 보안·관측·외부 업체 SDK가 다수 결합된 서비스보다 새
  주요 버전을 채택할 때의 위험이 제한적입니다.
- Java 21을 공식 지원하고, Gradle 8.14 이상과 함께 현재 개발·CI 환경에서 재현 가능한
  조합입니다.
- 2026-09-24 기준 4.1은 오픈소스에서 적극 유지보수되는 버전 계열입니다. 상용 지원
  계약을 가정하지 않아도 보안 및 중대 오류 수정을 받을 수 있는 현재 계열을
  기준으로 삼습니다.
- 지금 3.5.16으로 시작한 뒤 특별한 기능상 이익 없이 가까운 시기에 4.x 이전을 별도
  수행하는 것보다, 작은 기능을 처음부터 끝까지 구현하며 호환성을 검증하고 기준선을 먼저 고정하는
  편이 이 프로젝트에는 비용이 낮습니다.
- 다만 "최신 안정 버전"이라는 사실 자체는 채택 근거가 아닙니다. Testcontainers,
  Flyway, PostgreSQL 드라이버 또는 필요한 Spring 모듈의 호환성 문제가 사전 검증에서
  확인되면 구현 전에 3.5.16으로 낮추고 그 근거를 이 ADR에 기록합니다.
- 도입 기업이 Spring 상용 장기지원 계약을 사용하고 마지막 3.x 하위 버전을 표준으로
  운영한다면 3.5.16의 연장 지원과 조직 내 검증 자산이 4.1.1을 새 프로젝트에 쓰는
  이점보다 클 수 있습니다. 그 경우에는 이 ADR을 고치고 3.5.16을 선택합니다.

#### Gradle 8.14.5 선택 이유

- Spring Boot 4.1.1이 공식 지원하는 8.14 계열입니다. Gradle daemon은 개발자 컴퓨터의
  최신 JDK를 따라가지 않고 Java 21로 실행합니다.
- 8.14.1을 그대로 쓰지 않고 같은 버전 계열의 현재 수정판인 8.14.5를 선택합니다.
  같은 계열의 누적 오류와 보안 수정을 반영하기 위해서입니다.
- 2026-09-24 기준 Gradle의 공개 보안 정책은 현재 major와 이전 major의 최신 minor에
  보안 수정을 제공합니다. 8.14.5는 이전 major의 최신 minor이고 알려진 고위험 보안
  권고가 해결된 버전이므로 지원 범위 안에 있습니다.
- Gradle 9가 더 새롭다는 이유만으로 올리지는 않습니다. 현재 필요한 Spring Boot
  plugin과 build 기능을 8.14.5가 모두 제공하므로 major 변경의 이익이 없습니다.
- Wrapper가 정확한 배포본을 고정하므로 컴퓨터에 설치된 Gradle 상태에 의존하지
  않습니다.
- Kotlin DSL의 타입 안전성보다 초기 빌드 파일의 단순성과 공식·커뮤니티 예제의
  접근성을 우선해 Groovy DSL을 사용합니다. 애플리케이션 코드의 타입 안전성과는
  별개의 선택입니다.
- 이후 Gradle 업그레이드는 새 기능을 따라가기 위해 자동 수행하지 않습니다. Spring
  Boot 지원 범위, 보안 수정, JDK 호환성 또는 빌드 문제를 해결해야 할 때 검증 후
  수행합니다. 8.14.5가 공개 보안 지원 대상에서 빠지거나 Java 기준선을 올려야 하면
  Gradle 9의 당시 최신 지원 버전으로 전환합니다. Gradle 10처럼 다음 major가
  출시되면 8.x가 이전 major 지원 범위에 계속 포함되는지 즉시 재검토합니다.

#### PostgreSQL 18.6 선택 이유

- 새 DB 구조이므로 기존 DB의 주요 버전 업그레이드나 덤프 호환성 비용이 없습니다.
- 이 프로젝트가 의존하는 `timestamptz`, 지연 검사 외래 키, 제약 조건과 트리거를
  실제 운영 DB와 같은 제품에서 검증해야 하므로 H2 같은 대체 DB보다 PostgreSQL을
  직접 사용해야 합니다.
- 18 전용 기능이 필요해서 선택하는 것은 아닙니다. 새 DB라 주요 버전을 올리는 비용이
  아직 없고 18은 2030년 11월까지 공식 지원 예정이므로, 운영 시작 후 지원 기간을
  충분히 확보할 수 있다는 점이 이유입니다.
- PostgreSQL은 같은 주요 버전의 최신 수정판 사용을 권장합니다. 따라서 범위가 넓은
  `18` 태그나 최초 18.0이 아니라, 2026-09-24 현재 오류·보안 수정이 누적된 18.6을
  기준으로 검증합니다.
- 실제 배포 대상 관리형 DB가 18을 지원하지 않거나 드라이버·Testcontainers
  호환성 문제가 확인되면 지원 중인 17 계열로 낮출 수 있습니다. 이 경우에도 사용
  기능의 의미와 테스트 근거가 같아야 합니다.
- 구현 시 범위가 넓은 `postgres:18` 태그를 사용하지 않고 `18.6` 이미지의 해시까지
  고정합니다.

#### 버전 고정과 갱신 정책

- Spring Boot 플러그인, Gradle Wrapper와 PostgreSQL 테스트 이미지는 정확한 버전으로
  고정합니다. Spring 의존성 버전은 Boot의 의존성 관리를 따르고 일부만 따로
  바꾸지 않습니다.
- 수정 버전 업데이트도 자동으로 완료 처리하지 않습니다. 공개 환경 Python 테스트,
  Gradle 단위 테스트, PostgreSQL 통합 테스트와 기준 데이터 재적재 테스트를 통과한 뒤
  반영합니다.
- 하위 버전 또는 주요 버전 변경이 데이터 계약, 시각, 직렬화, SQL이나 DB 이전의 의미에
  영향을 줄 수 있으면 ADR 개정 대상으로 봅니다.
- 지원 종료, 알려진 보안 문제, 필요한 기능 또는 검증된 호환성 문제가 없으면
  "더 최신 버전이 나왔다"는 사실만으로 기준선을 변경하지 않습니다.
- 매 분기와 중대한 보안 공지가 있을 때 사용 버전 목록과 지원 종료일을 재검토합니다. 긴급 보안
  수정은 정기 주기를 기다리지 않습니다.

2026-09-24 기준 공식 문서에서 위 조합의 지원 범위를 확인했습니다. 선택 이유와 지원
여부는 구분합니다. 공식 문서의 안정 버전 표시는 후보를 정하는 자료일 뿐 최종 결정 근거가
아닙니다.

참고한 공식 문서:

- [Spring Boot 시스템 요구사항](https://docs.spring.io/spring-boot/system-requirements.html)
- [Spring Boot 지원 버전](https://github.com/spring-projects/spring-boot/wiki/Supported-Versions)
- [Spring 지원 정책](https://spring.io/support-policy/)
- [Spring Boot Gradle 플러그인](https://docs.spring.io/spring-boot/gradle-plugin/)
- [Spring Boot DB 초기화 안내](https://docs.spring.io/spring-boot/how-to/data-initialization.html)
- [PostgreSQL 버전 정책](https://www.postgresql.org/support/versioning/)
- [Gradle 8.14.5 릴리스 안내](https://docs.gradle.org/8.14.5/release-notes.html)
- [Gradle 공개 보안 지원 설명](https://blog.gradle.org/gradle-security-subscription)

### JDBC 선택

Day 4에서는 Spring Data JPA 대신 `JdbcClient`를 사용합니다.

- 데이터 계약의 ID를 DB 기본 키로 그대로 사용합니다.
- 삽입과 조회 SQL을 명시하고 일반 애플리케이션 저장소에는 수정과 삭제 기능을
  제공하지 않습니다.
- N+1 문제, 지연 로딩과 영속성 문맥의 변경 감지에 의존하지 않습니다.
- DB 구조는 Java 엔티티 자동 생성이 아니라 Flyway SQL을 기준으로 합니다.
- `spring.jpa.hibernate.ddl-auto`는 사용하지 않으며 JPA 의존성도 추가하지
  않습니다.

JPA는 이후 쓰기 중심 업무 객체가 늘어날 때 별도 ADR로 재검토할 수 있습니다.

### DB schema 원칙

- JSON 계약의 사건 ID와 내용 ID를 DB 기본 키로 보존합니다.
- 의미 있는 관계에는 외래 키를 둡니다.
- `dataset_class`, `synthetic`, 상태 enum과 ID 접두사에는 `CHECK` 제약 조건을 둡니다.
- 날짜와 시각은 PostgreSQL `timestamptz`로 저장하고 Java에서는 `Instant`로
  처리합니다.
- 요청에 UTC와의 시차가 있으면 `Instant`로 변환하고 DB 및 API의 표준 출력은 UTC `Z`를
  사용합니다.
- 금액은 원 단위 `bigint`를 사용합니다.
- 상품 사실(Fact) 값은 문자열, 정수와 상태 열을 분리하고 자료형에 맞는 열만 채워지도록 `CHECK`
  제약 조건을 둡니다.
- 변경 분류와 여러 근거 위치는 순서를 보존하는 하위 테이블에 저장합니다.
- 공개 데이터 계약 전체를 하나의 JSONB 열에만 저장하는 방식은 사용하지 않습니다.
- JSON 파일에서 직접 생성되는 최상위 record에는 `source_record_hash`를 필수로
  저장합니다. 이 값은 해당 record의 canonical JSON SHA-256입니다.
- 대상은 `public_product`, `public_snapshot`, `collection_attempt`,
  `public_observation`, `product_terms_version`, `version_evidence`,
  `observed_rate_quote`, `extraction_attempt`와 `change_detection_result`입니다.
- `product_term_fact`, `fact_evidence_locator`와
  `change_detection_classification`은 부모 JSON에 포함된 하위 행입니다. 별도 원본
  record hash를 만들지 않고 부모의 `source_record_hash`와 하나의 트랜잭션으로
  검증합니다.
- 같은 ID를 다시 적재할 때 `ON CONFLICT DO NOTHING`만 사용하지 않습니다. 기존
  `source_record_hash`와 입력 해시가 같은지 확인하고, 다르면 전체 import를
  `SOURCE_RECORD_CONFLICT`로 rollback합니다.
- 해시가 같더라도 importer는 부모에 속한 하위 행의 개수와 내용을 다시 비교합니다.
  이를 통해 정비 작업이나 DB 손상으로 빠진 하위 행을 멱등 성공으로 숨기지 않습니다.
- Canonical JSON은 UTF-8, object key 사전순 정렬, 공백 없는 separator, Unicode 비escape,
  array 순서 보존으로 정의합니다. 이 baseline에는 부동소수점 값을 허용하지 않습니다.
- Python과 Java는 `contracts/fixtures/canonical-json-hash-cases.json`의 동일한 입력과 기대
  SHA-256을 모두 통과해야 합니다.
- `source_record_hash`는 이 canonical JSON 규칙으로 계산합니다. 반면
  `maintenance_change_audit`의 전후 행 해시는 PostgreSQL `jsonb::text` 표현으로
  계산하며 함수 안에서 timezone을 UTC, DateStyle을 ISO YMD로 고정합니다. 두 해시는
  목적과 직렬화 규칙이 다르므로 서로 비교하거나 하나의 검증 함수로 처리하지 않습니다.

초기에 만들 테이블은 다음과 같습니다.

1. `public_product`
2. `public_snapshot`
3. `collection_attempt`
4. `public_observation`
5. `product_terms_version`
6. `product_term_fact`
7. `version_evidence`
8. `fact_evidence_locator`
9. `observed_rate_quote`
10. `extraction_attempt`
11. `change_detection_result`
12. `change_detection_classification`
13. `baseline_import_run`
14. `maintenance_change_audit`

CollectionAttempt와 Observation의 상호 참조처럼 한 트랜잭션에서 함께 완성되는
관계에는 PostgreSQL의 지연 검사 외래 키를 사용할 수 있습니다. 실패한 시도는 성공
기록을 가리키지 않습니다.

성공한 ExtractionAttempt는 ProductTermsVersion, VersionEvidence와 ObservedRateQuote를
가리켜야 하며 각 evidence와 quote를 만든 성공 attempt는 하나로 식별돼야 합니다.
과거 조회는 이 관계를 통해 evidence 생성 시각을 판단합니다.

ExtractionAttempt에는 `attempted_at_source`를 저장합니다. 실행 중 측정한 시각은
`MEASURED`, Day 3 migration에서 Observation 시각을 복사한 값은
`BACKFILLED_FROM_OBSERVATION`으로 구분합니다.

### Append-only 정책과 감사되는 정비 예외

- PublicProduct, Snapshot, Observation, ProductTermsVersion, VersionEvidence, ObservedRateQuote,
  CollectionAttempt, ExtractionAttempt, ChangeDetectionResult, BaselineImportRun과
  MaintenanceChangeAudit 및 이들의 하위 행은 새 기록만 추가할 수 있습니다.
- 애플리케이션 저장소에는 수정과 삭제 메서드를 제공하지 않습니다.
- DB 트리거로 대상 테이블의 수정, 삭제와 `TRUNCATE`를 거부합니다.
- 과거 판정을 바로잡을 때는 기존 행을 고치지 않고 `supersedes_result_id`를 가진 새 행으로
  표현합니다.
- 동일 ID와 동일한 정규 내용의 재적재는 중복 없이 성공으로 처리합니다.
- 동일 ID에 다른 내용을 넣으려 하면 충돌로 처리하고 실패합니다.
- 정상적인 데이터 정정은 기존 행 수정이 아니라 새 correction 또는 superseding record로
  표현합니다.

DB 자체가 손상됐거나 법적 삭제처럼 기존 행을 직접 변경해야 하는 예외는 다음
break-glass 절차로만 수행합니다.

- Runtime role에는 DDL 권한을 주지 않습니다. Migration role은 Flyway DDL을 위해 보호
  테이블을 소유하지만, 보호 trigger를 끄거나 삭제할 수 없습니다. SQL 문자열 검색을
  보안 경계로 사용하지 않고 모든 DDL 종료 시 PostgreSQL catalog에서 보호 대상 14개
  테이블의 row guard와 truncate guard 28개가 모두 존재하고 활성 상태인지 확인합니다.
  하나라도 없거나 비활성 상태면 해당 DDL transaction을 실패시켜 상태를 rollback합니다.
- 별도 maintenance role을 승인된 작업자만 일시적으로 사용할 수 있습니다. Runtime
  role과 migration role은 이 role을 상속하거나 대신 사용할 수 없습니다.
- Maintenance transaction은 작업 ticket ID, 사유와 작업자 ID를 transaction-local
  setting으로 제공해야 합니다. Trigger는 maintenance role membership와 세 값이 모두
  확인될 때만 update 또는 delete를 허용합니다.
- Trigger는 허용한 변경마다 테이블명, operation, primary key, 작업자, ticket ID, 사유,
  변경 시각, 변경 전·후 JSON과 해시를 `maintenance_change_audit`에 자동 기록합니다.
- `maintenance_change_audit`은 별도 owner가 관리하며 일반 maintenance role도 수정하거나
  삭제할 수 없습니다.
- `trust_agent_audit_owner`는 보호 함수, event trigger와 감사 테이블을 소유하는 NOLOGIN
  role입니다. 운영에서는 어떤 로그인 계정에도 이 role membership을 부여하지 않습니다.
  이 role 변경은 superuser와 별도 승인이 필요한 DB 관리 작업으로 취급합니다.
- DB superuser와 audit owner 권한을 통한 보호 장치 변경은 애플리케이션이 막을 수 없는
  경계입니다. 운영 DB에서는 `log_statement=ddl`에 준하는 DB DDL 감사 로그와 별도 승인
  절차로 독립적으로 통제합니다.
- 테스트는 maintenance bypass로 행을 지우지 않습니다. Testcontainer나 test schema를
  매 테스트 단위로 새로 만들어 격리합니다.

### Schema 생성과 migration

- Schema를 만들고 변경하는 도구는 Flyway 하나만 사용합니다.
- Hibernate DDL, `schema.sql`, `data.sql`과 애플리케이션 기동 시 임의 DDL을 함께
  사용하지 않습니다.
- Flyway migration 파일은 `classpath:db/migration`에 둡니다.
- 보호 테이블을 추가하는 후속 migration은 `CREATE TABLE`, append-only trigger,
  truncate trigger, `trust_agent_protected_tables()` 갱신, owner 변경 순서로 작성합니다.
  보호 테이블 목록을 먼저 갱신하면 `ddl_command_end` 검사가 아직 trigger가 없는 테이블을
  감지해 migration이 실패합니다.
- 이미 적용된 migration 파일은 주석만 추가하는 경우에도 수정하지 않습니다. Flyway
  checksum과 기존 DB의 검증 가능성을 보존하기 위해 새 migration과 ADR에 후속 규칙을
  기록합니다.
- 로컬과 테스트 환경에서는 애플리케이션이 Flyway migration을 실행할 수 있습니다.
- 운영 환경에서는 배포 단계의 migration 작업과 제한된 migration role이 Flyway를
  실행합니다. Runtime role에는 DDL, 트리거 변경과 테이블 소유 권한을 부여하지
  않습니다.
- 애플리케이션은 시작할 때 기대한 Flyway schema version과 실제
  `flyway_schema_history`를 비교하고 다르면 요청을 받지 않습니다.
- Flyway clean은 모든 실행 환경에서 비활성화합니다.
- Migration test는 실제 PostgreSQL Testcontainer에서 실행합니다.
- Day 3 migration으로 생성한 ExtractionAttempt 5건의 `attempted_at`은 실제 추출
  시각이 아니라 `observed_at`을 복사한 값입니다. 실제 재추출은 2026-09-23부터
  2026-09-24 사이의 migration 작업에서 수행됐으며, 이 5건은
  `attempted_at_source=BACKFILLED_FROM_OBSERVATION`으로 적재합니다.

### Baseline importer

- 커밋된 `datasets/public/kb`와 `datasets/derived/public-kb`에서 명시적으로 허용한
  JSON만 입력으로 사용합니다.
- 원문 HTML과 `.private-artifacts`는 입력으로 사용하지 않습니다.
- Baseline importer는 일반 API 서버 기동과 분리된 명시적 명령으로 실행합니다.
- 기본 기동에서는 기준 데이터를 넣지 않습니다.
- 실행하는 쪽에서 저장소 루트나 기준 데이터 루트를 명시해야 합니다.
- 입력 루트가 없거나 허용 경로 밖의 파일이 발견되면 아무것도 적재하지 않고 실패합니다.
- 한 번의 적재는 하나의 트랜잭션으로 처리하며 일부 항목만 남기지 않습니다.
- 참조 순서와 관계없이 JSON을 먼저 읽고 전체 구조와 관계를 검증한 다음 DB에
  기록합니다.
- 입력 파일의 상대 경로와 SHA-256을 정렬한 목록을 만들고 그 정규 해시를
  `baseline_fingerprint`로 사용합니다.
- `baseline_import_run`은 실행 사건 ID, 기준 데이터 지문, 입력 파일 목록, 시작·종료
  시각, 최종 상태와 건수를 보존하는 변경 불가 감사 기록입니다.
- 실행 ID는 기준 데이터 지문과 분리합니다. 호출자가 지정할 수 있고, 지정하지 않으면
  실행할 때마다 생성합니다. 따라서 같은 기준 데이터의 실패 후 재시도도 별도 사건으로
  기록할 수 있습니다.
- 같은 기준 데이터를 다시 실행해도 업무 데이터가 중복 생성되지 않습니다.
- 성공 실행 기록은 업무 데이터와 같은 트랜잭션에 기록합니다. 검증 또는 적재가
  실패하면 업무 트랜잭션을 rollback한 뒤 별도 감사 트랜잭션으로 실패 기록만 남깁니다.
- 프로세스 강제 종료처럼 실패 감사 기록조차 남기지 못한 공백은 이 importer만으로 완전히
  설명할 수 없습니다. 이런 경우는 운영 대조 절차에서 확인합니다.
- 같은 ID의 DB 행과 입력 내용이 다르면 덮어쓰지 않고 충돌로 실패합니다.

Baseline importer는 운영 데이터 동기화 도구가 아니라 빈 DB를 시작하기 위한 bootstrap
도구입니다.

- 업무 테이블이 비어 있으면 승인된 baseline을 처음 적재합니다.
- 같은 `baseline_fingerprint`를 다시 실행하는 멱등 경로는 runtime ingestion이 시작되기
  전, DB가 baseline 행만 가진 동안에만 유효합니다. 이때 전체 record hash와 하위 행을
  검증한 뒤 성공으로 처리합니다.
- 같은 fingerprint이더라도 입력에 없는 행이 하나라도 있으면 건수 검증을 느슨하게 하지
  않고 `RUNTIME_DATA_PRESENT`로 거부합니다. 이는 데이터 손상과 운영 데이터 존재를 서로
  다른 진단으로 알려 주며, bootstrap importer가 운영 동기화 도구로 사용되는 일을
  막습니다.
- 업무 테이블이 비어 있지 않은데 다른 `baseline_fingerprint`를 넣으려 하면
  `BASELINE_MISMATCH`로 실패하고 어떤 기존 행도 변경하지 않습니다.
- Fingerprint 경계를 개별 record hash보다 먼저 확인합니다. 다른 baseline이면
  `BASELINE_MISMATCH`를 우선 반환하고, 같은 fingerprint의 DB 행이 손상됐을 때만
  `SOURCE_RECORD_CONFLICT`를 반환합니다.
- Contract 변경이나 과거 baseline 정정으로 fingerprint가 바뀌면 새 DB 또는 새 schema를
  만들고 전체 baseline을 다시 적재합니다. 검증 후 연결 대상을 전환하고, 이전 DB는
  보존 정책에 따라 read-only 감사 자료로 보관합니다.
- 이 새 DB 재구축 절차는 runtime ingestion이 시작되기 전까지만 유효합니다. 운영
  Observation과 attempt가 쌓인 뒤에는 baseline만 새 DB에 넣어 전환하지 않으며,
  운영 데이터 보존과 이전 절차를 별도 ADR로 승인해야 합니다.
- 운영 시작 후 새 Observation과 attempt를 넣는 경로는 baseline importer가 아니라 별도
  ingestion use case가 담당합니다.
- 기존 DB 안에 없어진 파일의 행을 그대로 남기거나 importer가 임의로 삭제하는 방식은
  사용하지 않습니다.

Python JSON Schema 검증은 CI의 데이터 계약 검사로 계속 유지합니다. Java importer는
타입이 정해진 DTO, 필수 필드, enum, ID 형식, 해시, 참조와 DB 제약 조건을 독립적으로
검증합니다. 두 구현이 같은 계약 파일을 서로 다른 의미로 해석하지 않는지는 기준 데이터
재적재 테스트로 확인합니다.

### 공개 상품 관측 상태 조회 API

초기 endpoint는 다음과 같습니다.

```text
GET /api/v1/public-products/{productKey}/observed-state?asOf={RFC3339 instant}
```

- 요청을 시작할 때 주입된 `Clock`에서 현재 `Instant`를 한 번 읽어 `evaluatedAt`으로
  고정합니다. 한 요청 안에서 다시 현재 시각을 읽지 않습니다.
- `asOf`는 그 시각까지 시스템에 기록돼 있던 상태를 재현하기 위한 event visibility
  cutoff일 뿐입니다. Freshness는 `asOf`가 아니라 항상 `evaluatedAt`을 기준으로
  계산합니다.
- `asOf`가 없으면 `evaluatedAt`과 같은 값을 사용합니다.
- `asOf > evaluatedAt`인 미래 조회는 `400 FUTURE_AS_OF_NOT_ALLOWED`로 거부합니다.
- `asOf < evaluatedAt`인 과거 조회는 감사와 설명 목적으로 허용하지만
  `publicEvidenceConfirmationAllowed`를 항상 `false`로 반환하고
  `confirmationBlockingReasons`에 `HISTORICAL_AS_OF`를 포함합니다.
- 조회 대상은 `observed_at <= asOf`인 Observation과 `attempted_at <= asOf`인 처리
  시도입니다.
- ProductTermsVersion, VersionEvidence와 ObservedRateQuote는 이를 만든 성공
  ExtractionAttempt의 `attempted_at <= asOf`일 때만 조회할 수 있습니다. Observation의
  `observed_at`만으로 evidence를 과거 시점에 미리 노출하지 않습니다.
- 성공 ExtractionAttempt의 `attempted_at`은 해당 Observation의 `observed_at`보다
  빠를 수 없습니다. Importer와 ingestion use case가 이를 검증합니다.
- `last_confirmed_at`은 위 가시성 조건을 만족하는 VersionEvidence가 연결된 가장 최근
  Observation의 `observed_at`입니다. Evidence가 시스템에 나타난 시각은 extraction
  `attempted_at`이고, 확인 대상으로 보고하는 시각은 observation `observed_at`으로
  서로 다릅니다.
- `latest_observation_at`은 조회 범위 안의 가장 최근 Observation 시각입니다.
- 추출 상태는 상품 전체의 마지막 시도가 아니라, 최신 Observation에 속한 마지막
  `attempted_at <= asOf` ExtractionAttempt 상태를 사용합니다.
- 최신 Observation에 추출 시도가 없으면 상태는 `null`이고
  `PENDING_EXTRACTION`입니다.
- 더 최신 Observation이 실패 또는 대기 상태여도 마지막으로 확인한 상품 조건과 근거를
  경고와 함께 반환할 수 있습니다.
- 상품 조건을 한 번도 성공적으로 확인하지 못했다면, 등록된 상품에 대해
  `UNAVAILABLE` 상태를 반환합니다.
- 상품 목록에 없는 상품 키는 `404`를 반환합니다.
- 잘못된 `asOf`는 `400`을 반환합니다.
- DB 접근 실패는 `UNAVAILABLE`로 숨기지 않고 `5xx` 오류로 반환합니다.
- 원문에 없는 `effective_from`과 `effective_to`를 `observed_at`으로 채우지 않습니다.

응답에는 최소 다음 정보를 포함합니다.

- `productKey`, `asOf`, `evaluatedAt`, `historicalQuery`
- `lastConfirmedAt`, `latestObservationAt`
- `freshnessStatus`, `blockingReasons`, `warningReasons`
- `publicEvidenceConfirmationAllowed`, `confirmationBlockingReasons`
- 적용한 최신성 정책 버전인 `freshnessPolicyVersion`과 허용 기간인
  `maxConfirmationAge`
- 마지막으로 확인한 ProductTermsVersion과 `fact`
- 마지막으로 확인한 Observation과 Snapshot 해시
- 같은 Observation의 ObservedRateQuote
- VersionEvidence와 각 `fact`의 원문 위치

`publicEvidenceConfirmationAllowed`는 현재 조회이고 freshness가 `CONFIRMED`일 때만
`true`입니다. 과거 조회에서는 freshness가 `CONFIRMED`로 계산되더라도 반드시
`false`입니다. 이 값은 공개 근거가 충분히 최근인지 확인하는 한 가지 조건의 결과일
뿐, 전체 AI 확정이나 업무 승인을 허용한다는 뜻이 아닙니다. 화면 표시를 돕는 값이며
보안 통제 수단도 아닙니다.

### 최신 확인 상태와 확정 차단

- Freshness 계산의 현재 시각 입력은 `evaluatedAt`입니다. `asOf`를 전달하지 않습니다.
- Python과 Java가 같은 결과를 내도록
  `contracts/fixtures/public-product-confirmation-policy-cases.json`을 하나의 정답표로
  둡니다.
- 정답표는 `asOf`, `evaluatedAt`, `maxConfirmationAge`, `lastConfirmedAt`,
  `latestObservationAt`, 최신 observation의 extraction 상태, 최신 collection 상태와
  기대 freshness 및 confirmation 결과를 포함합니다.
- Python 테스트와 Java 테스트는 같은 정답표의 모든 case를 읽어 검증합니다. 정책을
  변경할 때는 정답표와 두 구현을 한 변경에서 함께 수정해야 합니다.
- 다음 우선순위를 사용합니다.
  `UNAVAILABLE > UNCONFIRMED_AFTER_FAILURE > PENDING_EXTRACTION > STALE > CONFIRMED`
- `maxConfirmationAge`와 `freshnessPolicyVersion`은 시작할 때 검증하는 설정값입니다.
- 운영 설정에서 두 값이 없으면 애플리케이션 기동을 거부합니다.
- 최신 수집 실패는 허용 기간을 넘기 전까지 `warningReasons`에만 포함합니다.
- 마지막 확인 후 허용 기간이 지나면 `STALE`과 차단 사유를 반환합니다.
- 향후 승인 유스케이스는 응답의 참·거짓 값만 믿지 않습니다. 공개 근거를 검사하는
  애플리케이션 정책과 다른 필수 조건을 직접 조합해야 합니다.
- Day 4에는 승인 대상 데이터가 아직 없으므로 형식뿐인 승인 API를 만들지 않습니다.
  대신 `PublicEvidenceConfirmationPolicy`와 차단 테스트를 구현해 이후 승인
  유스케이스가 반드시 사용하는 조건 중 하나로 둡니다.
- 수기 체크리스트 조회는 최신성 때문에 확정이 차단된 상태에서도 허용합니다.

### 오류 응답

- API 오류는 일관된 Problem Detail 형식을 사용합니다.
- 내부 경로, 원문 HTML, 인증 정보, 예외 호출 경로와 DB SQL을 응답에 포함하지 않습니다.
- 오류에는 바뀌지 않는 애플리케이션 오류 코드와 요청 추적 ID를 포함합니다.
- PUBLIC_KB, SYNTHETIC_INTERNAL, SYNTHETIC_WORK와 DERIVED 값을 하나의 무표시 응답으로
  합치지 않습니다.

### CI

- 기존 `Python contracts` CI 작업을 변경 없이 유지합니다.
- 별도 `Gradle tests` 작업을 추가합니다.
- Gradle 작업은 Java 21과 저장소에 커밋한 Gradle Wrapper를 사용합니다.
- 단위 테스트, PostgreSQL Testcontainers 통합 테스트와 애플리케이션 기동 테스트를
  실행합니다.
- Gradle 의존성 캐시를 사용하되 Wrapper 파일도 검증합니다.
- Wrapper 배포본 체크섬, Gradle 의존성 검증 정보와 의존성 잠금 파일을 저장소에서
  관리합니다.
- Dependency verification metadata는 허용한 HTTPS repository만 사용하는 격리된
  환경에서 생성합니다. CI가 새 checksum을 자동 승인하지 않으며, dependency 변경 PR은
  lockfile과 verification metadata diff를 함께 검토해야 합니다.
- 빌드 결과물에는 소스 커밋과 의존성 목록을 연결합니다. CI에서는 의존성 취약점과
  라이선스 검토 결과를 보존합니다.
- 두 작업은 서로 독립적으로 통과해야 하는 검사 후보입니다.
- 공개 CI에서는 비공개 원문 HTML이나 실제 KB 통신을 요구하지 않습니다.

### 기업 운영 전제와 운영 배포 조건

Day 4의 목표는 운영 환경을 고려해 처음부터 끝까지 동작하는 첫 기능을 만드는 것입니다.
Day 4를 마쳤다고 바로 외부 고객 요청을 받을 수 있다는 뜻은 아닙니다. 기능 구현 정도와
운영 배포 준비 정도를 나눠 기록합니다.

Day 4에서 반드시 구현하거나 자동 검증할 운영 기준은 다음과 같습니다.

- 설정과 비밀값은 코드나 저장소에 넣지 않고 외부에서 주입합니다. 운영에 꼭 필요한
  설정이 없으면 임의의 기본값으로 대신하지 않고 기동을 거부합니다.
- 로컬 기본값은 기본 profile에서만 사용합니다. `prod` profile은 DB URL, username,
  password와 기대 schema version을 명시적으로 주입하지 않으면 기동을 거부합니다.
- 기대 schema version은 classpath의 가장 최신 Flyway versioned migration과 기동 시
  비교합니다. 새 migration을 추가하고 설정 version을 올리지 않으면 테스트와 기동이
  실패합니다.
- Runtime role은 필요한 테이블의 제한된 조회와 삽입만 허용합니다. Migration role과
  테이블 owner 계정을 애플리케이션 실행에 사용하지 않습니다.
- 생존 상태(liveness)는 프로세스가 살아 있는지만 나타냅니다. 외부 KB 통신, DB 연결과
  비공개 원문 파일의 존재 여부는 liveness에 넣지 않습니다.
- Readiness에는 `databaseConnectivity`와 `schemaCompatibility`를 별도 health component로
  노출합니다. 전자는 일시적인 DB 연결 장애이고 후자는 재배포나 migration이 필요한
  비호환 상태입니다. 전체 readiness는 둘 중 하나라도 실패하면 요청을 받지 않지만,
  component와 alert code를 구분해 운영자가 기다릴 문제와 조치할 문제를 판단할 수 있게
  합니다.
- Management HTTP port는 업무 endpoint port와 분리하고 기본적으로 loopback address에
  bind합니다. 이 내부 management port에서만 health component와 안정적인 운영 코드를
  노출하며 외부 ingress에는 연결하지 않습니다. 배포 환경에서 다른 내부 address가
  필요하면 네트워크 정책과 함께 명시적으로 설정합니다.
- Runtime datasource의 초기 기준은 connection timeout 2초, validation timeout 1초,
  `statement_timeout` 5초, instance당 maximum pool size 5입니다. 값은 validated
  configuration으로 노출합니다. Production 값은
  `pool size × 최대 instance 수 + 운영 여유분 <= DB connection budget`을 만족해야
  하며 부하 테스트 근거 없이 키우지 않습니다.
- Baseline importer와 migration은 runtime datasource를 공유하지 않고 작업 성격에 맞는
  별도 timeout을 사용합니다. `trust_agent_importer`는 LOGIN할 수 없는 권한 묶음이며
  baseline 테이블의 `SELECT`와 `INSERT`만 가집니다. 운영 importer 로그인 계정은 이
  role만 상속하고 runtime, migration, maintenance 또는 audit owner role을 상속하지
  않습니다. `prod` profile에서 importer를 켜면 runtime 자격증명으로 fallback하지 않고
  importer URL, username과 password를 모두 명시해야 합니다. Readiness DB 확인은 1초 안에
  끝나는 전용 query를 사용합니다.
- Actuator API는 상태와 필요한 측정값만 최소한으로 공개합니다. 환경 변수, 전체 설정,
  메모리 덤프와 전체 Spring bean 정보는 기본적으로 공개하지 않습니다.
- 모든 API 요청에 추적 ID를 부여하고 감사 기록 ID와 연결할 수 있는 구조화 로그를
  남깁니다. 원문 HTML, 로컬 경로, 인증 정보, SQL 인자와 고객 데이터를 기록하지
  않습니다.
- 시스템 시계는 UTC 동기화를 전제로 합니다. 시간 순서는 문자열이나 서버의 현지 시각이 아니라
  PostgreSQL `timestamptz`와 Java `Instant`로 수행합니다. 운영 환경의 clock
  동기화는 기반 시설의 책임으로 명시합니다.
- 의존성 잠금과 검증, Gradle Wrapper 체크섬과 고정된 컨테이너 이미지 해시를 사용해
  같은 소스 커밋의 빌드 입력을 재현합니다.
- Baseline import, schema migration과 애플리케이션 기동을 서로 다른 단계로 실행하며
  각 단계의 실패가 다음 단계로 진행되지 않게 합니다.

다음 항목은 실제 운영 배포 전에 별도 설계와 검증 근거가 필요합니다. Day 4에서
구현하지 않았다면 서비스를 운영 준비 완료로 표시하지 않습니다.

- 조직의 인증·인가 체계, 서비스 신원, TLS 종료 지점과 네트워크 정책
- 비밀 관리 시스템 연동 및 인증 정보 교체
- 개인정보·내부정보 분류와 접근 감사 정책
- PostgreSQL 백업, 특정 시점 복구, 복구 훈련과 재해복구 목표
- 고가용성, 재시도와 과부하 정책
- 서비스 수준 지표와 목표, 경보, 대시보드, 비상 대응 및 데이터 대조 절차
- 예상 요청량과 데이터 규모에 대한 부하·장애·장시간 실행 검증
- 취약점 처리 기한, 소프트웨어 구성 명세서(SBOM) 전달 방식과 의존성 라이선스 승인
- 무중단 schema migration의 expand/contract 절차와 rollback 또는 forward-fix 절차

Day 4 API는 인증이 구현되기 전까지 로컬·테스트 용도이거나 통제된 내부 개발망으로만
제한합니다. 인터넷 공개 배포는 허용 범위가 아닙니다.

## 검토한 대안

### Spring Boot 3.5.16 사용

운영 사례와 외부 라이브러리 호환 범위가 더 넓고 보수적인 선택입니다. 기존 3.x 코드나
호환되지 않는 외부 스타터가 있다면 우선 선택해야 합니다. 현재는 새 프로젝트이고
공식 Spring 모듈 중심의 작은 의존성 범위라 4.1.1 호환성을 먼저 검증할 수 있습니다.
사전 검증에서 필요한 의존성이 지원되지 않으면 3.5.16을 대안으로 선택합니다. 막연히
더 안정적으로 보인다는 이유만으로 버전을 바꾸지는 않습니다.

### Java 24 사용

현재 로컬 JDK와 일치하고 일부 최신 언어·실행 환경 기능을 사용할 수 있습니다. 그러나
이 서비스는 해당 기능이 필요하지 않고 Java 24는 장기 지원 버전이 아닙니다. 로컬 설치 편의보다
CI, 배포와 시연 환경의 장기 재현성을 우선해 Java 21을 선택합니다.

### PostgreSQL 17 계열 사용

18보다 운영 사례가 많고 관리형 DB 지원 범위가 넓을 수 있는 안전한 대안입니다. 다만
현재는 새 로컬·CI DB이고 18을 같은 이미지로 검증할 수 있으므로 더 긴 공식 지원
잔여 기간을 선택합니다. 실제 배포 환경 제약이나 호환성 근거가
생기면 17로 낮출 수 있습니다.

### Spring Data JPA 사용

보편적이고 CRUD 구현은 빠르지만, 사건 기록을 추가만 하는 구조와 명시적 SQL, 바뀌지
않는 ID가 핵심인 현재 모델에는 자동 수정과 지연 로딩이 불필요합니다. JdbcClient를
선택합니다.

### H2 단위·통합 테스트 사용

빠르지만 PostgreSQL의 `timestamptz`, 지연 검사 제약 조건, JSON 및 트리거 동작을
동일하게 검증하지 못합니다. Repository integration test는 PostgreSQL Testcontainers만
사용합니다. 순수 업무 규칙의 단위 테스트는 DB를 사용하지 않습니다.

### JSONB 단일 테이블 사용

기존 JSON을 쉽게 적재할 수 있지만 관계와 참조 무결성, 시간 조회와 인덱스가 애플리케이션
코드에 숨습니다. MVP에 필요한 필드는 관계형 열과 하위 테이블로 표현합니다.

### 정상 기동 시 기준 데이터 자동 적재

시연은 단순해지지만 서비스 기동과 데이터 이전이 결합되고 잘못된 경로에서
자동 적재될 수 있습니다. 명시적인 별도 명령 방식을 선택합니다.

### Day 4에 승인 API 추가

현재 승인 대상인 AI 결과나 상담 준비안 묶음이 정의되지 않아 형식적인 API만
생깁니다. 이번에는 공통 차단 정책을 구현하고 실제 승인 API는 승인 대상 계약과
함께 추가합니다.

## 위험과 제한사항

- Spring Boot 4.x와 외부 라이브러리의 호환성 확인 필요
- 14개 테이블, DB 트리거와 break-glass audit가 3개 상품 MVP에 비해 과도할 가능성
- JdbcClient 수동 변환 코드의 반복과 누락 위험
- Python JSON Schema와 Java DTO의 검증 규칙이 달라질 위험
- 저장소 경로 기반 importer는 패키징한 운영 환경에 적합하지 않음
- Testcontainers의 Docker 의존성과 CI 실행 시간 증가
- PostgreSQL 수정 버전과 이미지 해시를 갱신하는 운영 절차 필요
- 대량 데이터 기준 페이지 나누기와 인덱스 검증 미포함
- 인증, 인가와 실제 사용자 식별 정보 구현 미포함
- 실제 승인 API와 수기 체크리스트 API 미포함

## 결과

이 ADR이 승인되면 Day 4는 4a 기반, 4b baseline 적재, 4c 조회와 확정 차단의 세 단계로
진행합니다. 공개 상품 관측 상태를 DB 적재부터 API 응답까지 연결하되 각 단계의 test
evidence가 없으면 다음 단계로 넘어가지 않습니다. 합성 내부 공문의 시행일 조회, AI
호출 흐름, 실제 승인 대상, Spring Batch 실행기와 화면은 후속 단계로 남깁니다.

## 승인 전 검토 요청

1. Spring Boot 4.1.1, Java 21과 보안 지원 중인 Gradle 8.14.5 조합의 적절성
2. JPA 대신 JdbcClient를 선택한 이유의 적절성
3. PostgreSQL Testcontainers만 사용하는 테스트 전략의 비용 수용 여부
4. `maintenance_change_audit`를 포함한 14개 테이블이 MVP 범위에 비해 과도한지 여부
5. Role membership와 자동 감사를 요구하는 break-glass 변경 절차의 안전성
6. Baseline importer를 빈 DB bootstrap으로 제한하고 fingerprint 변경 시 새 DB로
   재구축하는 방식의 적절성
7. `asOf`와 `evaluatedAt` 분리, 미래 조회 거부와 과거 조회 confirmation 차단 계약
8. Evidence를 성공 ExtractionAttempt의 `attempted_at` 이후에만 노출하는 계약
9. Python과 Java가 하나의 confirmation policy 정답표를 공유하는 검증 방식
10. 등록된 상품에 성공 근거가 없을 때 `200 UNAVAILABLE`을 반환하는 계약
11. Migration/runtime/maintenance role 분리, readiness component와 timeout 기준의 실효성
12. Day 4a/4b/4c 범위와 실제 운영 배포 전 필수 조건의 누락 여부
