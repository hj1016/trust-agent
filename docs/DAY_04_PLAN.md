# 넷째 날 핵심 서비스 계획

## 상태

승인 — Day 4a부터 순서대로 구현

## 목표

승인된 공개 상품 처리 흐름의 데이터를 Spring Boot와 PostgreSQL에 적재하고, 요청한
기준 시각까지의 상품 조건, 광고 금리, 근거와 최신 확인 상태를 조회하는 첫 기능을
완성합니다.

공개 페이지의 `observed_at`을 내부 공문의 업무상 시행일로 해석하지 않고,
최신 Observation의 처리 상태를 확인하지 못했다면 AI 확정 가능 상태를 반환하지 않는 것이
핵심 완료 조건입니다.

`asOf`는 과거 상태를 어디까지 보여줄지 정하는 시각으로만 사용합니다. Freshness는
요청을 처리하는 현재 시각인 `evaluatedAt`으로 계산하며, 과거 조회 결과는 현재 업무의
AI 확정에 사용할 수 없습니다.

설계 초안은 `docs/adr/ADR-007-core-service-and-public-product-query.md`에 기록합니다.

## 범위

### 포함

- Spring Boot 및 Gradle Wrapper 골격
- PostgreSQL Flyway schema
- 공개 상품 baseline importer
- 공개 상품 관측 상태 조회 API
- 최신 확인 상태 계산과 `PublicEvidenceConfirmationPolicy`
- PostgreSQL 통합 테스트와 Gradle CI
- 운영 상태, schema version 검증과 공급망 고정
- API 계약, ERD와 Day 4 검증 기록
- Python과 Java가 함께 사용하는 confirmation policy 정답표

### 제외

- 실시간 KB 수집과 Python 처리 흐름의 Spring 재작성
- Spring Batch 실행기
- 합성 내부 공문과 시행일 기준 선택
- 합성 기업, 신청과 상담 준비안
- AI 서비스 및 LLM 호출
- 실제 사람 승인 대상과 승인 API
- 인증과 인가
- 외부 운영 배포
- 비밀 관리 시스템, TLS와 조직 서비스 신원 연동
- 백업·복구, 고가용성, 서비스 수준 목표와 비상 대응 체계
- 화면

Day 4는 하루 분량을 뜻하지 않습니다. 의존 관계와 검증 범위를 기준으로 Day 4a, 4b,
4c 세 단계로 나누며 각 단계는 독립된 test evidence를 갖습니다.

각 작업의 "테스트와 검증 근거"에서 별도로 문서 검토라고 표시하지 않은 항목은 자동화
테스트로 증명합니다. 눈으로 한 번 확인한 결과만으로는 완료 처리하지 않습니다.

## Day 4a — 애플리케이션과 DB 기반

Spring Boot 골격, CI, schema, 권한, immutable trigger, maintenance audit와 health를 먼저
완성합니다. 이 단계에서는 baseline을 적재하거나 조회 API를 만들지 않습니다.

## 작업 1 ADR-007과 API 시간 의미 확정

### 서비스에서 담당하는 역할

핵심 서비스의 기술 기준선, DB 책임, importer 경계와 공개 상품 조회의 시간 의미를
구현 전에 고정합니다.

### 필요한 이유

공개 Observation의 관측 시각과 내부 공문의 시행일을 같은 `asOf` 의미로 사용하면
공개 페이지에서 확인한 사실을 업무상 유효 조건으로 과장하게 됩니다. 저장 기술과
기존 기록을 바꾸지 않는 원칙도 구현 후에는 변경 비용이 큽니다. 기술 버전은 최신 여부가
아니라 이 프로젝트의 기능 필요성, 장기 지원, 호환성, 재현성과 변경 비용을 근거로
선택해야 합니다.

### 가능한 대안

- Spring Boot 3.5.16 또는 4.1.1
- Java 21 또는 현재 로컬 Java 24
- Gradle 8.14.5 또는 9.x
- JPA, Spring Data JDBC 또는 JdbcClient
- PostgreSQL 관계형 테이블 또는 JSONB 중심 저장
- 기동 시 자동 적재 또는 명시적 명령으로 적재

### 위험과 제한사항

- 최신 주요 버전 선택에 따른 외부 라이브러리 호환성 위험
- 관계형 정규화가 3개 상품 MVP에 비해 과도할 위험
- 승인 API가 없는 상태에서 차단 정책만 먼저 존재하는 범위 오해 가능성

### 테스트와 검증 근거

- Claude와 사람 검토 결과
- ADR 상태 `승인`
- 미결 결정이 없는지 점검
- 공식 Spring, Gradle과 PostgreSQL 문서 링크 확인
- 선택한 각 버전의 프로젝트상 이점, 지원 방식과 대안 전환 조건 기록
- Spring Boot 4.1.1, Java 21, Gradle 8.14.5와 PostgreSQL 18.6 조합의 최소 호환성
  사전 호환성 검증 통과
- 상용 지원 계약이나 사내 승인 기술 목록이 없다는 선택 전제 기록
- 최신 버전이라는 이유만으로 선택하거나 갱신하는 항목이 없는지 검토
- Gradle 8.14.5가 이전 major의 최신 minor로 공개 보안 지원 대상인지 공식 정책 확인

### 다음 작업과의 연결

승인된 버전, 의존성, 패키지와 DB 원칙을 기준으로 프로젝트 골격을 생성합니다.

## 작업 2 Spring Boot 골격과 Gradle CI

### 서비스에서 담당하는 역할

핵심 서비스를 어디서든 같은 방식으로 빌드하고 테스트하며 실행할 수 있는 최소 환경과
운영 상태 확인 기준을 제공합니다.

### 필요한 이유

로컬 IDE 설정이나 설치된 Java 24에 의존하면 다른 개발자와 GitHub Actions에서 같은
결과를 재현할 수 없습니다. 기존 Python CI와 Spring 검증도 분리해야 실패 원인을
구분할 수 있습니다.

### 가능한 대안

- Maven 또는 Gradle
- 컴퓨터에 설치된 Gradle 또는 저장소에 포함한 Gradle Wrapper
- 기존 Python 작업 안에서 순차 실행 또는 별도 Gradle 작업
- Java 도구 모음 자동 다운로드 또는 CI에 JDK 명시 설치

### 위험과 제한사항

- Wrapper 실행 파일과 의존성 추가에 따른 저장소 크기 증가
- 네트워크 의존성 다운로드 실패 가능성
- Spring Boot 4 플러그인과 Gradle 버전 호환성 문제
- CI 작업 추가에 따른 실행 시간 증가
- Actuator를 과도하게 공개해 내부 설정이 노출될 위험
- 의존성 잠금 파일 갱신 절차가 지켜지지 않으면 보안 수정이 지연될 위험

### 테스트와 검증 근거

- `./gradlew test` 통과
- `./gradlew bootJar` 통과
- 애플리케이션 기동 테스트 통과
- Java 21 도구 모음 사용 확인
- Gradle Wrapper 체크섬과 파일 검증 확인
- 의존성 lockfile, Wrapper distribution checksum과 wrapper JAR 검증을 자동 테스트
- Dependency verification metadata는 4a의 마지막 hardening 작업으로 분리하고, 생성 시
  처음 신뢰한 artifact 목록과 검토 절차를 검증 기록에 남김
- Liveness에 DB 상태가 포함되지 않는지 자동 테스트
- `databaseConnectivity`와 `schemaCompatibility` readiness component의 실패 사유가
  구분되는지 자동 테스트
- 별도 management port의 실제 readiness HTTP 응답에 두 component와 안정적인 오류
  코드가 포함되는지 자동 테스트
- `prod` profile 필수 DB·schema 설정 누락 시 기동 거부 검증
- 설정한 기대 schema version과 classpath 최신 Flyway migration version 일치 검증
- Runtime datasource의 connection timeout, validation timeout, `statement_timeout`과
  maximum pool size 설정 binding 및 경계값 테스트
- 느린 query가 `statement_timeout`으로 중단되고 readiness query가 정해진 timeout 안에
  끝나는지 PostgreSQL integration test
- 빌드 결과와 소스 커밋 및 의존성 목록 연결 확인
- 기존 Python 51개 테스트 회귀 통과
- GitHub Actions `Gradle tests` 작업 통과

### 다음 작업과의 연결

같은 빌드와 테스트 환경에서 Flyway schema와 repository integration test를 추가합니다.

## 작업 3 PostgreSQL schema, append-only 저장과 감사되는 정비

### 서비스에서 담당하는 역할

공개 데이터 계약의 ID, 관계, 관측 시각과 처리 감사 기록을 관계형 DB에 보존하고 이후
조회의 신뢰 경계를 제공합니다.

### 필요한 이유

파일만 직접 읽는 API는 참조 무결성, 트랜잭션, 기준 시각 조회와 운영 감사를 충분히
검증할 수 없습니다. 애플리케이션 코드에서만 기록을 추가하도록 약속하면 우발적인
수정과 삭제를 막기 어렵습니다.

### 가능한 대안

- JPA 엔티티와 Hibernate DDL
- JdbcClient와 Flyway SQL
- JSONB 단일 테이블
- 애플리케이션 저장소에서만 추가 전용으로 제한하거나 DB 트리거까지 사용
- H2 또는 PostgreSQL Testcontainers 테스트

### 위험과 제한사항

- 많은 테이블과 제약 조건으로 초기 구현량 증가
- CollectionAttempt와 Observation의 상호 참조 처리 복잡성
- 여러 자료형을 갖는 상품 사실 값의 제약 조건으로 SQL이 복잡해질 가능성
- DB 트리거가 migration과 테스트 데이터 정리를 방해할 가능성
- Migration role과 runtime role 분리로 로컬·CI 설정이 복잡해질 가능성
- Break-glass maintenance 경로 자체가 새로운 권한 상승 경로가 될 위험

### 테스트와 검증 근거

- 빈 PostgreSQL에서 Flyway migration 성공
- Migration 재실행 시 변경 없음
- 모든 기본 키와 외래 키 생성 확인
- 데이터 분류와 ID 접두사의 `CHECK` 제약 조건 확인
- 성공 및 실패 시도 조건 확인
- 여러 근거 위치의 순서 보존 확인
- 최상위 import record의 `source_record_hash NOT NULL` 확인
- Python과 Java가 `canonical-json-hash-cases.json`의 모든 기대 SHA-256을 통과
- 수정, 삭제와 `TRUNCATE`가 DB에서 거부되는지 확인
- Runtime role의 DDL, 수정과 삭제 권한 거부 확인
- Migration role만 Flyway DDL을 실행할 수 있는지 확인
- 기대한 schema version과 실제 버전이 다르면 readiness가 실패하고 요청을 차단하는지 확인
- Runtime role이 maintenance 설정값만 흉내 내도 update와 delete가 거부되는지 확인
- Runtime role의 delete 거부와 Runtime·migration role의 maintenance membership 부재 확인
- 문자열을 조립한 동적 SQL로 trigger를 disable해도 DDL transaction이 rollback되고
  보호 trigger 28개가 모두 활성 상태로 남는지 확인
- 승인된 maintenance role, ticket ID, 사유와 작업자 ID가 모두 있을 때만 변경되는지 확인
- 허용된 정비 변경의 전후 내용과 해시가 `maintenance_change_audit`에 자동 기록되는지 확인
- 서로 다른 session timezone에서 같은 행을 정비해도 감사 JSON의 시각과 해시가 UTC
  기준으로 동일한지 확인
- 보호 대상마다 실제 primary key를 찾을 수 있고 찾지 못하면 변경이 실패하는지 확인
- Maintenance role도 `maintenance_change_audit`을 수정·삭제할 수 없는지 확인
- `timestamptz`와 `Instant`를 저장하고 다시 읽었을 때 동일한지 확인
- H2 없이 PostgreSQL Testcontainers에서 전체 저장 계층 테스트 통과

### 다음 작업과의 연결

검증된 schema에 커밋된 JSON baseline을 하나의 트랜잭션으로 적재합니다.

## Day 4b — 기준 데이터 적재

Baseline importer, record hash, 멱등성, 충돌 처리와 baseline 교체 절차를 구현합니다.
조회 API와 freshness policy는 아직 구현하지 않습니다.

## 작업 4 재현 가능한 baseline importer

### 서비스에서 담당하는 역할

Python 처리 흐름이 생성한 공개·정제 기록을 ID와 근거 관계를 잃지 않고 핵심 서비스의
DB로 전달합니다.

### 필요한 이유

수동 SQL 초기 데이터나 애플리케이션 기동 시 자동 적재는 누락과 중복을 확인하기
어렵습니다. Importer 자체가 잘못된 참조와 동일 ID의 내용 충돌을 막아야 Python과 Spring
사이의 신뢰 경계가 유지됩니다.

### 가능한 대안

- JSON을 애플리케이션 리소스로 복제
- Flyway 데이터 변경에 JSON 내용을 SQL로 변환
- 정상 서버 기동 시 자동 적재
- 별도 명령으로 실행하는 importer
- Python에서 DB에 직접 적재

### 위험과 제한사항

- 저장소 기준 상대 경로는 패키징한 운영 환경에 적합하지 않음
- Python JSON Schema와 Java DTO의 검증 규칙이 달라질 가능성
- 파일 전체 검증과 하나의 DB 트랜잭션으로 인한 메모리 사용
- 기준 데이터 지문과 실행 사건 ID를 혼동할 위험
- 다른 baseline을 기존 DB에 섞어 넣으면 파일과 DB가 불일치할 위험

### 테스트와 검증 근거

- 테이블별 baseline 건수 확인:
  `public_product=3`, `public_snapshot=5`, `collection_attempt=5`,
  `public_observation=5`, `product_terms_version=3`, `product_term_fact=16`,
  `version_evidence=5`, `fact_evidence_locator=30`, `observed_rate_quote=5`,
  `extraction_attempt=5`, `change_detection_result=5`,
  `change_detection_classification=5`, 성공한 `baseline_import_run=1`
- 모든 JSON ID와 DB 기본 키 일치
- Observation, 처리 시도, 상품 조건, 금리와 근거의 외래 키 연결 확인
- 입력 경로, SHA-256과 재현 가능한 기준 데이터 지문을 감사 기록에 남겼는지 확인
- 같은 기준 데이터를 두 번 적재해도 업무 데이터 행 수가 변하지 않는지 확인
- 기존 ID의 `source_record_hash`가 같을 때만 멱등 성공하는지 확인
- 같은 ID와 다른 canonical JSON을 넣으면 `SOURCE_RECORD_CONFLICT`로 전체 rollback하는지
  확인
- 같은 parent hash인데 하위 행이 빠졌거나 달라진 DB 상태를 성공으로 숨기지 않는지 확인
- 같은 기준 데이터 재시도의 실행 ID는 다르지만 지문은 같은지 확인
- 실패 시 업무 데이터를 모두 rollback하고 별도 실패 감사 기록만 남기는지 확인
- 파일 누락, 잘못된 enum과 끊어진 참조 입력 시 전체 rollback
- 원문 HTML과 허용되지 않은 경로 입력 거부
- 입력 순서가 달라도 기준 데이터 지문이 동일한지 확인
- 비어 있지 않은 DB에 다른 `baseline_fingerprint`를 넣으면 `BASELINE_MISMATCH`로
  거부하는지 확인
- Migration baseline ExtractionAttempt 5건의 `attempted_at_source`가 모두
  `BACKFILLED_FROM_OBSERVATION`으로 적재되는지 확인
- 바뀐 baseline은 새 DB에 전체 적재하고 검증한 뒤 전환하며 이전 DB는 read-only로
  보존하는 절차 기록
- Runtime ingestion이 시작된 DB에는 baseline-only 새 DB 재구축 절차를 적용하지 않는다는
  운영 경계 확인

### 다음 작업과의 연결

DB에 적재된 기준 데이터를 대상으로 관측 상태 조회와 최신성 판단을 구현합니다.

## Day 4c — 조회와 확정 차단

관측 상태 조회, event visibility, freshness, confirmation policy와 공유 정답표를
구현합니다. Day 4a와 4b의 검증이 모두 끝난 뒤 시작합니다.

## 작업 5 공개 상품 관측 상태 조회 API

### 서비스에서 담당하는 역할

특정 기준 시각까지 확인 가능한 마지막 상품 조건, 광고 금리, 근거와 최신 처리 상태를
하나의 감사 가능한 응답으로 제공합니다.

### 필요한 이유

후속 상담 준비안은 HTML이나 개별 테이블을 직접 해석해서는 안 됩니다. `20억원` 같은
값과 원문 위치, 최신 확인 상태를 같은 조회 결과에서 확인할 수 있어야 합니다.

### 가능한 대안

- 최신 상태만 조회하고 `asOf` 미지원
- Snapshot 단위 조회
- 상품 조건과 광고 금리 endpoint 분리
- 하나의 API에서 관측 상태를 묶어 반환
- 성공 결과만 반환하고 대기 또는 실패 상태 숨김

### 위험과 제한사항

- `asOf`를 업무상 시행일로 오해할 가능성
- `asOf`를 freshness 평가 시각으로도 사용해 과거 정보가 현재 확정 가능해지는 위험
- Observation의 `observed_at`만 보고 아직 생성되지 않은 evidence를 과거에 노출할 위험
- 마지막 성공 상품 조건과 최신 실패 Observation을 함께 반환하는 응답의 복잡성
- VersionEvidence와 근거 위치를 결합하면서 조회가 복잡해질 가능성
- 페이지 나누기 없이 모든 근거를 반환할 때 향후 응답이 커질 가능성

### 테스트와 검증 근거

- 등록된 상품의 현재 관측 상태 `200`
- 등록되지 않은 상품 키 `404`
- 잘못된 `asOf` `400`
- 미래 `asOf`는 `400 FUTURE_AS_OF_NOT_ALLOWED`
- 응답의 `evaluatedAt`은 주입된 `Clock`에서 요청당 한 번만 읽은 현재 시각
- `asOf`가 없으면 `asOf == evaluatedAt`
- 과거 `asOf`이면 `historicalQuery=true`,
  `publicEvidenceConfirmationAllowed=false`,
  `confirmationBlockingReasons`에 `HISTORICAL_AS_OF` 포함
- UTC와의 시차가 포함된 입력을 UTC `Instant`로 변환
- 기준 시각 이후 Observation과 처리 시도 제외
- Observation은 `asOf` 이전이지만 성공 ExtractionAttempt는 `asOf` 이후인 경우 terms,
  evidence와 quote를 반환하지 않음
- ExtractionAttempt `attempted_at`이 Observation `observed_at`보다 빠른 입력 거부
- `lastConfirmedAt`은 evidence를 만든 `attempted_at`이 아니라 Observation의
  `observed_at`으로 반환
- 동일 관측시각의 Observation ID 안정 정렬
- 셀러론 법인 한도 `2,000,000,000 KRW` 확인
- `200,000,000 KRW`로 축소되지 않는 회귀 검증
- 광고 금리 기준일의 `null` 보존
- 원문 위치와 근거 해시 반환
- `effective_from`, `effective_to` 임의 생성 없음
- DB 장애를 `UNAVAILABLE`로 숨기지 않고 `5xx` 반환

### 다음 작업과의 연결

조회한 사건 흐름을 최신성 정책과 AI 확정 가능 여부 판단에 연결합니다.

## 작업 6 최신 확인 상태와 확정 차단

### 서비스에서 담당하는 역할

마지막으로 확인한 상품 조건과 최신 관측·수집·추출 상태를 평가해 상담에서 사용할 수
있는 확인 수준과 AI 확정 차단 여부를 제공합니다.

### 필요한 이유

새 Observation이 아직 처리되지 않았거나 추출에 실패했는데도 이전 `SELLING` 상품
조건을 확정 가능한 정보로 제공하면, 추출 단계에서 막은 위험을 핵심 서비스가 다시
허용하게 됩니다.

### 가능한 대안

- 화면에서만 경고 표시
- API 응답에 참·거짓 값만 제공
- 애플리케이션 정책을 공통 의존성으로 구현
- 일정 시간이 지나야 모든 실패를 차단
- 최신 Observation 실패를 즉시 차단

### 위험과 제한사항

- 상품 전체의 최신 처리 시도와 최신 Observation의 처리 시도를 혼동할 위험
- `asOf`와 `evaluatedAt`을 혼동할 위험
- 설정 누락 시 안전하지 않은 기본값을 사용할 위험
- 실제 승인 API가 없는데도 차단이 완성됐다고 오해할 가능성

### 테스트와 검증 근거

- `UNAVAILABLE > UNCONFIRMED_AFTER_FAILURE > PENDING_EXTRACTION > STALE > CONFIRMED`
  우선순위 확인
- 최신 Observation에 추출 시도가 없으면 이전 성공과 무관하게 `PENDING_EXTRACTION`
- 최신 Observation 추출 실패 시 즉시 `UNCONFIRMED_AFTER_FAILURE`
- 수집 실패만 있고 허용 기간을 넘지 않았으면 경고 유지
- 허용 기간을 넘으면 `STALE`
- 성공 근거가 없으면 `UNAVAILABLE`
- 차단 상태의 `publicEvidenceConfirmationAllowed=false`
- `CONFIRMED`일 때만 공개 근거 사용 허용
- 과거 조회는 freshness가 `CONFIRMED`여도 공개 근거 확정 사용 불가
- 운영 설정에서 정책 버전이나 허용 기간이 누락되면 기동 실패
- 수기 체크리스트 조회가 최신성 차단과 독립적임을 단위 테스트로 확인
- `contracts/fixtures/public-product-confirmation-policy-cases.json`의 모든 case를 Python과
  Java 테스트가 함께 통과
- 경계값 직전·정확히 일치·직후, pending, extraction failure, collection warning,
  unavailable, historical query 조합을 공유 정답표에 포함

### 다음 작업과의 연결

후속 실제 승인 유스케이스는 `PublicEvidenceConfirmationPolicy`를 다른 필수 조건과 함께
반드시 호출하도록 구성합니다.

## 작업 7 문서와 완료 검증 기록

### 서비스에서 담당하는 역할

실제로 구현한 DB 구조, API, 시간 의미, 테스트 범위와 남은 위험을 이후 개발과 면접
설명의 기준선으로 제공합니다.

### 필요한 이유

문서가 구현보다 앞서거나 뒤처지면 공개 관측 정보와 업무상 유효 정보의 경계가 다시
흐려질 수 있습니다. 테스트가 없는 항목을 완료로 표시하지 않는 원칙도 유지해야 합니다.

### 가능한 대안

- README에만 요약
- OpenAPI와 ERD만 생성
- Day 4 계획, ADR, 검증 기록과 README를 함께 갱신

### 위험과 제한사항

- 생성된 OpenAPI와 실제 컨트롤러가 달라질 가능성
- ERD가 Flyway DB 변경과 달라질 가능성
- 로컬 Testcontainers 결과만 기록하고 공개 CI 결과를 누락할 가능성

### 테스트와 검증 근거

- `docs/evidence/DAY_04_EVIDENCE.md` 작성
- 각 작업의 역할, 이유, 대안, 위험, 테스트와 다음 연결 기록
- 실제 API 요청과 응답 예시 기록
- Flyway 기준 ERD 작성
- 비공개 원문이 있는 Python 테스트, 공개 환경 Python 테스트와 Gradle 검증 결과 분리
- Day 4 완료와 운영 준비 완료의 차이 및 남은 배포 조건 기록
- README 현재 상태와 실행 명령 갱신
- 구현하지 않은 승인 API, Batch와 AI 기능을 완료로 표시하지 않는지 검토

### 다음 작업과의 연결

Day 5 합성 내부 공문과 시행일 기준 버전 선택의 입력 경계를 제공합니다.

## 구현 순서

1. ADR-007 승인
2. Day 4a: Spring Boot·CI 골격, Flyway schema, 권한, immutable trigger,
   maintenance audit, timeout과 health
3. Day 4a test evidence 작성 및 승인
4. Day 4b: Baseline importer, `source_record_hash`, 멱등성·충돌 처리와 새 DB 재구축 절차
5. Day 4b test evidence 작성 및 승인
6. Day 4c: 관측 상태 조회 API, `asOf`/`evaluatedAt`, event visibility, freshness와 공유
   confirmation policy 정답표
7. Day 4c test evidence, API 문서와 ERD 작성 및 승인

각 단계는 해당 테스트 근거가 확인된 뒤에만 완료 처리합니다. 앞 단계의 데이터 계약이
변경되면 다음 단계 구현 전에 ADR과 계획을 먼저 갱신합니다.

## 완료 정의

- ADR-007 승인
- Java 21 도구 모음과 커밋된 Gradle Wrapper로 빌드 재현
- PostgreSQL Flyway migration과 기존 기록 수정 방지 검증
- Maintenance change가 승인된 break-glass 경로에서만 가능하고 전후 내용이 감사
  테이블에 남는지 자동 테스트
- 커밋된 기준 데이터 전체의 재현 가능하고 중복 없는 적재
- 13개 baseline 관련 테이블의 정확한 건수, `baseline_import_run=1`과 초기
  `maintenance_change_audit=0` 검증
- 동일 ID·다른 내용 충돌과 다른 baseline fingerprint를 기존 DB에 넣는 시도를 rollback
- 공개 상품 3개의 관측 상태 조회
- `asOf`, `evaluatedAt`과 업무상 시행일의 의미 분리
- 미래 조회 거부와 과거 조회의 confirmation 차단
- Evidence가 성공 extraction의 `attempted_at` 전에는 조회되지 않는지 검증
- 상품 조건, 광고 금리, 근거와 최신 확인 상태를 하나의 응답으로 연결
- 셀러론 법인 한도 20억원 회귀 검증
- 최신 Observation이 대기 또는 실패 상태일 때 AI 확정 경로 차단
- Python과 Java가 공유 confirmation policy 정답표 전체 통과
- Migration role과 runtime role 분리 및 schema version readiness 검증
- 의존성 lockfile·verification metadata, Wrapper checksum과 고정된 PostgreSQL 이미지
  해시 자동 검증
- Actuator 비공개 endpoint, 비밀값 없는 로그와 timeout 설정 자동 검증
- 공개 Git 환경의 Python 테스트와 Gradle CI 통과
- Day 4 검증 기록, API 문서와 ERD의 구현 일치
- 인증·인가, 백업·복구, 고가용성과 서비스 수준 목표가 없으므로 운영 준비 완료가
  아님을 명시

## Claude 검토 요청

- ADR-007의 12개 승인 전 검토 요청 확인
- Day 4a, 4b와 4c의 경계와 선행 조건 확인
- 14개 테이블과 DB 트리거가 MVP에 필요한 최소 범위인지 확인
- Importer의 트랜잭션과 중복 방지 정의에 빈틈이 없는지 확인
- `asOf`와 `evaluatedAt` 분리 및 evidence visibility 조건 확인
- 실제 승인 API 제외가 기존 MVP 목표와 충돌하지 않는지 확인
- Spring Boot 4.1.1과 PostgreSQL 18.6 선택이 기업 운영 전제에서도 타당한지 확인
- 상용 지원 또는 사내 표준이 있을 때 대안 전환 조건이 충분한지 확인
- 운영 DB 권한 분리, 상태 확인, 공급망 고정과 운영 배포 조건의 누락 확인
- 테스트 근거가 각 완료 조건을 충분히 증명하는지 확인
