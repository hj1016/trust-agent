# ADR 004 공개 원문 snapshot 저장 정책

## 상태

승인

## 배경

TrustAgent는 공개 상품 fact의 출처와 무결성을 재현하기 위해 raw HTML snapshot을 보존해야 합니다. 저장소는 향후 공개될 예정이므로 제3자 웹페이지의 HTML 전체를 Git 이력에 포함하지 않는 저장 정책이 필요합니다.

## 결정

- raw HTML snapshot은 Git 외부의 비공개 artifact로 저장합니다.
- artifact object key는 SHA-256을 포함하는 content-addressed 형식을 사용합니다.
- Git에는 source URL, 수집 시각, byte size, SHA-256, storage type과 object key를 포함한 manifest를 저장합니다.
- 정규화된 공개 fact, schema와 검증 코드는 Git에서 관리합니다.
- 로컬 개발의 기본 비공개 artifact 위치는 `.private-artifacts`로 사용하고 Git에서 제외합니다.
- 자동 수집 실패 시 공식 공개 페이지에서 취득한 원문에 한해 수동 취득을 허용하며 취득 방법과 실패 사유를 기록합니다.
- 복사 후 편집한 본문과 screenshot은 원문 snapshot으로 인정하지 않습니다.

## 영향

- public clone에서는 manifest와 데이터 분류 검증을 항상 실행할 수 있습니다.
- raw HTML 무결성 검증은 비공개 artifact가 제공된 환경에서 실행합니다.
- 비공개 artifact는 Git과 별도로 백업하고 접근 권한을 관리해야 합니다.
- 저장 backend가 변경되어도 manifest의 content-addressed object key와 SHA-256은 유지합니다.
