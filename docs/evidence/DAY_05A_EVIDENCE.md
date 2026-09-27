# 다섯째 날 A단계 검증 기록

## 구현 범위

Day 5a에서는 합성 내부 공문을 실제 내부자료와 구분해 적재할 계약과 저장 기반을
구현했습니다. 합성 공문 v1과 v2, 수신 사건, 구조화 시도와 rule evidence를 별도 record로
보존하고, 승인 checklist의 내용과 적용 schedule을 분리했습니다.

시행일 기준 공문 선택, 변경 proposal, 공개 근거 자동 검증과 사람 검토는 5b와 5c
범위이므로 완료로 처리하지 않습니다.

## 서비스에서 담당하는 역할

- `InternalNoticeVersion`: 발행된 합성 공문의 내용과 시행 기간 보존
- `InternalNoticeReceipt`: 시스템이 공문을 실제로 알게 된 시각과 업무일 보존
- `PolicyExtractionAttempt`: 구조화 시도 사건 보존
- `InternalPolicyRuleVersion`과 evidence: 구조화 rule과 JSON Pointer 근거 연결
- `ApprovedChecklistVersion`: 승인된 checklist 내용 identity 보존
- schedule revision과 entry: 기존 기간을 수정하지 않고 새 적용 일정 생성
- synthetic importer: 공개 상품 baseline과 다른 자격증명, fingerprint와 transaction으로
  합성 내부자료 bootstrap
- `InternalChecklistUsePolicy`: 후속 조회와 상담 준비 경로가 공유할 대표 상태와 사용 차단
  판정

## 필요한 이유

공문 발행일과 수신 시각, 시행일은 서로 다른 의미입니다. 이를 한 행이나 한 시각으로
합치면 아직 받지 않은 공문을 과거에 알고 있었던 것처럼 만들 수 있습니다. 또한 승인된
checklist의 open-ended 기간을 직접 수정하면 과거 승인 상태를 재현할 수 없습니다.

별도 importer와 DB constraint는 애플리케이션 한 경로의 규약만 믿지 않고 다른 쓰기
경로와 동시 transaction에서도 같은 경계를 지키기 위해 필요합니다.

## 주요 결정과 검증

### 계약과 합성 경계

- 공문 v1과 v2는 `document_status=ISSUED`이며 실제 내부자료가 아니라는 합성 고지를
  포함합니다.
- v2는 v1을 명시적으로 supersede하며 기간은 `[effective_from, effective_to)`입니다.
- 공개 교차 검증은 `product_key`, `snapshot_hash`, `fact_key`, `subject_type`, value와
  unit을 포함합니다.
- 20억원은 `2,000,000,000 KRW`, `CORPORATION`으로 구조화했습니다.
- 원천 JSON 안에는 자기 자신을 hash하는 `source_record_hash`를 넣지 않고 importer가
  canonical JSON에서 계산합니다.

### PostgreSQL V4

- `CREATE EXTENSION IF NOT EXISTS btree_gist`가 실패하면 migration도 실패합니다. 이어지는
  명시적 전제 검사와 exclusion constraint 생성 때문에 제약 없이 조용히 진행할 수
  없습니다.
- 같은 schedule revision의 인접 구간은 허용하고 하루라도 겹치면 SQLSTATE `23P01`로
  거부하는 통합 테스트를 통과했습니다.
- family별 root schedule partial unique index를 검증했습니다.
- 실제 병렬 transaction 두 개가 같은 schedule revision을 supersede하도록 실행해 unique
  constraint가 정확히 하나만 성공시키는 것을 검증했습니다.
- 신규 업무 table 11개를 기존 append-only trigger와 DDL 보호 목록에 포함했습니다.
- V4 상단에 후속 보호 table migration 순서인 table 생성, append-only trigger, truncate
  trigger, 보호 목록 갱신, owner 변경을 명시했습니다.
- `trust_agent_synthetic_importer`는 필요한 table의 `SELECT`, `INSERT`만 가지며
  `UPDATE`, `DELETE`, maintenance와 audit owner membership은 없습니다.

### 업무 timezone

- 업무 timezone은 `Asia/Seoul`, 변환 정책은 `internal-business-time-v1`로 명시합니다.
- `2026-09-10T23:30:00Z` 수신은 `2026-09-11` 업무일로 저장됩니다.
- JVM 기본 timezone을 UTC, America/New_York, Pacific/Honolulu로 바꿔도 결과가 같았습니다.
- PostgreSQL database timezone을 America/New_York으로 바꾼 통합 테스트에서도 importer가
  계산해 바인딩한 `received_business_date=2026-09-11`이 유지됐습니다.

### Importer 무결성

- 최초 적재와 같은 fingerprint 재적재가 중복 없이 성공합니다.
- 다른 fingerprint는 `BASELINE_MISMATCH`로 거부하고 업무 행을 바꾸지 않으며 실패 audit만
  별도 transaction에 남깁니다.
- 동일 ID의 다른 source hash는 `SOURCE_RECORD_CONFLICT`로 거부합니다.
- 부모 hash와 전체 건수가 같아도 reference, rule, JSON Pointer evidence의 의미 컬럼을
  전수 재비교합니다. 하위 evidence text만 바꾼 회귀 fixture도 거부했습니다.
- 입력보다 DB 행이 많으면 `RUNTIME_DATA_PRESENT`와 해당 table 이름을 반환하고, 적으면
  `BASELINE_CONTENT_MISMATCH`로 구분합니다.
- 공개 baseline importer와 합성 importer가 공통 `AppendOnlyBootstrapChecks`의 건수 판정과
  SQL 식별자 allowlist를 사용합니다. 동적 SQL의 table과 ID column은 내부 상수 목록을
  통과한 값만 사용합니다.
- importer 동시 실행은 transaction advisory lock으로 직렬화하지만, schedule 무결성은
  advisory lock이 아니라 DB constraint가 보증합니다.

### 상태와 사용 차단

`contracts/fixtures/internal-checklist-availability-policy-cases.json`을 Java policy test가
읽습니다. 대표 상태 동시 성립 조합과 다음 사용 조건을 각각 깨뜨린 case를 포함합니다.

- 현재 지식 시점
- 현재 또는 과거 업무일
- `noticeSelectionStatus=SELECTED`
- `checklistAvailabilityStatus=AVAILABLE`
- validation freshness 통과
- 공개 근거 confirmation 통과
- semantic mismatch 없음

모든 조건을 만족할 때만 `internalChecklistUseAllowed=true`입니다.

## 검토한 대안

- 공개 baseline importer 재사용: 권위와 자격증명 경계가 달라 별도 importer를 선택
- 승인 checklist 행의 종료일 수정: 과거 상태가 사라져 schedule revision을 선택
- advisory lock만으로 중첩 방지: 다른 쓰기 경로가 우회할 수 있어 exclusion/unique
  constraint를 선택
- JVM 또는 DB 기본 timezone 사용: 환경에 따라 업무일이 달라져 명시적 `ZoneId`를 선택
- 상태를 호출부마다 판단: Day 6에서 판정이 달라질 수 있어 공용 policy와 정답표를 선택

## 테스트 evidence

2026-09-27 로컬 실행 결과입니다.

```text
./gradlew test bootJar --no-daemon
BUILD SUCCESSFUL
71 tests completed
Spring Boot executable jar 생성 성공

python3 -m unittest discover -s tests
Ran 57 tests
OK

TRUSTAGENT_PRIVATE_ARTIFACT_ROOT=/tmp/trust-agent-public-git-no-artifacts \
  python3 -m unittest discover -s tests
Ran 57 tests
OK (skipped=3)
```

Public Git 조건의 skip 3개는 비공개 raw snapshot이 필요한 기존 테스트입니다. 나머지
54개는 통과했습니다.

## 위험과 제한사항

- 실제 은행 내부 공문, 문서 관리 시스템, SSO와 전자결재를 사용하지 않습니다.
- `btree_gist` 허용 여부는 관리형 PostgreSQL 환경에서도 별도로 확인해야 합니다.
- bootstrap은 runtime ingestion이 시작되기 전의 빈 synthetic internal 영역에서만
  재실행할 수 있습니다.
- 영업일 holiday calendar는 포함하지 않고 달력 날짜와 명시적 timezone만 사용합니다.
- `InternalChecklistUsePolicy`는 기반 계약이며 승인 checklist를 실제로 만드는 5c 전에는
  업무 사용 가능 상태가 생성되지 않습니다.
- 인증, 인가, 백업, 복구, 고가용성과 SLO가 없어 운영 준비 완료가 아닙니다.

## 다음 작업과의 연결

Day 5b는 저장된 receipt와 시행 기간을 사용해 `businessDate`와 `knownAt` 두 축으로 적용
공문을 선택합니다. Day 5c는 선택한 공문 rule을 변경 proposal로 만들고 공개 근거
validation 및 사람 검토를 거쳐 새 checklist와 schedule revision을 생성합니다.
