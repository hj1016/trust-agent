# 첫날 계획

## 목표

2주 MVP의 범위를 고정하고 이후 구현이 공통으로 따를 데이터 계약과 저장소 구조를 만듭니다.

## 완료 항목

- [x] 2주 MVP 목표와 제외 범위 작성
- [x] KB 셀러론 중심 대표 데모 작성
- [x] 네 가지 데이터 분류와 신뢰 경계 정의
- [x] 초기 ADR 작성
- [x] 공개 snapshot manifest schema 작성
- [x] 공개 상품 catalog baseline 작성
- [x] 애플리케이션, 데이터셋, 인프라, 테스트 디렉터리 구성
- [ ] Git baseline commit 생성
- [ ] 공개 상품 3개의 실제 첫 snapshot 수집
- [ ] 실제 SHA-256과 schema를 통과하는 manifest 생성
- [ ] 첫 합성 공문과 합성 기업 및 신청 fixture 생성
- [ ] 잘못된 데이터 분류와 경로 조합을 거부하는 contract test 작성

## 첫날 완료 조건

공개 상품 3개의 실제 snapshot과 검증된 manifest가 존재하고 합성 fixture가 명확히 분리되며 contract test가 의도적인 경계 위반을 한 건 이상 거부해야 합니다.

## 보존할 evidence

- snapshot 파일과 SHA-256
- schema 검증 결과
- 데이터 경계 contract test 결과
- ADR
- Git commit ID

## 다음 구현 순서

1. 불변 snapshot 수집과 hash 중복 제거
2. 상품 version과 fact 저장
3. 요청 기준일 필터
4. 합성 공문 workflow와 승인 상태 전이
5. 검색, Agent, 자동 검증, 화면, 배포, 평가
