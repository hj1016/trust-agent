# ADR 003 자동 검증과 사람 승인 분리

## 상태

승인

## 배경

자동 검증을 통과했다는 사실만으로 AI가 만든 변경안이나 준비안을 업무에 사용하기 적합하다고 판단할 수 없습니다.

## 결정

자동 검증 결과와 사람의 결정을 별도 entity로 저장합니다. 중대 자동 검증 실패는 승인을 차단하며 PASS는 사람 검토가 가능한 상태만 의미합니다.

## 영향

- 자동 검증은 evidence와 validator version이 포함된 `PASS`, `WARN`, `FAIL`을 생성합니다.
- 사람 검토는 actor, 사유, 시각, before와 after hash가 포함된 `APPROVE`, `MODIFY`, `REJECT`를 생성합니다.
- Agent와 validator에는 승인 권한을 부여하지 않습니다.
