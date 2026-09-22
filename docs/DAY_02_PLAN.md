# 둘째 날 계획

## 목표

KB 공개 상품 catalog를 입력으로 받아 raw HTML을 비공개 artifact에 저장하고, SHA-256 기반 manifest를 안전하게 생성하는 반복 가능한 수집 경로를 완성합니다.

## 프로젝트에서 담당하는 역할

Snapshot collector는 외부 공개 페이지와 이후 product version 및 fact 저장 사이의 입력 계층입니다. Spring API와 Agent가 웹페이지를 직접 조회하지 않고 출처와 hash가 검증된 데이터를 사용하도록 합니다.

## 필요한 이유

- 수동 다운로드와 manifest 작성은 반복 실행과 오류 재현이 어렵습니다.
- 동일 원문을 여러 번 저장하면 어떤 snapshot이 기준인지 불명확해집니다.
- 수집 실패 중 일부 파일만 남으면 이후 fact와 감사 이력의 근거가 깨집니다.
- 허용되지 않은 URL을 수집하면 `PUBLIC_KB` 분류의 신뢰성이 훼손됩니다.

## 작업 순서

- [x] catalog와 공식 KB URL 검증
- [x] HTTP 및 수동 취득 입력 지원
- [x] raw byte 기준 SHA-256 계산
- [x] content-addressed 비공개 artifact 저장
- [x] 동일 hash artifact와 manifest 중복 방지
- [x] 임시 파일 기반 원자적 저장과 실패 정리
- [x] manifest schema 검증 후 저장
- [x] mock 기반 단위 테스트 작성
- [x] 공개 상품 3개 통합 수집 검증
- [x] README와 evidence 갱신

## 완료 조건

- 공개 상품 3개를 catalog 기준으로 수집할 수 있습니다.
- 동일 원문을 반복 수집해도 artifact와 manifest가 증가하지 않습니다.
- 변경된 원문은 새로운 hash의 artifact와 manifest로 저장됩니다.
- 허용되지 않은 URL, HTML이 아닌 응답, 상품 식별 문자열이 없는 응답을 거부합니다.
- 수집 실패 시 불완전한 최종 파일을 남기지 않습니다.
- 수동 취득은 사유가 있을 때만 허용합니다.
- 실제 외부 통신 없이 실패와 중복 조건을 반복 검증하는 테스트가 존재합니다.

## 검토한 대안

### 요청 시 실시간 수집

사용자 요청마다 최신 페이지를 읽을 수 있지만 외부 장애와 페이지 변경이 상담 흐름에 직접 영향을 줍니다. 이번 MVP에서는 사전 수집 방식을 사용합니다.

### 파일명 기반 저장

상품명과 날짜 기반 파일명은 같은 날 여러 내용이 수집될 때 충돌할 수 있습니다. 원문 SHA-256을 object key로 사용하는 content-addressed 저장을 선택합니다.

### 실제 사이트 기반 단위 테스트

실제 사이트 상태와 네트워크에 따라 테스트 결과가 달라집니다. 단위 테스트는 mock 응답을 사용하고 실제 수집은 별도 통합 evidence로 남깁니다.

## 위험과 대응

- 공통 메뉴 변경만으로 hash가 달라질 수 있음: snapshot 변경과 product fact 변경을 다음 단계에서 별도로 구분합니다.
- redirect로 허용되지 않은 호스트에 접근할 수 있음: 최초 URL과 최종 URL을 모두 검증합니다.
- 큰 응답으로 저장 공간이 소진될 수 있음: 최대 응답 크기를 제한합니다.
- 저장 도중 프로세스가 종료될 수 있음: 같은 디렉터리의 임시 파일을 사용해 원자적으로 생성합니다.
- public clone에는 raw HTML이 없음: manifest 검증은 항상 실행하고 원문 무결성 검증은 비공개 artifact가 있는 환경에서 실행합니다.

## 다음 작업과의 연결

Collector가 생성한 manifest와 비공개 artifact를 입력으로 product version과 정규화된 fact를 생성합니다. 요청 기준일 조회는 product version과 fact가 준비된 뒤 구현합니다.

## 완료 증거

실행 명령, 테스트 결과, 실제 수집 hash와 남은 위험은 `docs/evidence/DAY_02_EVIDENCE.md`에 기록합니다.
