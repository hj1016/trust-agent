# ADR 008 합성 내부 공문과 시행일 기준 규칙 선택

## 상태

승인

## 배경

TrustAgent의 실제 업무 기준은 공개 상품 페이지가 아니라 승인된 내부 공문과 규정입니다.
프로젝트는 실제 은행 내부자료에 접근하지 않으므로 `SYNTHETIC_INTERNAL` 합성 공문으로
같은 처리 경계를 검증합니다.

Day 4까지 구현한 공개 상품 pipeline은 공개 페이지에서 무엇을 언제 관측했고 현재 그
근거가 충분히 최근인지 설명합니다. 공개 페이지의 `observed_at`은 내부 규칙의 시행일이
아닙니다. 공개 정보는 내부 공문을 자동 변경하는 권위가 아니라, 고객에게 공개된 설명과
구조화 결과가 어긋나는지 확인하는 교차 검증 근거입니다.

현재 합성 공문 계약은 문서 자체의 `DRAFT`, `APPROVED`, `RETIRED` 상태와 TrustAgent가
만든 체크리스트 변경안의 사람 검토 상태를 구분하지 않습니다. `issued_at`과
`effective_from`만 있어 시스템이 공문을 언제 받았는지도 재현할 수 없습니다. 이 모델을
그대로 사용하면 다음 문제가 생깁니다.

- 시행일이 과거라는 이유로 아직 수신하지 않은 공문이 과거 조회에 나타날 수 있습니다.
- 공문이 승인됐다는 사실만으로 자동 추출한 규칙까지 승인된 것으로 오해할 수 있습니다.
- v1과 v2가 중첩될 때 version 숫자만 보고 임의로 하나를 선택할 수 있습니다.
- 자동 검증 결과를 proposal status에 덮어쓰면 어떤 근거와 validator로 판단했는지
  재현하기 어렵습니다.
- 사람이 값을 수정하면 최초 오류와 수정 이유가 사라질 수 있습니다.
- 공개 페이지의 숫자가 다를 때 내부 공문을 자동으로 덮어쓰면 업무 권위가 뒤집힙니다.

## 결정

### ADR-007에서 이어받는 원칙

Day 5 이후의 내부 정책 기능도 ADR-007에서 확정한 다음 원칙을 그대로 사용합니다.

- 시간: 업무 적용일과 시스템이 사건을 알게 된 시각을 분리하고 미래 지식을 과거에
  노출하지 않습니다.
- 상태: 여러 조건이 동시에 성립할 수 있으면 대표 상태 우선순위와 모든 blocking reason을
  계약 및 정답표로 고정합니다.
- 강제: 중요한 무결성은 애플리케이션 호출 규약만 믿지 않고 PostgreSQL constraint와
  trigger로 보호합니다.
- 차단: 안전 판정을 화면이나 후속 소비자에게 미루지 않고 Core service policy에서
  계산합니다.

### 권위와 범위

- 업무상 규칙의 권위는 합성 내부 공문에 있습니다.
- 공개 상품 정보는 외부 공개 내용과의 교차 검증 및 변경 감지에만 사용합니다.
- 공개 정보가 다르다는 이유로 내부 규칙이나 공문을 자동 수정하지 않습니다.
- 충돌은 validation issue로 기록하고 담당자의 검토 대상으로 보냅니다.
- 이 프로젝트의 모든 공문, 규칙, 검토자와 결정은 합성 데이터입니다. 실제 KB 내부자료나
  실제 임직원의 행동으로 표현하지 않습니다.
- Day 5는 상담과 심사 준비용 체크리스트를 다루며 대출 승인/거절, 신용등급, 최종 한도와
  최종 금리를 결정하지 않습니다.

### 두 종류의 승인 분리

공문 원천의 발행 상태와 TrustAgent가 만든 구조화 결과의 사람 검토를 분리합니다.

- `document_status=ISSUED`: 합성 시나리오에서 권한 있는 원천 시스템이 발행한 문서
- `InternalNoticeLifecycleEvent=WITHDRAWN`: 원천 문서가 철회됐다는 새 사건
- `HumanReviewDecision`: TrustAgent가 만든 proposal에 대한 별도 검토 결정

문서의 `ISSUED`는 proposal 승인을 뜻하지 않습니다. 자동 validation의 `PASS`도 사람의
승인을 뜻하지 않습니다.

### 시간 모델

- `issued_on`, `effective_from`, `effective_to`와 `business_date`는 ISO 8601
  `LocalDate`입니다.
- `received_business_date`도 `LocalDate`이며 설정으로 고정한 업무 timezone
  `Asia/Seoul`에서 `received_at`을 변환해 수신 시 한 번 저장합니다. JVM과 DB session의
  기본 timezone은 사용하지 않습니다.
- 업무 timezone과 변환 정책 version은 production 필수 설정으로 두고 receipt audit에
  함께 보존합니다.
- `received_at`, `attempted_at`, `validated_at`, `decided_at`, `known_at`과
  `evaluated_at`은 UTC `Instant`입니다.
- `business_date`는 어느 영업일에 규칙이 적용되는지를 결정합니다.
- `known_at`은 그 순간까지 시스템에 기록된 사건만 보이게 하는 event visibility
  cutoff입니다.
- `evaluated_at`은 요청 시작 시 `Clock`에서 한 번 읽은 현재 시각입니다.
- `evaluated_business_date`는 같은 `evaluated_at`을 업무 timezone `Asia/Seoul`로 변환한
  날짜이며 요청 중 다시 계산하지 않습니다.
- `business_date`를 server timezone의 `known_at` 날짜로 암묵 변환하거나 그 반대로
  변환하지 않습니다.
- 적용 기간은 `[effective_from, effective_to)`입니다. `effective_to=null`은 열린
  종료 구간입니다.
- `known_at > evaluated_at`인 미래 조회는 거부합니다.
- 과거 `known_at` 조회는 감사와 재현 목적으로만 허용하고 새로운 검토 결정이나 승인을
  만들 수 없습니다. `historical_knowledge_query=true`, blocking reason
  `HISTORICAL_KNOWN_AT`과 `internal_checklist_use_allowed=false`를 반환합니다.
- `business_date > evaluated_business_date`인 미래 업무일 조회는 계획과 사전 검토를
  위해 허용하지만 `business_date_in_future=true`, blocking reason
  `FUTURE_BUSINESS_DATE`와 `internal_checklist_use_allowed=false`를 반환합니다.
- `internal_checklist_use_allowed`는 Core service의 `InternalChecklistUsePolicy`가
  계산합니다. Day 6은 응답 boolean만 신뢰하지 않고 같은 policy를 직접 호출해야 하며,
  화면이나 AI service의 검사로 대신하지 않습니다.

### InternalNoticeVersion과 수신 사건

- 공문 version은 수정하지 않는 `InternalNoticeVersion`으로 저장합니다.
- 문서 identity에는 `notice_id`, `family_id`, `version`, `issued_on`, 시행 기간과
  `supersedes_notice_id`가 포함됩니다.
- Importer는 입력 JSON 전체의 canonical SHA-256을 `source_record_hash`로 계산해 DB에
  저장합니다. Hash 값을 원천 JSON 안에 넣어 자기 자신을 hash하는 구조를 만들지
  않습니다.
- `version`은 사람이 읽는 순서 정보이며 단독 우선순위로 사용하지 않습니다.
- 시스템이 문서를 알게 된 사건은 별도 append-only `InternalNoticeReceipt`로 저장합니다.
- 같은 공문 ID와 같은 hash의 replay는 멱등 처리합니다.
- 같은 공문 ID의 다른 내용은 충돌로 거부합니다.
- 원천 문서 철회도 기존 공문을 수정하지 않고 별도 사건으로 기록합니다.

### 적용 공문 선택

원천 공문 선택은 다음 조건만 사용합니다.

1. receipt의 `received_at <= known_at`
2. `document_status=ISSUED`이고 `known_at`까지 보이는 철회 사건이 없음
3. `effective_from <= business_date`
4. `effective_to`가 없거나 `business_date < effective_to`

후보가 없으면 `NO_APPLICABLE_NOTICE`를 반환합니다. 여러 후보가 있으면 명시적인
`supersedes_notice_id` chain으로 하나를 결정할 수 있을 때만 후속 version을 선택합니다.
서로 관계없는 공문이 중첩되거나 chain이 끊기면 `AMBIGUOUS_EFFECTIVE_NOTICE`로
fail-closed 처리합니다. 가장 큰 version이나 가장 늦게 받은 공문을 임의 선택하지
않습니다.

`effective_from < received_business_date`처럼 과거 시행 공문을 늦게 받은 경우는
허용하되 `RETROACTIVE_NOTICE` warning을 반환합니다. 해당 공문은 실제 `received_at` 이전의
`known_at` 조회에는 나타나지 않습니다. 기록되지 않은 과거 지식을 만들어 내지
않습니다.

원천 공문 선택과 승인 checklist 선택은 별도 결과입니다. 적용할 공문이 있어도 구조화,
validation 또는 사람 검토가 끝나지 않았으면 공문 자체는 조회할 수 있지만 승인
checklist는 반환하지 않습니다. 응답은 다음 두 상태를 구분합니다.

- `noticeSelectionStatus`: `AMBIGUOUS`, `WITHDRAWN`, `NOT_YET_EFFECTIVE`,
  `NO_APPLICABLE_NOTICE`, `SELECTED`
- `checklistAvailabilityStatus`: `AVAILABLE`, `PENDING_EXTRACTION`,
  `PENDING_VALIDATION`, `PENDING_REVIEW`, `VALIDATION_STALE`, `VALIDATION_FAILED`,
  `UNAVAILABLE`

사람의 `APPROVE` 결정이 `known_at`까지 보이는 checklist만 `AVAILABLE`입니다. 실패나 대기
상태에서 이전 checklist를 새 공문의 결과인 것처럼 대신 반환하지 않습니다.

여러 조건이 동시에 성립할 때 대표 상태는 다음 우선순위를 사용합니다.

- `noticeSelectionStatus`:
  `AMBIGUOUS > WITHDRAWN > NOT_YET_EFFECTIVE > NO_APPLICABLE_NOTICE > SELECTED`
- `checklistAvailabilityStatus`:
  `UNAVAILABLE > VALIDATION_FAILED > VALIDATION_STALE > PENDING_REVIEW >`
  `PENDING_VALIDATION > PENDING_EXTRACTION > AVAILABLE`

대표 상태에 포함되지 않은 원인도 `blocking_reasons`와 `warning_reasons`에 모두
보존합니다. 상태 조합과 우선순위는
`contracts/fixtures/internal-checklist-availability-policy-cases.json`을 정답표로 두고
Java policy test가 전체 case를 검증합니다.

`AMBIGUOUS`는 클라이언트 요청 오류가 아니므로 `409`로 반환하지 않습니다. 알려진
family의 상태 조회는 `200`, `noticeSelectionStatus=AMBIGUOUS`, blocking reason
`AMBIGUOUS_EFFECTIVE_NOTICE`와 충돌한 candidate notice ID 목록을 반환합니다.

`internal_checklist_use_allowed=true`는 다음 조건을 모두 만족할 때만 가능합니다.

- 과거 `known_at` 조회가 아님
- 미래 `business_date` 조회가 아님
- `noticeSelectionStatus=SELECTED`
- `checklistAvailabilityStatus=AVAILABLE`
- 최신 visible validation이 `max_validation_age` 안에 있음
- 현재 공개 근거가 Day 4 confirmation policy를 통과함
- 현재 공개 fact와 승인 checklist 사이에 semantic mismatch가 없음

이 값은 내부 checklist와 공개 교차 검증 조건만 나타내며 대출 판단이나 전체 업무 승인을
의미하지 않습니다.

### 구조화 규칙과 근거

- 공문에서 구조화한 규칙은 immutable `InternalPolicyRuleVersion`으로 저장합니다.
- Rule identity는 rule key, subject type, value type, value, unit과 업무 의미를 canonical
  JSON으로 만든 SHA-256입니다.
- 시행 기간, parser version과 처리 시각은 의미 identity에 넣지 않고 source relation과
  extraction attempt에 보존합니다.
- `InternalPolicyRuleEvidence`가 rule을 notice version과 연결합니다.
- JSON fixture의 locator는 RFC 6901 JSON Pointer, evidence text와 evidence hash를
  포함합니다.
- 처리 성공과 실패는 `PolicyExtractionAttempt`에 append-only로 기록합니다.
- 알 수 없는 rule, 중복 locator와 주체가 불명확한 숫자는 추정하지 않고 실패합니다.

Day 5는 작은 합성 fixture를 위한 명시적 rule extractor만 구현합니다. 범용 parser DSL,
PDF, OCR과 LLM 기반 extraction은 포함하지 않습니다.

### 체크리스트 변경 후보와 revision

- 새 규칙과 직전 승인 checklist의 차이는 `ChecklistChangeProposal`로 생성합니다.
- Proposal은 base checklist version, target notice, rule과 generator version을 참조합니다.
- Proposal item은 추가, 수정, 삭제와 before/after 값을 명시합니다.
- Proposal과 item은 `DERIVED`로 분류합니다.
- 사람이 `MODIFY`하면 기존 proposal을 수정하지 않고 새 revision을 만듭니다.
- 새 revision은 `supersedes_proposal_id`, before hash, after hash와 수정 사유를
  보존합니다.
- 같은 입력과 generator version은 같은 canonical proposal 내용을 생성해야 합니다.
  실행 사건 ID는 별도로 둡니다.

Day 5에서는 결정적인 fixture generator를 사용합니다. LLM을 사용하지 않으므로 이
결과가 자연어 공문 해석 성능을 증명한다고 표현하지 않습니다.

### 자동 검증

- 자동 검증은 별도 append-only `AutomatedValidationResult`와 issue로 저장합니다.
- 결과는 `PASS`, `WARN`, `FAIL`이며 validator version, proposal hash, `validated_at`과
  사용한 evidence ID를 포함합니다.
- Validation을 다시 실행하면 이전 결과를 수정하지 않고 새 결과를 추가합니다.
- Validation 가시성은 `validated_at <= known_at`으로 판단합니다. 승인 판단과 과거 조회는
  해당 proposal revision에서 그 시점까지 보이는 최신 완료 validation만 사용합니다.

공개 근거 교차 검증은 구조화된 `fact_key`, `subject_type`, `value_type`, value와 unit을
비교합니다. 문자열 포함 여부만으로 비교하지 않습니다.

- fresh하고 비교 가능한 공개 fact와 값 또는 subject가 다르면
  `FAIL PUBLIC_FACT_MISMATCH`
- 필수 교차 검증 항목의 공개 근거가 pending, failed, stale 또는 unavailable이면
  `FAIL PUBLIC_EVIDENCE_UNCONFIRMED`
- 참고용 교차 검증 항목의 미확인은 `WARN PUBLIC_EVIDENCE_UNCONFIRMED`
- 공개 정보와 내부 규칙이 충돌해도 내부 규칙을 자동 수정하지 않음

Validation은 Day 4의 `PublicEvidenceConfirmationPolicy`를 직접 호출합니다. 검증 당시
사용한 public Observation, ProductTermsVersion, VersionEvidence와 fact identity를
결과에 저장합니다. 과거 validation을 재생할 때 현재 공개 상태를 다시 조회해 결과를
바꾸지 않습니다.

미래 시행 checklist의 교차 검증은 승인 당시 공개 상태만 증명합니다. 시행일 도래나 실제
상담 사용 시점의 공개 상태까지 보장하지 않습니다. Core service의
`InternalChecklistUsePolicy`는 조회와 상담 준비 요청마다 현재 공개 근거 freshness와
mismatch를 다시 검사하고, 새 충돌이 있으면 `internal_checklist_use_allowed=false`와
blocking reason을 반환합니다. Day 6은 이 policy를 우회할 수 없습니다.

KB 셀러론 법인 한도를 `200,000,000 KRW`로 만든 proposal fixture는 공개 근거의
`2,000,000,000 KRW`와 달라 반드시 `FAIL PUBLIC_FACT_MISMATCH`가 됩니다.

### 사람 검토 결정

- 자동 validation과 사람 결정은 ADR-003에 따라 분리합니다.
- `HumanReviewDecision`은 `APPROVE`, `MODIFY`, `REJECT`를 사용합니다.
- Decision에는 actor ID, 사유, `decided_at`, proposal hash, 적용한 validation result ID와
  before/after hash, validation policy version과 `max_validation_age`가 포함됩니다.
- 하나 이상의 `FAIL` issue가 있거나 validation이 없거나 proposal hash가 달라졌으면
  `APPROVE`를 거부합니다.
- `evaluated_at - validated_at > max_validation_age`이면 `VALIDATION_STALE`로 승인과
  현재 업무 사용을 차단하고 재검증을 요구합니다. 정확히 일치하는 경계는 유효하고
  초과하는 순간부터 차단합니다.
- `max_validation_age`와 validation approval policy version은 production 필수 설정이며,
  값이 없거나 0 이하이면 기동을 거부합니다.
- `WARN`이 있는 proposal을 승인하려면 명시적인 사유가 필요합니다.
- `MODIFY`는 승인 상태를 만들지 않고 새 proposal revision을 생성합니다. 새 revision은
  다시 validation을 받아야 합니다.
- 승인된 결과만 immutable `ApprovedChecklistVersion`을 생성합니다.
- 승인 checklist의 시행 기간은 원천 공문의 기간을 넘어설 수 없습니다.
- Decision 가시성은 `decided_at <= known_at`으로 판단합니다. 과거 조회에 이후 결정을
  노출하지 않습니다.

승인 checklist 내용과 적용 schedule을 분리합니다.

- `ApprovedChecklistVersion`은 승인된 checklist 내용의 immutable identity입니다.
- `ApprovedChecklistScheduleRevision`은 한 family의 적용 기간 전체를 나타내는 append-only
  schedule입니다.
- `ApprovedChecklistScheduleEntry`가 schedule revision 안에서 checklist version과
  `[effective_from, effective_to)`를 연결합니다.
- 후속 공문이 open-ended checklist를 대체하면 기존 행을 닫도록 수정하지 않습니다.
  기존 schedule을 복사해 이전 기간의 종료일을 새 시행일로 제한하고 새 checklist를
  추가한 schedule revision을 생성합니다.
- PostgreSQL `btree_gist` extension과 exclusion constraint로 같은
  `(schedule_revision_id, family_id)` 안의 `daterange(..., '[)')` 중첩을 거부합니다.
- `supersedes_schedule_revision_id`에는 unique constraint를 두어 같은 current schedule에서
  동시에 두 후속 schedule이 승인되는 것을 DB에서 거부합니다.
- `supersedes_schedule_revision_id IS NULL`인 최초 schedule은 family별 한 건만 허용하는
  partial unique index를 둡니다.
- 조회는 `created_at <= known_at`인 schedule revision만 대상으로 하며 visible chain의
  마지막 revision을 사용합니다.
- Advisory lock은 성능상 직렬화에 보조적으로 사용할 수 있지만 무결성 보증 수단으로
  인정하지 않습니다.
- 관리형 PostgreSQL에서 `btree_gist` extension 사용이 허용되는지는 migration 전제 검사와
  운영 배포 체크리스트에서 확인합니다.

정책 변경 검토 결정은 `SYNTHETIC_INTERNAL` governance event로 분류합니다. 향후 고객
신청과 상담에 대한 최종 행원 승인은 `SYNTHETIC_WORK`로 별도 관리합니다.

### 인증이 없는 현재 단계의 경계

Day 5에는 실제 조직 인증과 권한 체계가 없습니다. 클라이언트가 전달한 actor 문자열을
운영 사용자 신원으로 신뢰하지 않습니다.

- review command는 test/demo profile에서만 활성화합니다.
- 모든 actor ID는 합성 식별자이며 응답과 화면에 합성임을 표시합니다.
- production profile에서는 신뢰할 수 있는 인증 principal mapping이 없으면 review
  command를 기동하지 않습니다.
- 이 기능을 실제 운영 승인 통제로 표현하지 않습니다.
- 실제 배포 전에는 SSO, 역할 권한, 직무 분리, 대리 승인, 계정 수명주기와 접근 감사를
  별도 ADR과 테스트로 구현해야 합니다.

### 저장 분류와 importer

- 공문 원천, rule evidence와 승인 checklist는 `SYNTHETIC_INTERNAL`입니다.
- Proposal과 automated validation은 `DERIVED`입니다.
- 정책 검토 decision은 `SYNTHETIC_INTERNAL` governance event입니다.
- 공개 상품 record와 같은 응답이나 table에 dataset class 표시 없이 합치지 않습니다.

Synthetic internal bootstrap은 공개 상품 baseline importer와 분리합니다. 두 입력은
권위, 보안 등급과 운영 수명주기가 다르기 때문입니다.

- 별도 input allowlist와 fingerprint 사용
- 별도 transaction과 import run audit 사용
- 별도 NOLOGIN privilege bundle과 운영 login credential 사용
- 정상 애플리케이션 기동 시 자동 적재하지 않음
- runtime ingestion이 시작된 DB에는 bootstrap 재실행 금지

### API 경계

초기 조회 endpoint 후보는 다음과 같습니다.

```text
GET /api/v1/internal-policy/checklists/{familyId}/applicable
    ?businessDate=YYYY-MM-DD
    &knownAt=RFC3339 instant
```

- `businessDate`는 필수입니다.
- `knownAt`을 생략하면 요청 시작의 `evaluatedAt`을 사용합니다.
- 응답은 `evaluatedBusinessDate`, `businessDateInFuture`, `internalChecklistUseAllowed`,
  blocking reason과 warning reason을 포함합니다. 현재 적용한 validation approval policy
  version과 `maxValidationAge`도 함께 반환합니다.
- 응답은 선택된 notice, rule, approved checklist, validation과 review decision ID를
  포함합니다.
- Validation 당시 사용한 public Observation, ProductTermsVersion, VersionEvidence와 fact
  ID를 같은 응답에 포함합니다. 현재 사용 가능성 재평가에 다른 공개 근거를 사용했다면
  현재 evidence ID도 구분해 반환합니다.
- 합성 데이터 표시를 최상위에 포함합니다.
- 미래 `knownAt`은 `400 FUTURE_KNOWN_AT_NOT_ALLOWED`입니다.
- 등록되지 않은 family는 `404 POLICY_FAMILY_NOT_FOUND`입니다.
- 등록된 family에 적용 가능한 승인 checklist가 없으면 `200`과
  `noticeSelectionStatus` 및 `checklistAvailabilityStatus`, 구체적인 blocking reason을
  반환합니다.
- 중첩도 `200`, `noticeSelectionStatus=AMBIGUOUS`, blocking reason과 candidate notice ID
  목록으로 반환합니다.
- 과거 `knownAt` 조회는 read-only이며 approval command 입력으로 사용할 수 없습니다.

Review command의 HTTP 노출 여부는 인증 경계 검토 후 확정합니다. Day 5에서 노출한다면
test/demo profile에서만 활성화하고 같은 service policy를 우회하는 별도 저장 경로를 두지
않습니다.

### 오류와 감사

- Day 4의 Problem Detail과 trace ID 계약을 그대로 사용합니다.
- 요청 trace ID, proposal ID, validation result ID와 decision ID를 연결할 수 있는 로그를
  남깁니다.
- 공문 본문 전체, actor 표시 외 개인 정보, DB SQL과 credential을 로그에 남기지
  않습니다.
- 업무 record는 append-only이고 correction은 superseding record로 남깁니다.
- Break-glass 변경은 Day 4의 maintenance audit와 동일한 DB 경계를 사용합니다.

## 검토한 대안

### 공개 상품 페이지를 업무 기준으로 사용

접근하기 쉽지만 공개 페이지는 내부 업무 규칙의 발행/승인/시행 정보를 제공하지
않습니다. 내부 공문을 권위로 두고 공개 페이지는 교차 검증으로 제한합니다.

### `asOf` 하나로 모든 시간 표현

API는 단순하지만 시행일과 시스템 인지 시각을 구분할 수 없어 미래 지식이 과거에
나타납니다. `businessDate`와 `knownAt`을 분리합니다.

### 공문 상태 하나로 전체 workflow 표현

원천 문서 승인과 추출/validation/사람 검토가 섞입니다. 별도 entity와 append-only
event로 분리합니다.

### 공개 값과 다르면 공문 자동 수정

공개 페이지가 내부 공문을 덮어쓰는 잘못된 권위 역전이므로 사용하지 않습니다. 충돌을
차단하고 사람이 확인하게 합니다.

### mutable proposal과 checklist

구현은 단순하지만 최초 오류와 수정 이력이 사라집니다. 새 revision과 supersession을
사용합니다.

### Day 5부터 LLM extraction 구현

공문 계약, 시간 선택과 승인 경계가 고정되기 전에 비결정적 생성기를 붙이면 오류 원인을
분리하기 어렵습니다. 결정적인 generator와 validator로 E2E를 먼저 완성하고 LLM은 후속
단계에서 같은 proposal 계약 뒤에 연결합니다.

### 인증 없이 운영 approval API 제공

actor spoofing을 막을 수 없어 실제 통제가 되지 않습니다. demo 범위로 제한하고 production
profile에서는 비활성화합니다.

## 위험과 제한사항

- 합성 JSON 공문은 실제 문서 관리 시스템, 전자결재와 보안 등급을 재현하지 않음
- 공문 철회, 정정과 소급 시행의 실제 조직 규칙은 별도 정책 확인 필요
- 영업일 calendar와 휴일 처리를 포함하지 않음
- 실제 사용자 인증/인가와 직무 분리가 없음
- 범용 자연어 extraction과 LLM 평가를 포함하지 않음
- 대량 공문 검색, pagination, 보존 기간과 archive 정책 미정
- 공개 정보가 최신이어도 내부 규칙의 정확성을 보장하지는 않음
- 내부 공문 자체에 오류가 있을 때 공개 정보와의 충돌은 감지할 수 있지만 자동 해결하지
  않음
- runtime ingestion 이후 baseline 교체와 무중단 schema migration은 별도 운영 ADR 필요

## 결과

이 ADR이 승인되면 Day 5를 5a 계약/저장, 5b 시행일 선택, 5c 변경 후보/validation/사람
검토로 나눠 구현합니다. 각 단계는 PostgreSQL 통합 테스트와 계약 테스트 evidence를
확인한 뒤에만 완료 처리합니다.

Day 5가 끝나면 TrustAgent는 공개 상품 근거와 합성 내부 공문을 같은 권위로 섞지 않고,
특정 업무 기준일에 적용되는 승인 checklist와 그 생성/검증/검토 이력을 재현할 수
있습니다. 상담 준비안 생성과 최종 행원 승인은 다음 단계로 남깁니다.

## 승인 전 검토 요청

1. 문서 발행 상태와 TrustAgent proposal 검토 상태 분리
2. `businessDate`, `knownAt`, `evaluatedAt`의 의미와 미래 지식 차단
3. `[effectiveFrom, effectiveTo)` 경계와 supersession 기반 선택
4. 소급 시행 공문의 visibility와 warning 처리
5. 공문/rule/proposal/validation/decision/approved checklist entity 분리 수준
6. 공개 정보의 보조 권위와 mismatch 차단 방식
7. 필수/참고 공개 evidence 요구 수준
8. 20억원을 2억원으로 만든 proposal의 fail-closed 경로
9. `MODIFY` 이후 새 revision과 재검증 강제
10. 인증 없는 review command의 test/demo 제한
11. synthetic internal importer와 공개 baseline importer 분리
12. Day 6 상담 준비안에 필요한 추적 ID와 조회 계약 누락 여부
