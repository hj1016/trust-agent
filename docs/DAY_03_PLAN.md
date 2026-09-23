# 셋째 날 계획

## 목표

검증된 공개 snapshot에서 서비스가 조회할 수 있는 정규화된 product fact를 생성하고, 의미 있는 fact 변경 단위로 product version을 구분합니다.

## 프로젝트에서 담당하는 역할

Product fact pipeline은 raw HTML과 Spring 서비스 사이의 변환 계층입니다. 공개 페이지의 표현을 비교 가능한 값으로 정규화하고, 모든 값에 snapshot hash와 source locator를 연결합니다.

## 필요한 이유

- Spring API와 validator가 HTML 문구를 직접 해석하면 같은 값도 서로 다르게 처리할 수 있습니다.
- `20억원`을 정수 원 단위로 변환해야 `2억원` 같은 오류를 자동 비교할 수 있습니다.
- raw HTML의 메뉴나 표시 요소만 바뀌어도 snapshot hash는 달라질 수 있으므로 의미 있는 fact 변경과 구분해야 합니다.
- 근거 위치가 없으면 생성된 fact를 원문에서 재검증할 수 없습니다.

## 작업 순서

- [x] product version schema 정의
- [x] product fact schema 정의
- [x] source locator와 evidence hash 정의
- [x] 상품별 label 기반 parser 구현
- [x] 금액과 날짜 정규화 구현
- [x] fact set hash 기반 version 중복 제거
- [x] 공개 상품 3개의 baseline fact 생성
- [x] KB 셀러론 법인 한도 20억원 검증
- [x] parser 실패와 중복 조건 단위 테스트 작성
- [x] README와 evidence 갱신

## 완료 조건

- 공개 상품 3개의 정규화된 product version이 존재합니다.
- 모든 fact가 snapshot hash, source URL, locator와 evidence hash를 가집니다.
- KB 셀러론 법인 최대한도가 `2,000,000,000 KRW`로 저장됩니다.
- 원문에 없는 effective date를 생성하지 않습니다.
- 동일 fact set은 raw snapshot hash가 달라도 새 version을 만들지 않습니다.
- label이 없거나 여러 후보가 있으면 임의 선택하지 않고 실패합니다.
- 실제 artifact를 포함한 반복 가능한 테스트 evidence가 존재합니다.

## 검토한 대안

### Snapshot마다 product version 생성

구현은 단순하지만 메뉴와 표시 날짜 등 비업무 변경도 새 version이 됩니다. 정규화된 fact set의 canonical hash를 version 기준으로 사용합니다.

### 전체 HTML text에 대한 regex

동일 문구가 메뉴와 상세 영역에 반복돼 잘못된 값을 선택할 수 있습니다. 상세 영역의 label과 인접 본문을 사용합니다.

### 금리 숫자만 저장

공개 페이지의 금리는 조건과 기준일에 따라 달라지며 최종 적용 금리가 아닙니다. MVP에서는 광고된 금리 문구와 명시된 기준일을 근거 그대로 저장합니다.

## 위험과 대응

- HTML label 변경으로 parser가 실패할 수 있음: 실패를 명시하고 기존 version을 유지합니다.
- 여러 위치에 같은 label이 생길 수 있음: 후보가 정확히 한 건일 때만 추출합니다.
- 금액 단위 변환 오류가 발생할 수 있음: 억원 변환과 KB 셀러론 20억원 회귀 테스트를 둡니다.
- Source locator가 DOM 변경에 약할 수 있음: label, selector, evidence text와 evidence hash를 함께 저장합니다.
- 원문 표시 날짜가 매일 바뀔 수 있음: 해당 날짜가 fact에 포함되면 새 version으로, 근거 외 변경이면 같은 version으로 판정합니다.

## 다음 작업과의 연결

생성된 product version과 fact를 Spring Boot 및 DB에 적재하고 요청 기준일에 유효한 version을 선택하는 API를 구현합니다.

## 후속 운영 계획

- 공개 상품 변경 감지용 Spring Batch Job 구현
- 초기에는 수동 Job 실행을 지원하고 이후 scheduler 또는 외부 cron 연결
- 상품 수가 적은 초기 단계에서는 Tasklet 기반 step 구성 우선 검토
- Snapshot 수집, fact 추출, version 비교, 변경 후보 등록 단계 분리
- 감지한 변경을 자동 적용하지 않고 담당자 검토 대기 상태로 저장
- 실제 업무에서는 내부 공문을 공식 변경 기준으로 사용하고 공개 상품 정보는 교차 검증에 활용
- 프로젝트에서는 실제 내부 공문 대신 `SYNTHETIC_INTERNAL` 합성 공문으로 흐름 검증
