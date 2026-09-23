# ADR 005 Product version과 source locator

## 상태

승인

## 배경

Raw HTML은 업무 fact와 무관한 변경에도 hash가 달라질 수 있습니다. 반대로 정규화된 fact는 자동 검증과 기준일 조회에서 안정적으로 비교할 수 있어야 하며 원문 근거를 재현할 수 있어야 합니다.

## 결정

- Product version ID는 정규화된 fact set의 canonical SHA-256으로 생성합니다.
- Snapshot hash만 달라지고 fact set이 같으면 기존 product version을 재사용합니다.
- Fact set hash에는 fact key, subject type, value type, value와 unit만 포함합니다.
- 수집 시각과 source locator는 fact set hash에 포함하지 않습니다.
- Fact는 source snapshot hash, source URL, selector, label, evidence text와 evidence SHA-256을 보존합니다.
- 원문에 명시되지 않은 effective date는 `null`로 유지합니다.
- 금리는 최종 적용 금리로 정규화하지 않고 공개 페이지의 광고 문구와 기준일을 저장합니다.

## 영향

- Raw snapshot 변경 수와 product version 수가 다를 수 있습니다.
- 같은 fact set의 후속 snapshot은 새 version 파일을 만들지 않습니다.
- Parser는 label이 없거나 중복되면 실패하며 값을 추정하지 않습니다.
- 다음 DB 모델은 product version과 fact set hash의 유일성을 보장해야 합니다.
