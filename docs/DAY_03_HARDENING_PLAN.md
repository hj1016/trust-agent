# 셋째 날 pipeline hardening 계획

## 목표

Day 4 Spring service를 시작하기 전에 공개 상품 pipeline의 관측 이력, 상품 조건
version, 광고 금리 quote, 추출 결과와 변경 판정을 분리합니다. 잘못된 시간 의미와
감사 공백이 DB schema와 API에 고정되지 않도록 합니다.

설계 기준은 승인된 `docs/adr/ADR-006-public-observation-and-product-terms.md`에
기록합니다.

## 작업 1 ADR과 contract 경계 확정

### 담당 역할

Snapshot, CollectionAttempt, Observation, ProductTermsVersion, VersionEvidence,
ObservedRateQuote, ExtractionAttempt와 ChangeDetectionResult의 소유권과 불변성을
고정합니다.

### 필요한 이유

현재 구조를 그대로 DB에 적재하면 매일 바뀌는 quote 기준일이 상품 version을 만들고,
같은 내용을 다시 확인한 시점과 추출 실패 재시도 이력이 사라집니다.

### 검토할 대안

- 기준일만 canonical hash에서 제외
- 모든 공개 fact를 하나의 version으로 유지하고 변경 유형만 사후 분류
- Observation과 quote를 terms version에서 분리

### 위험과 제한사항

Entity를 과도하게 일반화하면 3개 상품 MVP보다 큰 framework가 될 수 있습니다. 현재
필요한 record와 상태만 contract로 정의하고 parser profile 일반화는 후속 과제로
남깁니다.

### 테스트와 검증 evidence

- ADR 검토 승인 기록
- 각 entity의 생성 주체, ID, 분류와 참조 관계 점검표
- 기존 ADR과 충돌 여부 검토

### 다음 작업과의 연결

승인된 ADR을 기준으로 schema와 Python model을 변경합니다.

## 작업 2 시간 형식 검증 복구

### 담당 역할

Manifest, Observation과 처리 이력의 RFC 3339 UTC `Z` date-time을 실제로
검증합니다.

### 필요한 이유

현재 `date-time` format checker는 optional dependency가 없어
`not-a-timestamp`를 허용하며 문자열 정렬 결과도 신뢰할 수 없습니다.

### 검토할 대안

- `rfc3339-validator` 직접 dependency 추가
- 애플리케이션 코드의 `datetime.fromisoformat`만 사용
- `jsonschema` format extra 사용

### 위험과 제한사항

Schema와 코드가 서로 다른 date-time 범위를 허용하지 않도록 한 validator를 기준으로
contract test를 작성해야 합니다.

### 테스트와 검증 evidence

- 유효한 UTC date-time 허용
- Offset date-time 입력을 UTC `Z`로 정규화
- 잘못된 날짜와 timezone 없는 date-time 거부
- 시간 비교에서 문자열 정렬 미사용
- Public CI dependency 설치 재현

### 다음 작업과의 연결

유효한 관측 시각을 사용해 Observation과 attempt를 정렬하고 조회합니다.

## 작업 3 Snapshot과 Observation 분리

### 담당 역할

Raw content 중복 제거와 매 수집 사건의 append-only 기록을 동시에 보장합니다.

### 필요한 이유

현재는 동일 hash가 발견되면 기존 manifest를 반환하므로 재관측과 A에서 B를 거쳐
A로 복귀한 시점을 재생할 수 없습니다.

### 검토할 대안

- 같은 content도 manifest를 매번 생성
- 기존 manifest에 observation 배열 누적
- Snapshot registry와 append-only Observation 분리

### 위험과 제한사항

Timestamp나 snapshot hash에 의존하는 ID는 사건과 결과를 혼동합니다. 외부에서 주입
가능한 run ID, product key와 attempt sequence로 event ID와 replay 멱등성을 정의하고
artifact rollback이 다른 record의 참조를 깨지 않게 해야 합니다.

### 테스트와 검증 evidence

- 동일 content 2회 수집 시 Snapshot 1건, Observation 2건
- 성공 CollectionAttempt와 Observation의 일대일 연결
- 수집 실패의 FAILED CollectionAttempt 영속 기록
- A, B, A 수집 시 Snapshot 2건, Observation 3건
- 동일 run ID와 attempt sequence replay 시 HTTP 요청 및 Observation 중복 없음
- 새 attempt sequence는 content가 같아도 새 Observation 생성
- Attempt sequence가 product별로 증가하는지 검증
- Manifest 쓰기 실패와 공유 artifact 보존

### 다음 작업과의 연결

각 Observation을 extractor 입력과 evidence 기준으로 사용합니다.

## 작업 4 Terms, quote와 evidence 추출 분리

### 담당 역할

안정적인 상품 조건 identity와 시점별 광고 금리 quote를 각각 생성하고 모든 결과를
Observation에 연결합니다.

### 필요한 이유

광고 금리 문구와 기준일은 상품 조건 version과 다른 변경 주기와 검토 대상을
가집니다. 같은 terms를 여러 snapshot에서 확인한 evidence도 모두 남겨야 합니다.

### 검토할 대안

- Quote를 version 파일에 남기고 hash에서만 제외
- Quote와 terms를 별도 entity로 저장
- 모든 변경을 같은 product version으로 저장

### 위험과 제한사항

Quote를 hash에서만 제외하면 최초 quote가 version에 고정됩니다. Composite claim은
하나의 locator만으로 근거가 완전하지 않을 수 있습니다.

### 테스트와 검증 evidence

- Quote reference date만 달라져도 terms hash 동일
- Quote 문구가 달라져도 terms hash 동일
- 한도 변경 시 terms hash 변경
- 개인사업자 subject와 한도의 복수 locator 연결
- 동일 terms version의 여러 VersionEvidence 생성

### 다음 작업과의 연결

변경 판정과 Spring DB의 terms 및 quote 조회 모델을 제공합니다.

## 작업 5 판매 상태와 실패 격리

### 담당 역할

예상 가능한 판매종료를 terms 변경으로 저장하고, 한 상품의 실패가 다른 상품 처리를
막지 않게 하며 마지막 확인 이후의 실패가 상담에서 정상 terms로 오인되지 않게
합니다.

### 필요한 이유

현재 가장 중요한 상태 전이인 판매종료가 extraction failure가 되며 `--all`은 첫
실패에서 중단됩니다.

### 검토할 대안

- 판매중 이외 상태를 모두 unknown으로 실패
- 확인되지 않은 상태까지 미리 enum으로 정의
- 확인된 판매중과 판매종료만 매핑하고 나머지는 fail-closed

### 위험과 제한사항

알 수 없는 원문을 임의로 정상 상태에 매핑하거나 이전 `SELLING` terms를 확인 상태로
계속 노출하면 상담에 사용할 수 없는 상품이 노출될 수 있습니다. 전역 설정 오류까지
상품별 실패로 삼으면 잘못된 실행이 계속될 수 있습니다.

### 테스트와 검증 evidence

- 판매종료를 `DISCONTINUED`로 정규화
- 미지 상태는 FAILED ExtractionAttempt 생성
- 최신 Observation의 추출 전에는 `PENDING_EXTRACTION` 조회 상태
- 최신 extraction 실패 시 `UNCONFIRMED_AFTER_FAILURE` 조회 상태
- 마지막 성공 확인 후 정책 임계 초과 시 `STALE` 조회 상태
- 성공 evidence가 없으면 `UNAVAILABLE` 조회 상태
- `UNAVAILABLE`, `UNCONFIRMED_AFTER_FAILURE`, `PENDING_EXTRACTION`, `STALE`,
  `CONFIRMED` 우선순위와 복수 blocking reason 조합 검증
- stale 또는 unconfirmed 상태의 AI 확정 API 차단과 수기 checklist 허용
- 적용한 freshness threshold와 policy version 응답 및 audit 포함
- 상품 하나 실패 후 나머지 상품 성공
- 전역 schema 오류는 즉시 전체 중단
- 부분 실패 summary와 non-zero exit code

### 다음 작업과의 연결

후속 Batch가 처리 실패와 실제 상품 변경을 다른 알림으로 다룰 수 있습니다.

## 작업 6 기존 데이터 migration

### 담당 역할

기존 product version 5개를 신규 terms version, Observation, quote와 evidence 구조로
재생성합니다.

### 필요한 이유

Quote fact를 제거하면 모든 fact set hash와 version ID가 바뀌므로 기존 파일 일부를
그대로 유지할 수 없습니다.

### 검토할 대안

- 기존 파일을 active legacy 경로에 유지
- 기존 JSON만 변환
- 검증된 raw artifact에서 전체 재추출

### 위험과 제한사항

Private artifact가 유실되면 raw source 기반 migration을 재현할 수 없습니다. 구 ID를
참조하는 소비자가 있으면 mapping 없이 연결이 끊깁니다.

### 테스트와 검증 evidence

- Private artifact 5개의 manifest hash와 byte size 확인
- Snapshot manifest 5건을 같은 경로와 파일명에서 신규 schema로 재작성
- 옮겨진 시각과 취득 정보가 Observation 및 CollectionAttempt에 보존됐는지 확인
- 기존 파일 5개가 active dataset에서 제거됐는지 확인
- 신규 terms version 3개 생성
- Manifest 5건에서 Observation, quote와 evidence 5세트 생성
- Migration Observation 5건이 과거 실제 수집 횟수가 아닌 확인 가능한 하한선임을 기록
- 성공 CollectionAttempt 5건 생성, 근거 없는 과거 실패 attempt 미생성
- 고정 migration run ID로 ChangeDetectionResult baseline 생성
- 두 상품의 09-22 관측이 `QUOTE_REFRESHED`인지 확인
- 구 ID와 신규 ID mapping 기록
- 재실행 시 Observation과 attempt ID를 포함한 committed dataset canonical JSON 일치

### 다음 작업과의 연결

Spring service는 legacy version이 아니라 신규 contract만 적재합니다.

## 작업 7 CI와 문서 evidence 갱신

### 담당 역할

Public Git 조건과 private artifact 조건의 검증 범위를 명확히 분리해 기록합니다.

### 필요한 이유

Hardening 이전 Public CI의 36개 테스트에는 private artifact test 1개 skip이
포함됐지만 README의 private 36개 통과와 숫자만 보면 구분하기 어려웠습니다. 실제
snapshot 재추출도 자동 golden test로 고정되어 있지 않았습니다.

### 검토할 대안

- Public CI에서 live KB 페이지 수집
- Raw HTML을 Git에 포함
- Public fixture test와 private golden test 분리
- `datasets/derived/public-kb/`의 정제된 baseline audit record만 Git 허용

### 위험과 제한사항

Live page 기반 CI는 외부 장애와 페이지 변경으로 비결정적이며 raw HTML Git 저장은
ADR-004를 위반합니다.

### 테스트와 검증 evidence

- Public CI pass와 의도한 skip 수 기록
- Private artifact 필수 모드 전체 통과
- 실제 artifact 전체 golden 재추출 일치
- CollectionAttempt, ExtractionAttempt와 ChangeDetectionResult baseline이 clone과
  Public CI에서 검증되는지 확인
- `docs/evidence/DAY_03_HARDENING_EVIDENCE.md`에 명령, 환경, hash와 결과 기록
- 기존 Day 3 evidence에는 정정 링크만 추가

### 다음 작업과의 연결

검증된 신규 contract와 dataset을 Day 4 Spring Boot 저장 및 조회 구현의 입력으로
사용합니다.

## 완료 정의

구현 상태: 완료. 2026-09-24 기준 private artifact 필수 모드 49개 통과, Public Git
모드 49개 중 private 전용 3개 skip 및 나머지 통과를 확인했습니다. 실제 Spring Core
승인 API 차단은 이 단계에서 정책 contract와 projection으로 고정하고 Day 4 통합
테스트에서 구현합니다.

- ADR-006이 승인 상태이며 ADR-005와의 관계가 명시됩니다.
- Snapshot 중복 제거와 Observation append-only 기록이 함께 동작합니다.
- 수집 및 추출 실패가 append-only attempt record로 남습니다.
- ProductTermsVersion, quote, evidence와 처리 결과의 경계가 contract와 코드에서
  일치합니다.
- 조회 projection의 freshness 상태와 AI 확정 차단 정책 contract가 검증됩니다.
- 판매종료와 상품별 실패가 반복 가능한 테스트로 검증됩니다.
- 신규 dataset migration이 private raw artifact에서 재현됩니다.
- Public CI와 private validation evidence가 구분되어 기록됩니다.
- 전체 기존 contract 및 unit test 회귀 검증이 통과합니다.
