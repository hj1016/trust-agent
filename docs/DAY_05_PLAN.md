# 다섯째 날 계획

## 목표

합성 내부 공문 두 version을 불변 기록으로 적재하고, 업무 기준일에 적용되는 승인된
체크리스트를 선택합니다. 공문에서 만든 변경 후보는 공개 상품 근거와 자동 검증한 뒤
사람의 검토 기록 없이는 적용하지 않습니다.

Day 5의 업무 기준은 합성 내부 공문입니다. KB 공개 상품 페이지는 공문의 효력을
결정하지 않으며, 공개된 상품 설명과 내부 체크리스트가 서로 어긋나는지 확인하는 보조
근거로만 사용합니다.

설계 기준은 승인된
`docs/adr/ADR-008-internal-notice-effective-policy-and-review.md`에 기록합니다. ADR이
승인된 계약을 기준으로 DB migration과 API 구현을 진행합니다.

## Day 5에서 구현하는 범위

- `SYNTHETIC_INTERNAL` 합성 공문 v1과 v2
- 공문 수신 시각과 원문 hash를 보존하는 append-only 적재
- 공문에서 구조화한 규칙과 JSON Pointer 기반 근거
- 업무 기준일과 시스템 인지 시각을 분리한 적용 공문 선택
- 이전 체크리스트와 새 규칙의 변경 후보 생성
- 공개 상품 fact와의 교차 검증
- 자동 검증 결과와 사람 검토 결정의 분리
- 수정 시 기존 후보를 덮어쓰지 않는 proposal revision
- 승인된 체크리스트 version의 기준일 조회
- 20억원을 2억원으로 잘못 만든 후보의 승인 차단

다음 항목은 Day 5에 포함하지 않습니다.

- 실제 KB 내부 공문 시스템 연동
- 실제 임직원 계정, SSO, 조직 권한과 전자결재 연동
- LLM 호출과 prompt 운영
- 실제 고객 데이터와 대출 승인/거절 판단
- 최종 상담 준비안과 행원의 최종 업무 승인
- 운영용 공문 수신 connector와 대규모 문서 parser

## 반드시 구분할 시간

Day 5에서는 서로 다른 질문에 답하는 시간을 하나의 `asOf`로 합치지 않습니다.

| 이름 | 타입 | 답하는 질문 |
|---|---|---|
| `issuedOn` | `LocalDate` | 공문이 문서상 언제 발행됐는가 |
| `effectiveFrom`, `effectiveTo` | `LocalDate` | 어느 업무 기준일에 규칙이 적용되는가 |
| `receivedAt` | `Instant` | TrustAgent가 공문을 언제 알게 됐는가 |
| `receivedBusinessDate` | `LocalDate` | 명시된 업무 timezone에서 수신 사건이 속한 날짜는 언제인가 |
| `attemptedAt` | `Instant` | 구조화 처리를 언제 시도했는가 |
| `validatedAt` | `Instant` | 변경 후보를 언제 어떤 근거로 검증했는가 |
| `decidedAt` | `Instant` | 사람이 언제 검토 결정을 남겼는가 |
| `businessDate` | `LocalDate` | 사용자가 어느 영업일의 규칙을 찾는가 |
| `knownAt` | `Instant` | 그 시점까지 시스템이 알고 있던 사건은 무엇인가 |
| `evaluatedAt` | `Instant` | 현재 요청에서 정책을 평가한 시각은 언제인가 |
| `evaluatedBusinessDate` | `LocalDate` | 현재 평가 시각이 업무 timezone에서 어느 날짜인가 |

`businessDate`는 날짜이고 `knownAt`은 순간입니다. 서버 timezone으로 한 값을 다른 값으로
암묵 변환하지 않습니다. 수신 업무일만 설정으로 고정한 `Asia/Seoul`에서 계산해
`receivedBusinessDate`로 함께 저장합니다. 날짜 경계는 `[effectiveFrom, effectiveTo)`로
처리합니다.

## Day 5a — 공문 계약과 저장 기반

구현 및 검증 완료. 결과는 `docs/evidence/DAY_05A_EVIDENCE.md`에 기록합니다.

### 작업 1 합성 공문 계약 개정

#### 서비스에서 담당하는 역할

합성 공문의 문서 identity, version 관계, 시행 기간, 참조 상품과 합성 고지를 명확히
정의합니다.

#### 필요한 이유

현재 fixture의 `status: DRAFT`는 문서의 발행 상태와 TrustAgent 내부 검토 상태를 한
필드에 섞고 있습니다. 또한 snapshot hash만 참조해서 어떤 의미의 fact를 확인하려는지
알 수 없습니다. 이 상태로 구현하면 문서 승인과 AI가 만든 변경안 승인이 같은 상태
전이로 오해될 수 있습니다.

#### 구현 내용

- 기존 `synthetic-internal-notice.schema.json`을 문서 원천 계약에 맞게 개정
- 적재 가능한 원천 문서는 `document_status=ISSUED`로 제한하고 철회는 별도 lifecycle
  event로 기록
- `supersedes_notice_id`와 `effective_to` 추가
- Importer가 원천 JSON 밖에서 canonical `source_record_hash`를 계산해 DB에 저장
- 공개 참조에 `product_key`, `fact_key`, `subject_type`, 기대 값과 근거 요구 수준 추가
- v1 fixture migration과 v2 fixture 추가
- 모든 fixture에 합성 표시와 실제 내부자료가 아니라는 고지 유지
- 실제 부서명/직원명/문서번호처럼 오해될 값을 사용하지 않고 합성 식별자 사용

#### 가능한 대안

- 기존 `DRAFT`, `APPROVED`, `RETIRED` 상태 유지
- 문서 상태와 내부 검토 상태를 별도 event로 분리
- 공문 전체를 자유 형식 text로만 저장
- 처음부터 PDF parser와 OCR 도입

#### 위험과 제한사항

- 기존 Day 1 fixture와 contract test의 migration 필요
- 합성 공문의 구조가 실제 은행 문서 구조를 대표한다고 오해할 가능성
- schema가 지나치게 범용적인 규칙 DSL로 커질 가능성

#### 테스트와 검증 evidence

- v1과 v2 schema 통과
- 실제 내부자료로 오해할 수 있는 합성 표시 누락 거부
- 잘못된 version/supersession/날짜 범위 거부
- 공개 참조의 fact 의미와 기대 값 누락 거부
- 단일 v1 경로를 하드코딩한 `SyntheticFixtureContractTest`를 공문 전체 inventory와 관계
  불변식 검사로 변경
- Day 1 evidence의 합성 공문 1건 기준선은 보존하고 Day 5 migration 결과를 별도 evidence로
  기록
- 기존 데이터 분류와 경로 contract test 통과

#### 다음 작업과의 연결

검증된 공문을 별도 적재 경로로 PostgreSQL에 저장합니다.

### 작업 2 공문 적재와 append-only 저장

#### 서비스에서 담당하는 역할

공문 원천, 수신 사건, 구조화 시도와 규칙 근거를 수정할 수 없는 이력으로 저장합니다.

#### 필요한 이유

공문 파일이 Git에 존재한다는 사실만으로 시스템이 언제 그것을 알았는지 설명할 수
없습니다. 원천 문서와 처리 결과를 한 행에 덮어쓰면 실패/재시도와 과거 상태를 재현할
수 없습니다.

#### 구현 내용

- `internal_notice_version`
- `internal_notice_reference`
- `internal_notice_receipt`
- `internal_notice_lifecycle_event`
- `policy_extraction_attempt`
- `internal_policy_rule_version`
- `internal_policy_rule_evidence`
- `approved_checklist_version`
- `approved_checklist_schedule_revision`
- `approved_checklist_schedule_entry`
- source record별 canonical JSON hash 저장
- 원천과 처리 사건을 append-only table로 보호
- 공개 baseline importer와 분리된 synthetic internal bootstrap command 제공
- 별도 fingerprint, transaction과 importer role 사용

#### 가능한 대안

- 기존 공개 baseline importer에 합성 공문까지 포함
- 애플리케이션 기동 시 자동 적재
- 공문 JSON을 읽을 때마다 DB 없이 계산
- 하나의 JSONB table에 모든 문서와 처리 결과 저장

#### 위험과 제한사항

- Day 4의 보호 table 목록과 trigger migration 순서를 정확히 지켜야 함
- 공개 데이터와 내부 데이터의 importer 자격증명이 섞일 위험
- 운영 ingestion이 시작된 DB에 bootstrap을 다시 실행할 위험
- 인증이 없으므로 운영 공문 ingestion으로 사용할 수 없음

#### 테스트와 검증 evidence

- 빈 PostgreSQL에 v1/v2 전체 적재
- 같은 fingerprint 재적재의 멱등 성공
- 동일 ID/다른 내용 충돌과 다른 fingerprint 거부
- 하위 참조와 evidence 행 전수 재비교
- 중간 실패 시 업무 행 전체 rollback과 실패 audit 보존
- Runtime role의 수정/삭제/DDL 거부
- 공문 importer role의 최소 `SELECT`, `INSERT` 권한 확인
- `btree_gist` extension 사용 가능 여부를 migration 전제 검사로 확인
- schedule revision 안의 `[effectiveFrom, effectiveTo)` 중첩을 exclusion constraint가
  모든 쓰기 경로에서 거부하는지 확인
- 같은 schedule을 supersede하는 동시 승인 중 하나만 unique constraint로 성공하는지 확인

#### 다음 작업과의 연결

저장된 공문과 규칙을 두 시간축으로 조회합니다.

## Day 5b — 시행일 기준 선택

### 작업 3 적용 공문과 규칙 선택

#### 서비스에서 담당하는 역할

`businessDate`에 적용되고 `knownAt`까지 시스템이 실제로 알고 있던 공문과 규칙을
선택합니다.

#### 필요한 이유

시행일만 보면 시스템이 아직 받지 못한 공문을 과거에 알고 있었던 것처럼 만들 수
있습니다. 반대로 수신 시각만 보면 미래 시행 공문이 너무 일찍 업무에 적용됩니다.

#### 구현 내용

- `businessDate`와 `knownAt`을 받는 조회 use case
- `receivedAt <= knownAt`인 `ISSUED` 공문만 후보로 사용
- `effectiveFrom <= businessDate < effectiveTo` 적용
- 명시적인 `supersedes_notice_id` chain 검증
- 후보가 없으면 `NO_APPLICABLE_NOTICE`
- 서로 우선순위를 정할 수 있는 근거 없이 중첩되면 `AMBIGUOUS_EFFECTIVE_NOTICE`
- 과거 시행 공문을 늦게 받은 경우 `RETROACTIVE_NOTICE` warning
- 받은 시각 이전의 과거 조회에는 해당 공문 비노출
- 원천 공문 선택 상태와 승인 checklist 준비 상태를 별도 필드로 반환
- 새 공문이 pending 또는 failed인 경우 이전 checklist를 새 공문의 결과로 대신 반환하지
  않음
- 미래 `businessDate` 조회는 허용하되 `businessDateInFuture=true`,
  `FUTURE_BUSINESS_DATE`와 `internalChecklistUseAllowed=false` 반환
- 과거 `knownAt` 조회는 `HISTORICAL_KNOWN_AT`으로 현재 업무 사용 차단
- 중첩 공문은 HTTP 오류가 아니라 `200`, `noticeSelectionStatus=AMBIGUOUS`, blocking
  reason과 candidate notice ID 목록으로 반환
- notice와 checklist 대표 상태 우선순위를 계약으로 고정하고 모든 원인을 별도 목록에
  보존

#### 가능한 대안

- version 숫자가 가장 큰 공문을 항상 선택
- 가장 늦게 받은 공문을 선택
- `businessDate` 하나만 받는 조회
- 중첩 시 임의로 최신 공문 선택

#### 위험과 제한사항

- 영업일/휴일 계산은 이번 범위에 포함하지 않음
- `effectiveTo`가 없는 두 공문이 중첩될 수 있음
- 철회 공문의 효력을 과거까지 소급 제거하면 감사 재현이 깨질 수 있음
- 미래 `knownAt`을 허용하면 아직 모르는 사건을 노출할 수 있음
- 미래 업무일의 checklist를 현재 상담에 사용할 위험
- 여러 proposal revision과 처리 상태가 동시에 존재할 때 대표 상태가 흔들릴 위험

#### 테스트와 검증 evidence

- 시행일 직전/당일/종료일 경계
- v1 시행 중 v1, v2 시행 이후 v2 선택
- v2 수신 전에는 시행일이 지났어도 v1 또는 미확인 상태 반환
- 미래 시행 공문 제외
- 철회 사건 전후 재현
- 중첩 공문 fail-closed
- 적용 공문은 있지만 extraction, validation 또는 review가 끝나지 않은 상태 구분
- 미래 `businessDate` 결과 조회 허용과 현재 업무 사용 차단
- 중첩 공문의 candidate notice ID 반환
- `contracts/fixtures/internal-checklist-availability-policy-cases.json`의 상태 우선순위,
  validation age 경계와 시간축 조합 전체 통과
- 동일 날짜에서 `knownAt`만 달라지는 bitemporal 회귀 테스트
- 문자열 정렬이 아닌 `LocalDate`와 `Instant` 비교

#### 다음 작업과의 연결

선택된 새 공문 규칙과 직전 승인 체크리스트를 비교해 변경 후보를 만듭니다.

## Day 5c — 변경 후보, 검증과 사람 검토

### 작업 4 체크리스트 변경 후보와 revision

#### 서비스에서 담당하는 역할

공문 v1과 v2의 차이를 실제 적용 전 검토할 수 있는 변경 후보로 만듭니다.

#### 필요한 이유

공문이 발행됐다는 사실과 시스템이 만든 구조화 체크리스트가 정확하다는 사실은 같지
않습니다. 생성 결과를 바로 업무 규칙으로 사용하면 추출이나 생성 오류가 그대로
적용됩니다.

#### 구현 내용

- `checklist_change_proposal`과 `checklist_change_item`
- base checklist version과 target notice/rule ID 보존
- generator type과 version 보존
- 수정 시 기존 proposal을 변경하지 않고 새 revision 생성
- before/after canonical hash와 수정 사유 보존
- Day 5에서는 결정적인 fixture generator를 사용하고 LLM 호출은 제외

#### 가능한 대안

- 공문 rule을 곧바로 checklist로 사용
- 변경 후보를 mutable row로 관리
- Day 5부터 LLM을 호출

#### 위험과 제한사항

- 결정적인 generator가 실제 자연어 공문 해석 능력을 증명하지는 않음
- 변경 후보와 승인된 checklist를 혼동할 위험
- 여러 공문을 한 proposal에 합치는 경우 원천 추적이 복잡해질 가능성

#### 테스트와 검증 evidence

- v1→v2 diff의 추가/수정/삭제 구분
- 같은 입력과 generator version의 같은 canonical 결과
- proposal revision 간 before/after hash 연결
- 원천 notice/rule/evidence ID 누락 거부

#### 다음 작업과의 연결

변경 후보를 공개 상품 근거와 자동 검증합니다.

### 작업 5 자동 검증과 공개 근거 교차 확인

#### 서비스에서 담당하는 역할

변경 후보의 숫자, 주체와 근거 연결을 검증하고 사람이 검토할 수 있는 `PASS`, `WARN`,
`FAIL` 결과를 별도 record로 만듭니다.

#### 필요한 이유

법인 한도 20억원을 2억원으로 잘못 작성해도 문장 형식만 보면 자연스럽습니다. 승인 전에
구조화 값과 공개 근거를 비교해야 합니다.

#### 구현 내용

- `automated_validation_result`와 `automated_validation_issue`
- validator version, 실행 시각과 입력 proposal hash 저장
- 검증에 사용한 공개 Observation, ProductTermsVersion, VersionEvidence와 fact ID 저장
- validation 조회와 승인 판단에서 `validatedAt <= knownAt`인 결과만 사용
- 공개 근거가 필요한 항목은 Day 4 confirmation policy를 직접 호출
- fresh하고 비교 가능한 공개 fact와 값/subject가 다르면
  `FAIL PUBLIC_FACT_MISMATCH`
- 필수 공개 근거가 stale/pending/failed/unavailable이면
  `FAIL PUBLIC_EVIDENCE_UNCONFIRMED`
- 참고용 공개 근거의 미확인은 `WARN`으로 보존
- validation 결과는 proposal이나 evidence를 수정하지 않음
- Core service의 checklist use policy가 조회 시점의 공개 근거를 다시 검사하고 Day 6에서
  우회할 수 없는 `internalChecklistUseAllowed` 계산

#### 가능한 대안

- 문자열 포함 여부만 검사
- 공개 페이지 값이 다르면 내부 공문을 자동 수정
- 공개 근거 미확인을 항상 무시
- 검증 결과를 proposal status에 덮어쓰기

#### 위험과 제한사항

- 공개 정보는 업무 권위가 아니므로 내부 규칙을 자동 변경하면 안 됨
- 서로 비교할 수 없는 개념을 같은 `fact_key`로 연결할 위험
- 검증 후 공개 정보가 바뀌어도 과거 결과가 바뀌어서는 안 됨

#### 테스트와 검증 evidence

- 법인 `2,000,000,000 KRW` 일치 `PASS`
- 법인 `200,000,000 KRW` 오류 `FAIL PUBLIC_FACT_MISMATCH`
- 개인 한도와 법인 한도를 바꿔 연결한 subject mismatch 차단
- stale/pending/failed/unavailable 공개 근거에서 필수 교차 검증 차단
- validation 이후 공개 데이터가 바뀌어도 저장된 evidence ID로 과거 결과 재현
- validator version 변경 시 새 결과 추가, 기존 결과 불변
- 응답에서 validation 당시 공개 evidence ID와 현재 재평가 evidence ID 구분
- `validatedAt` 전후 `knownAt` 조회에서 validation 가시성 차단

#### 다음 작업과의 연결

자동 검증과 독립된 사람 검토 결정을 기록합니다.

### 작업 6 사람 검토와 승인된 체크리스트 version

#### 서비스에서 담당하는 역할

규정담당자의 `APPROVE`, `MODIFY`, `REJECT` 결정을 별도 사건으로 기록하고 승인된
변경만 새로운 체크리스트 version으로 만듭니다.

#### 필요한 이유

자동 검증 `PASS`는 사람이 검토할 수 있다는 뜻이지 자동 승인을 뜻하지 않습니다.
반대로 `FAIL`인 후보를 화면이나 API에서 우회 승인할 수 있어도 안 됩니다.

#### 구현 내용

- `human_review_decision`
- `approved_checklist_version`과 checklist item
- `approved_checklist_schedule_revision`과 schedule entry
- actor ID, 사유, 결정 시각, proposal before/after hash 저장
- `FAIL` validation이 하나라도 있으면 `APPROVE` 거부
- `maxValidationAge`를 넘긴 validation은 `VALIDATION_STALE`로 승인 거부와 재검증 요구
- validation approval policy version과 적용한 `maxValidationAge`를 decision에 저장
- 조회 응답에도 현재 policy version과 `maxValidationAge` 반환
- `MODIFY`는 기존 후보를 수정하지 않고 새 revision 생성
- `WARN` 승인은 사유 필수
- 승인된 checklist도 `businessDate`와 `knownAt`으로 조회
- `decidedAt <= knownAt`인 사람 결정만 과거 조회에 노출
- schedule revision 안의 기간 중첩은 PostgreSQL exclusion constraint로 거부
- 같은 schedule에서 시작한 동시 후속 승인은 unique constraint로 하나만 허용
- 인증이 없는 Day 5에서는 review command를 test/demo profile로 제한
- production profile에서는 신뢰할 수 있는 인증 주체가 없으면 review command 기동 거부

#### 가능한 대안

- 자동 검증 `PASS` 즉시 적용
- proposal row의 status만 변경
- 클라이언트가 보낸 actor 문자열을 운영 사용자 신원으로 신뢰
- 인증 구현까지 사람 검토 기능 전체 연기

#### 위험과 제한사항

- 실제 SSO와 권한이 없으므로 운영 승인 통제로 사용할 수 없음
- 동시에 두 proposal을 승인할 때 시행 기간이 중첩될 가능성
- 승인 직후 새 공개 관측이 들어오면 교차 검증이 오래될 가능성
- 관리형 PostgreSQL에서 `btree_gist` extension이 허용되지 않을 가능성

#### 테스트와 검증 evidence

- `FAIL` 후보의 API/service 양쪽 승인 거부
- `PASS` 후보 승인과 새 checklist version 생성
- `WARN` 사유 없는 승인 거부
- `maxValidationAge` 경계 직전과 정확히 일치할 때 허용, 초과 시 승인 거부
- `MODIFY` 후 새 revision 재검증 전 승인 거부
- 같은 decision request ID replay의 멱등성
- 같은 ID/다른 decision 내용 충돌
- 동시 승인에서 한 transaction만 성공하고 중첩 version 차단
- application 경로를 우회한 INSERT도 exclusion constraint로 중첩 schedule 거부
- `decidedAt` 전후 `knownAt` 조회에서 decision 가시성 차단
- 승인 record와 checklist version의 actor/hash/evidence 연결
- production profile에서 인증 없는 review command 비활성

#### 다음 작업과의 연결

Day 6 상담 준비안은 승인된 체크리스트 version만 사용하고, 공개 근거 freshness와 내부
규칙 적용 가능성을 요청 시점에 다시 검사합니다.

## 구현 순서

1. ADR-008 검토와 승인
2. Day 5a contract/fixture migration
3. Day 5a Flyway schema와 synthetic internal importer
4. Day 5a 전체 테스트와 evidence 검토
5. Day 5b bitemporal 적용 공문 조회
6. Day 5b 경계/중첩/소급 테스트와 evidence 검토
7. Day 5c proposal revision과 자동 검증
8. Day 5c 사람 검토 결정과 승인 checklist 조회
9. 전체 회귀 테스트, API/ERD/evidence 문서와 외부 검토

각 단계는 테스트 evidence가 확인된 뒤에만 완료로 표시합니다.

## 완료 정의

- 합성 공문 v1/v2가 명시적인 supersession과 시행 기간을 가짐
- 원천 공문과 내부 처리/검토 상태가 분리됨
- `businessDate`, `knownAt`, `evaluatedAt` 의미가 API와 코드에서 분리됨
- 아직 받지 않았거나 승인되지 않은 내용을 과거에 노출하지 않음
- 미래 `businessDate`의 조회는 허용하지만 현재 상담 사용은 Core service policy에서 차단
- 적용 공문 중첩을 임의 선택하지 않고 fail-closed 처리
- 중첩 candidate ID와 모든 blocking reason을 하나의 `200` 상태 응답에서 제공
- 승인 schedule의 기간 중첩을 PostgreSQL exclusion constraint로 차단
- v1→v2 checklist change proposal 생성
- 20억원을 2억원으로 만든 후보를 자동 검증에서 차단
- 자동 검증 결과와 사람 결정을 별도 append-only record로 보존
- `FAIL` 후보는 사람도 승인할 수 없음
- 오래된 validation은 `maxValidationAge` 정책으로 승인과 현재 사용 차단
- 수정된 후보는 새 revision과 재검증을 거쳐야 승인 가능
- 기준일 전후에 승인된 checklist version이 정확히 선택됨
- 모든 결과에서 notice, rule, public evidence, validation과 review ID 추적 가능
- 실제 내부자료가 아니라는 합성 표시가 schema, API와 문서에 유지됨
- 인증/운영 connector/LLM 호출이 미구현임을 완료 범위와 분리해 기록

## Claude 검토 요청

1. 문서 발행 상태와 TrustAgent 검토 상태를 분리한 모델이 적절한지
2. `businessDate`와 `knownAt`의 bitemporal 선택 규칙에 시간 역행 구멍이 없는지
3. `effectiveTo` exclusive 경계와 supersession 규칙이 충분한지
4. 늦게 받은 소급 시행 공문의 처리와 warning이 적절한지
5. 공개 상품 정보가 내부 공문을 덮어쓰지 않으면서 20억/2억 오류를 차단하는지
6. 공개 근거 필수/참고 구분과 freshness 차단 수준이 적절한지
7. proposal, validation, human decision과 approved checklist 분리가 과도하거나 부족한지
8. `MODIFY`를 새 revision으로 만드는 append-only 방식이 충분한지
9. 인증 없는 demo review command를 production에서 막는 경계가 충분한지
10. 별도 synthetic internal importer와 DB role이 필요한 최소 범위인지
11. Day 5a/5b/5c 분할과 각 완료 evidence가 실제 구현 가능한 크기인지
12. Day 6 상담 준비안이 의존할 계약에서 빠진 항목이 있는지
13. 미래 `businessDate` 조회와 현재 업무 사용 차단 계약이 충분한지
14. Append-only schedule revision과 `btree_gist` exclusion constraint 조합이 적절한지
15. 두 대표 상태 우선순위와 공통 정답표 case가 충분한지
16. `maxValidationAge`와 validation 및 decision의 `knownAt` visibility가 충분한지
