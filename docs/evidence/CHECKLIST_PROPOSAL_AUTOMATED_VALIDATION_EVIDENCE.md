# checklist 변경안 자동 검증 검증 기록 (TASK-006)

## 구현 범위

checklist 변경안(proposal)을 공문 원문의 구조화 규칙, 기준 승인 checklist, 공개 상품 근거와 대조해 **PASS / WARN / FAIL**로 판정하고 결과, 세부 오류(issue), 실행 기록을 파생 데이터(DERIVED)로 저장한다. 판정은 승인이 아니며, PASS여도 checklist 사용 허용(`internalChecklistUseAllowed`)은 바뀌지 않는다. 사람 승인 기능, 화면, AI 기능은 범위 밖이다.

- DB 구조 변경 파일 V7: `automated_validation_result`, `automated_validation_issue`, `validation_run`. 결과와 issue의 행 단위 모순은 복합 FK(`(validation_result_id, status)`)와 CHECK(FAIL issue는 FAIL 결과에만, WARN issue는 WARN 또는 FAIL 결과에만)로, 개수 단위 모순(FAIL issue 없는 FAIL 결과, WARN issue 없는 WARN 결과)은 저장 완료(commit) 시점 deferred constraint trigger로 거부한다. 신규 테이블 3개 전부 append-only 가드와 보호 목록에 등록(애플리케이션 테이블 30 → 33, 보호 trigger 60 → 66, migration 6 → 7).
- 검증기(`ProposalValidator`): 검사 V-01~V-11(값 일치, 시행일, 변경 전 값 연속성, 숫자 형식과 범위, 대상 상품 존재, 조건, 삭제 항목, 공개 근거 교차 검증, 교차 검증 없음 INFO, 기준 checklist 최신성, 대상 공문 철회). SQL과 LLM을 쓰지 않는다.
- 검증 서비스(`ProposalValidationService`): 결과, issue, 성공 실행 기록을 한 트랜잭션으로 저장한다. 저장 전 서비스 검사(`VALIDATION_INCONSISTENT`)와 DB 제약이 같은 조건을 이중으로 강제한다. 중간 실패 시 전부 취소하고 실패 실행 기록만 남긴다(`VALIDATION_WRITE_FAILED`). 공개 근거 교차 검증은 기존 공개 상품 관측 상태 조회와 freshness 정책을 그대로 쓴다.
- 적용 공문 조회 반영: `knownAt`까지 생성된 최신 변경안 revision의 `validated_at <= knownAt`인 최신 결과로 상태를 정한다. 결과 없음 `PENDING_VALIDATION`, FAIL `VALIDATION_FAILED`, 유효 기간(`trust-agent.validation-policy.max-validation-age`) 초과 `VALIDATION_STALE`, PASS/WARN `PENDING_REVIEW`(차단 사유 `HUMAN_REVIEW_PENDING`). 응답에 nullable 필드 2개 `validatedProposalId`, `validationResultId`를 추가했다(제안 1a). 기존 19개 필드 의미는 바꾸지 않았다.
- 셀러론 승인 checklist 예시(`seller-loan-checklist-v1.*`, origin `FIXTURE`): 공개 근거 교차 검증 테스트용. TASK-005 예시와 같은 조건(실제 승인 기록 아님, production 적재 거부, 승인과 사용 허용 우회 없음).
- 운영 기동 거부: `trust-agent.proposal-validation.enabled`를 demo 전용 설정에 추가. `TRUST_AGENT_VALIDATION_MAX_AGE`(0보다 커야 함)와 `TRUST_AGENT_VALIDATION_POLICY_VERSION`은 production 필수 설정.

## 테스트 evidence

검증 대상 revision: 브랜치 `feat/proposal-automated-validation` HEAD(PR 본문의 commit). 환경: 로컬 macOS arm64(Darwin 27.0), Java 21(openjdk 21.0.8), Docker, PostgreSQL 18.6 Testcontainers, Python 3.11.8.

```text
./gradlew clean test bootJar --offline --no-daemon
130 tests completed (기존 106 + 신규 24), failures 0, errors 0, skipped 0
executable jar 생성

# 비공개 artifact를 제공한 로컬
TRUSTAGENT_PRIVATE_ARTIFACT_ROOT=<비공개 artifact 경로> TRUSTAGENT_REQUIRE_PRIVATE_SNAPSHOTS=1 \
  python3 -m unittest discover -s tests -t .
Ran 62 tests (기존 60 + 신규 2), 통과 62, 실패 0, 건너뜀 0

# 공개 CI 조건(비공개 artifact 없음, CI 명령과 동일한 discover)
python3 -m unittest discover -s tests -t .
Ran 62 tests, 통과 60, 실패 0, 건너뜀 2 (사유: "비공개 snapshot artifact가 제공되지 않았습니다.")
```

Java 신규 24 = 검증기 단위 7 + 검증 통합 10 + 적용 공문 조회 추가 5(14 → 19) + 운영 기동 거부 추가 2(4 → 6). 기존 106개는 개수가 유지됐고 기대값만 schema version 6 → 7, 테이블 30 → 33, trigger 60 → 66, fixture family 1 → 2로 바뀐 곳이 있다(아래 "기존 테스트 변경"). 공개 CI의 실제 실행 결과는 PR의 checks에서 확인한다.

완료 확인 조건(TASK-006 AC) 대응:

| 테스트 | 보장 | AC |
|---|---|---|
| 검증기 단위 7건 (`ProposalValidatorTest`) | 사례 1(PASS, INFO만), 2(`VALUE_MISMATCH`), 3(`EFFECTIVE_DATE_MISMATCH`), 4(`UNKNOWN_PRODUCT_KEY`), 5("12e-1"과 120퍼센트 `INVALID_NUMERIC_VALUE`), 6(`BEFORE_VALUE_MISMATCH`, `BEFORE_VALUE_NOT_STATED` WARN), 7(`ITEM_REMOVED` WARN, `MISSING_CONDITIONS` WARN), 10(참고용 근거 미확인 WARN), 8(`PUBLIC_FACT_MISMATCH`: 공개 값 불일치와 변경안 값 불일치), 9(필수 근거 미확인 FAIL), 11/12(`BASE_CHECKLIST_STALE`, `TARGET_NOTICE_WITHDRAWN`), 판정 집계 | AC-01~08 |
| 검증 통합 10건 (`ProposalValidationIntegrationTest`, 격리 DB) | 중도상환수수료 변경안 PASS와 정답 파일 일치(INFO 2건, rule_key 대응), 저장 컬럼(`DERIVED`, `proposal_hash`=after_hash, `validated_at`, 실행 기록 SUCCEEDED). 셀러론: 24h 정책에서 필수 근거 미확인 FAIL, 30일 정책에서 공개 값 20억 일치 PASS와 관측/약관/근거/fact ID 저장, 기대값 10억 참조 추가 시 `PUBLIC_FACT_MISMATCH` FAIL이고 내부 규칙 불변. 철회 공문과 대체된 변경안 FAIL. 재검증은 결과 2건과 실행 기록 2건이고 이전 결과 불변, run ID 충돌 거부, 없는 변경안 실패 기록. issue 저장 중 실패 시 결과/issue/성공 기록 0건과 실패 기록 1건. 모순 판정은 저장 전 거부. DB: PASS 결과에 FAIL/WARN issue 23514, 거짓 result_status 23503, FAIL issue 없는 FAIL 결과와 WARN issue 없는 WARN 결과는 commit 시 23514, 알맞은 issue가 있으면 commit 성공. 신규 테이블 UPDATE/DELETE 42501 | AC-01, 07, 08, 09, 10, 11 |
| 적용 공문 조회 추가 5건 (`InternalPolicyApplicableIntegrationTest` 14 → 19) | 변경안만 `PENDING_VALIDATION`(두 필드 null), FAIL `VALIDATION_FAILED`, WARN/PASS `PENDING_REVIEW` + `HUMAN_REVIEW_PENDING`, 모두 사용 불가. `knownAt`이 `validated_at`보다 1초 이르면 그 결과가 보이지 않고 정확히 같으면 보임. 새 revision이 보이면 이전 변경안 결과를 쓰지 않음. 24h + 1초 뒤 `VALIDATION_STALE`, 정확히 24h는 유효. 응답 필드 = 기존 19 + 2. 셀러론 예시 일정은 사람 검토 일정 이전 knownAt에서 `AVAILABLE`이지만 사용 불가 | AC-07, 09, 12 |
| 운영 기동 거부 추가 2건 (`DemoFeatureProductionGuardTest` 4 → 6) + 필수 설정 갱신 (`RuntimeDatasourcePropertiesTest`) | 검증 설정 단독, 셋 모두, 꺼짐. 유효 기간 0s와 -1h 거부. 유효 기간과 정책 version이 production 필수 | AC-13 |
| schema 테스트 갱신 | migration 7개, 애플리케이션 테이블 33개, 보호 trigger 66개, schema version 7 | AC-11(d) |
| Python 계약 2건 추가 (`test_checklist_proposal_contracts.py` 3 → 5) | 셀러론 예시가 FIXTURE 출처이고 v1 공문 규칙과 1:1, 일정이 v1 시행일과 일치. 검증 결과 schema가 정답 파일을 통과하고 FAIL issue 없는 FAIL, WARN issue 없는 WARN, FAIL issue 있는 PASS를 거부 | AC-07, 14 |

### 기존 테스트 변경

- `ChecklistProposalIntegrationTest`: fixture family가 2개가 되어 적재 개수 기대값(version 1 → 2, item 2 → 4, schedule 1 → 2) 갱신. 예시 복사 helper는 "모든 파일이 바뀌어야 함"에서 "적어도 한 파일이 바뀌어야 함"으로 조정. 공문군 필터 추가 1곳. 생성 동작과 다른 기대값은 그대로.
- `InternalPolicyApplicableIntegrationTest`: 셀러론 사람 검토 일정 `checklist-schedule:1111…`이 fixture 일정 `checklist-schedule:ca9f…`를 잇도록(supersedes) 바꿔 revision chain이 하나가 되게 했다. 기존 14건 기대값은 바꾸지 않았다.
- `CoreApplicationIntegrationTest`, `PublicProductSchemaIntegrationTest`, `RuntimeDatasourcePropertiesTest`, `DemoFeatureProductionGuardTest`: schema version 7과 필수 설정 2개.

### 구현 중 고친 결함 (업무 로직 결함 아님)

1. V7의 CHECK 제약 이름이 PostgreSQL 자동 생성 이름(`automated_validation_issue_severity_check`)과 충돌해 migration 실패 → 제약 이름 변경.
2. commit 시점 trigger 함수를 SECURITY DEFINER(감사 소유자)로 만들어 issue 테이블 SELECT 권한이 없어 42501 → SECURITY INVOKER로 변경(저장하는 runtime/maintenance 역할이 SELECT 권한을 가짐).
3. 테스트 설계 오류 3건: 조건 없는 규칙 테스트의 공문 규칙 불일치, 셀러론 참조 insert 컬럼 순서, 적용 공문 조회 WARN 결과의 유효 기간(기준은 knownAt이 아니라 평가 시각) 기대값. 모두 기대값/입력을 고쳤다.
4. 사례 1의 INFO 개수: 첫 구현은 변경안당 INFO 1건이었으나 사전 AC-01이 "INFO 2건(규칙별)"이므로 검증기를 규칙별 INFO로 맞췄다(AC를 완화하지 않음).

## 한계

- 검증은 CLI 실행(ApplicationRunner)으로만 한다. HTTP 노출은 없다.
- 공개 근거 교차 검증은 공문 참조(`internal_notice_reference`)의 fact 단위로 한다. 참조에 rule_key가 없어 "교차 검증 대상 규칙"은 변경안 항목의 `field_key`와 참조 `fact_key` 일치로 판단한다.
- 참고용(INFORMATIONAL) 공개 참조는 현재 데이터에 없어 WARN 경로는 단위 테스트로만 확인했다.
- 유효 기간 판정의 기준은 평가 시각(`evaluatedAt`)이며 과거 조회(`knownAt`)에서는 결과의 가시성만 `knownAt`을 따른다.
- 사람 검토와 승인(TASK-007)이 없으므로 PASS/WARN 결과는 `PENDING_REVIEW`에서 멈춘다. 검증 통과는 사용 허용이 아니다.
- 인간 검수와 완료 판정은 미기록이다.
