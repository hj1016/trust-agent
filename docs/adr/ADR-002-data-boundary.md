# ADR 002 공개 데이터와 합성 데이터 분리

## 상태

승인

## 배경

KB 상품 페이지는 공개 자료이지만 내부 공문, 규정, 기업 기록과 업무 이력은 공개되지 않으므로 프로젝트용으로 생성해야 합니다.

## 결정

`PUBLIC_KB`, `SYNTHETIC_INTERNAL`, `SYNTHETIC_WORK`, `DERIVED` 네 가지 데이터 분류를 사용합니다. 경로, schema, 저장소 제약, API, 화면, 테스트, 평가 결과에서 같은 구분을 강제합니다.

## 영향

- 합성 record는 항상 synthetic 표시와 고지를 가집니다.
- 공개 fact는 공식 source URL과 불변 snapshot hash가 필요합니다.
- 파생 결과는 원천 ID와 생성 정보를 보존합니다.
- CI가 허용되지 않은 데이터 분류와 경로 조합을 거부합니다.
