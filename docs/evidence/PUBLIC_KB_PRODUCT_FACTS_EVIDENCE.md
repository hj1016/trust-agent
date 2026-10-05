# 공개 KB 상품 fact 정규화와 버전 관리 검증 기록

> 이 문서는 최초 Day 3 구현 당시의 실행 결과를 보존합니다. 이후 광고 금리 quote와
> 상품 조건 version을 분리한 hardening으로 active contract와 baseline이 변경됐습니다.
> 현재 결과와 정정 내용은 `PUBLIC_KB_OBSERVATION_HARDENING_EVIDENCE.md`를 기준으로 봅니다.

## 검증 시점

- 실행일: 2026-09-23
- 브랜치: `feat/public-product-facts`
- 실행 환경: Python 3.11.8

## 구현 역할

Product fact pipeline은 비공개 raw HTML snapshot과 이후 Spring 서비스 사이의 변환 계층입니다. 공개 페이지의 상품명, 대상, 한도, 광고 금리, 상환 방법과 판매 상태를 비교 가능한 값으로 정규화하고 각 값에 원문 근거를 연결합니다.

## 필요한 이유

- 서비스가 HTML 구조와 표현을 직접 해석하지 않도록 입력 형식을 고정합니다.
- `20억원`을 `2,000,000,000 KRW`로 정규화해 이후 validator가 금액 오류를 비교할 수 있게 합니다.
- 메뉴와 화면 구성만 바뀐 raw snapshot과 실제 상품 fact가 바뀐 product version을 구분합니다.
- 생성된 값이 어느 snapshot의 어느 문구에서 왔는지 다시 검증할 수 있게 합니다.

## 자동화 테스트

실행 명령:

```bash
TRUSTAGENT_REQUIRE_PRIVATE_SNAPSHOTS=1 python3 -m unittest discover -s tests -v
```

결과:

```text
Ran 36 tests in 0.064s

OK
```

검증 범위:

- product version과 product fact schema
- fact set canonical hash와 product version ID 재현성
- 모든 fact의 snapshot hash, source URL, locator, evidence text와 evidence hash
- KB 셀러론 법인 한도 `20억원`의 `2,000,000,000 KRW` 변환
- 원문에 없는 effective date를 `null`로 유지
- 업무 의미가 같은 snapshot의 version 중복 제거
- label 누락과 중복 시 임의 추출 방지
- `2억원`과 `20억원` 단위 변환 회귀 테스트
- 기존 snapshot collector와 데이터 분류 contract 회귀 테스트

## Public clone 조건

비공개 raw HTML이 없는 공개 저장소 환경을 다음 명령으로 모의했습니다.

```bash
TRUSTAGENT_PRIVATE_ARTIFACT_ROOT=/private/tmp/trust-agent-missing-artifacts \
python3 -m unittest discover -s tests -v
```

결과는 36개 중 비공개 snapshot 무결성 테스트 1개만 의도대로 skip되고 나머지는 통과했습니다. 정규화된 product version과 fact에는 공개 페이지에서 가져온 필요한 evidence 문구만 포함되며 raw HTML은 Git 추적 대상에 포함되지 않습니다.

## 생성 결과

실행 명령:

```bash
python3 scripts/extract_public_kb_product_facts.py --all
```

생성된 baseline:

| product_key | version 수 | 변경 근거 |
| --- | ---: | --- |
| `small-business-credit` | 2 | 광고 금리 기준일 `2026-09-21`, `2026-09-22` |
| `boss-plus-overdraft` | 2 | 광고 금리 기준일 `2026-09-21`, `2026-09-22` |
| `kb-seller-loan` | 1 | 수집된 동일 원문 1건 |

같은 명령을 즉시 다시 실행했을 때 5개 입력이 모두 `DEDUPLICATED`로 반환됐습니다.

KB 셀러론 baseline의 주요 검증값:

- 개인사업자 최대한도: `500,000,000 KRW`
- 법인사업자 최대한도: `2,000,000,000 KRW`
- 법인사업자 한도 evidence: `법인사업자 ... 20억원`
- product version fact set hash: `sha256:27d45dcd93aca48a5227049e313cd4160e6f3cd4892837df123d0a1c16250cd2`
- `effective_from`, `effective_to`: `null`

## 설계 결정

- Product version은 raw snapshot hash가 아니라 정규화된 fact set의 canonical SHA-256으로 식별합니다.
- 수집 시각과 source locator는 fact set hash에서 제외해 같은 의미의 값을 중복 version으로 만들지 않습니다.
- Source locator에는 snapshot hash, source URL, selector, label, evidence text와 evidence hash를 보존합니다.
- 공개 페이지에 명시되지 않은 effective date는 수집 시각으로 추정하지 않습니다.
- 공개 금리는 최종 적용 금리가 아니므로 숫자 결론값이 아니라 광고 문구와 명시된 기준일로 저장합니다.

상세 결정은 `docs/adr/ADR-005-product-version-and-source-locator.md`에 기록합니다.

## 검토한 대안과 위험

Snapshot마다 version을 만들면 구현은 단순하지만 화면 구성 변경도 업무 변경으로 오인합니다. 전체 HTML regex는 반복된 문구를 잘못 선택할 수 있어 상품별 상세 label과 인접 본문을 사용합니다.

현재 parser는 공개 페이지의 label과 DOM 구조 변경에 영향을 받습니다. 후보가 정확히 하나가 아니면 실패하고 기존 version을 유지하도록 했지만, 운영 단계에서는 parser 실패 알림과 수동 검토 절차가 추가로 필요합니다. Source locator도 DOM 변경에 약할 수 있어 selector만 저장하지 않고 label과 evidence text 및 hash를 함께 저장했습니다.

## 다음 작업

Spring Boot 프로젝트 골격과 저장 계층을 만들고 product version과 fact를 적재합니다. 그다음 요청 기준일에 유효한 version을 선택하는 조회 API를 구현하되, 원문에 effective date가 없는 현재 baseline은 임의로 유효 기간을 추정하지 않는 정책을 먼저 정의해야 합니다.

위 다음 작업에 앞서 발견된 관측·quote·실패 이력 문제는 ADR-006과 Day 3 hardening에서
해결했습니다. Spring 구현은 신규 ProductTermsVersion 및 freshness contract를 입력으로
사용합니다.
