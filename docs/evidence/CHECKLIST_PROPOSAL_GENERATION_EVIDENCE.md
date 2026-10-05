# checklist 변경안 생성 검증 기록 (TASK-005)

## 구현 범위

새 공문 version의 구조화 규칙과 그 공문군의 직전 승인 checklist를 비교해 **checklist 변경안(proposal)** 을 만든다. 변경안은 승인이 아니며 파생 데이터(DERIVED)로 저장된다. 적용 공문 조회의 사용 허용 판단은 바뀌지 않는다.

- DB 구조 변경 파일 V6: 승인 checklist 항목 테이블(`approved_checklist_item`), 승인 checklist의 출처 구분 컬럼(`origin`, HUMAN_REVIEW 또는 FIXTURE), 테스트용 예시 데이터 적재 기록, 변경안과 변경 항목, 생성 실행 기록. 신규 테이블 5개 전부 append-only 가드와 보호 목록에 등록(보호 trigger 50 → 60, 애플리케이션 테이블 25 → 30).
- 변경안 생성기: 규칙 키(`rule_key`) 단위로 추가, 수정, 삭제를 판정하고 전후 값과 해시를 남긴다. 같은 입력과 같은 생성기 버전은 같은 변경안 ID를 만든다. LLM을 쓰지 않는다.
- 테스트용 승인 checklist 예시 데이터 적재 도구: 출처를 FIXTURE로 표시해 적재한다. 같은 공문군에 사람 검토로 승인된 checklist가 있으면 거부한다. 같은 ID의 내용이 다르면 거부한다. 재실행은 멱등이다. runtime 계정 권한으로만 동작하고 적재 도구(importer) 계정 권한은 늘리지 않았다.
- 운영 기동 거부: production profile에서 변경안 생성이나 예시 데이터 적재 설정 중 하나라도 켜져 있으면 기동을 거부한다(`DEMO_FEATURE_ENABLED_IN_PROD`).
- 적용 공문 조회 응답 계약은 바꾸지 않았다(`latestProposalId` 미추가).

## 변경안 생성 규칙 (요약)

1. 기준 checklist: 대상 공문 시행일 전날에 적용되는 승인 checklist. 없으면 생성하지 않고 실패 기록(`NO_BASE_CHECKLIST`)만 남긴다.
2. 비교 단위는 규칙 키. 새 공문에만 있으면 추가(ADD), 기준에만 있으면 삭제(REMOVE), 둘 다 있고 내용이 다르면 수정(MODIFY), 같으면 항목을 만들지 않는다.
3. 금융 비율은 문자열 십진수 그대로 비교한다. 부동소수점은 거부한다.
4. 변경안 ID는 기준 checklist, 대상 공문, 생성기 버전, 항목 내용의 SHA-256이다. 재실행은 변경안을 다시 만들지 않고 실행 기록만 추가한다.

대표 결과(중도상환수수료 공문 v1 → v2): 항목 2개. `CHECK_CUSTOMER_CONTRACT_DATE` 추가, `CHECK_PREPAYMENT_FEE_RATE` 수정(1.2퍼센트 → 0.8퍼센트, 시행일 2026-10-01, 조건 2개). `CHECK_NOTICE_SOURCE`는 내용이 같아 항목 없음. 변경안 ID `checklist-proposal:sha256:59ad46b8...25d524`(정답 파일 `contracts/fixtures/prepayment-fee-v2-proposal.expected.json`).

## 테스트 evidence

검증 대상 revision: 브랜치 `feat/checklist-change-proposal` HEAD. 환경: 로컬 macOS arm64, Java 21(ms-21.0.11), Docker, PostgreSQL 18.6 Testcontainers, Python 3.11.8.

```text
./gradlew clean test bootJar --offline --no-daemon
106 tests completed (82 + 24), failures 0, skipped 0
executable jar 생성

python3 -m unittest discover -s tests   (비공개 artifact 지정)
Ran 60 tests (57 + 3)
OK
```

추가된 검증과 완료 확인 조건(TASK-005 AC) 대응:

| 테스트 | 보장 | AC |
|---|---|---|
| 생성기 단위 3건 | 추가/수정/삭제 분류와 규칙 키 정렬, 같은 입력의 같은 ID(입력 순서 무관, 생성기 버전 다르면 다른 ID), 규칙 키 중복과 부동소수점 거부 | AC-01, 02, 07 |
| 변경안 통합 6건 | 정답 파일과 일치하는 변경안 생성, 재실행 멱등(실행 기록만 증가), 승인 checklist 없는 공문군은 `NO_BASE_CHECKLIST` 실패 기록, 미수신/철회 공문은 `TARGET_NOTICE_NOT_VISIBLE`, 항목 형태 제약(23514)과 append-only(42501), 같은 변경안 두 번 대체 거부(23505)와 다른 공문군 FK 거부(23503) | AC-01~07 |
| 예시 데이터 통합 5건 | 멱등 적재와 실행 기록 2건, 같은 ID 내용 불일치 거부(`FIXTURE_CONTENT_CONFLICT`)와 rollback, HUMAN_REVIEW 존재 시 거부(`HUMAN_APPROVAL_EXISTS`), 공문군 불일치 FK(23503)와 규칙 키 중복(23505)과 두 번째 root schedule(23505)과 append-only(42501), importer 계정 INSERT 불가와 runtime 허용 | AC-11~16 |
| 적용 공문 조회 추가 3건 | 예시 checklist가 있는 v1 기간은 `AVAILABLE`이지만 검증과 공개 근거 재평가 전이라 사용 불가, v2 기간은 v1로 대체하지 않고 `PENDING_VALIDATION`, 응답 필드 집합 19개 불변 | AC-08, 09, 22 |
| 운영 기동 거부 4건 | 변경안 생성 단독, 예시 적재 단독, 둘 다, 둘 다 꺼짐 또는 미설정 | AC-17~21 |
| 기존 schema 테스트 갱신 | migration 6개, 애플리케이션 테이블 30개, 보호 trigger 60개, schema version 6 | AC-15 |
| Python 계약 3건 | 예시 데이터 2종 schema와 FIXTURE 표시, 항목이 v1 공문 규칙과 규칙 version ID에 대응, 정답 변경안이 schema를 만족하고 두 공문에서 재계산한 해시와 ID가 일치 | AC-23 |

첫 실행에서 테스트 1건이 실패했다. 원인은 테스트 설계 오류(이미 대체된 변경안을 다시 대체하려 해 FK 전에 unique 위반이 발생)였고 기대값을 고쳤다. 업무 로직 결함은 아니다.

## 한계

- 변경안은 CLI 실행(ApplicationRunner)으로만 만든다. HTTP 노출은 없다.
- 자동 검증(TASK-006)과 사람 결정(TASK-007)이 없어 변경안이 있어도 checklist 사용 허용은 바뀌지 않는다.
- 예시 승인 checklist는 test/demo 전용이며 실제 승인 기록이 아니다. 운영에서는 적재할 수 없다.
- 수정(MODIFY) 결정에 따른 변경안 revision 생성 경로는 테이블과 제약만 준비했고 흐름은 TASK-007에서 구현한다.
