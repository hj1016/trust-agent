# Core Tool API(AI 서비스용 읽기 전용 조회 경계) 검증 기록 (TASK-008)

## 구현 범위

FastAPI AI 서비스가 업무 DB를 건드리지 않고 Core의 읽기 전용 Tool로 "지금 이 상담에 적용되는 승인 checklist와 근거"를 읽는 경계. **사용 허용 여부는 Core가 결정하고**, AI가 응답을 어떻게 해석하더라도 서버의 차단을 바꾸거나 우회할 수 없다. ADR-011.

- 진입점 하나 `POST /api/v1/tools/{toolName}`, allowlist 코드 상수 2개(`applicable_checklist`, `rule_evidence`). 쓰기 Tool 없음.
- `applicable_checklist`: 공문군 ID, 업무일(없으면 오늘), 상담 ID(추적용) → 기존 적용 공문 조회 결과를 줄여 전달. `usable=false`면 사유만 주고 `approvedChecklist`는 null(항목과 근거 ID 없음). 공문 본문, 규칙 원문, 검수자 ID, 변경안·검증 ID, 정책 입력 플래그는 응답에 없다.
- `rule_evidence`: 공문군 ID, 규칙 version ID → 서버가 오늘 기준으로 그 공문군의 checklist가 사용 가능하고 그 규칙이 승인 checklist 항목의 근거인지 다시 확인한 뒤에만 원문 문장과 위치를 준다. 아니면 403 `EVIDENCE_NOT_AVAILABLE`.
- 입력은 허용 필드만 받는다. `knownAt`이나 모르는 필드는 400. 조회는 항상 현재 시각 기준.
- 서비스 인증(test/demo): `Authorization: Bearer <토큰>`을 환경변수 `TRUST_AGENT_TOOL_SERVICE_TOKEN` 값과 상수 시간 비교. 토큰 설정이 비어 있으면 모든 호출 401(누락은 허용 아님). production은 설정 없으면 기동 거부. 실제 자격증명은 저장소·예시 설정·테스트 코드·로그에 없고 테스트는 실행 중 만든 임시 토큰을 쓴다. 사용자별 인증·권한은 미구현.
- 감사: `tool_call_audit`(V9, append-only, 보호 목록 등록: 테이블 35 → 36, trigger 70 → 72, migration 8 → 9). 서비스 ID, tool, 공문군, 업무일, 상담 ID, 결과 코드, 사용 가능 여부, 차단 사유, 추적 ID, 시각만 저장. 인증 거부도 기록. 저장 실패 시 500 `AUDIT_WRITE_FAILED`이고 데이터 없음.

## 테스트 evidence

검증 대상 revision: 브랜치 `feat/core-tool-api` HEAD(PR 본문의 commit). 환경: 로컬 macOS arm64, Java 21(openjdk 21.0.8), Docker, PostgreSQL 18.6 Testcontainers, Python 3.11.8.

```text
./gradlew clean test bootJar --offline --no-daemon
153 tests completed (기존 144 + 신규 9), failures 0, errors 0, skipped 0

# 비공개 artifact를 제공한 로컬
TRUSTAGENT_PRIVATE_ARTIFACT_ROOT=<경로> TRUSTAGENT_REQUIRE_PRIVATE_SNAPSHOTS=1 python3 -m unittest discover -s tests -t .
Ran 65 tests (기존 63 + 신규 2), 통과 65, 실패 0, 건너뜀 0

# 공개 CI 조건(비공개 artifact 없음)
python3 -m unittest discover -s tests -t .
Ran 65 tests, 통과 63, 실패 0, 건너뜀 2 (사유: "비공개 snapshot artifact가 제공되지 않았습니다.")
```

Java 신규 9 = Tool 통합 7(`ToolApiIntegrationTest`) + 토큰 미설정 1(`ToolApiMissingTokenIntegrationTest`) + 운영 기동 거부 1(`DemoFeatureProductionGuardTest` 7 → 8).

| 테스트 | 보장 | AC |
|---|---|---|
| `usableChecklistIsReturnedWithItemsAndMatchesCoreDecision` | 승인된 중도상환수수료 공문군 2026-10-01 조회 `usable=true`, 차단 사유 없음, 항목 3개, 출처 HUMAN_REVIEW, 결정 ID. 응답이 정답 파일 `contracts/fixtures/tool-applicable-checklist.expected.json`과 일치. 본문·원문·검수자·변경안·검증·정책 플래그·knownAt·rules 없음. `usable`이 Core 조회 API의 `internalChecklistUseAllowed`와 같고 상담 ID 유무에 따라 응답이 같음 | AC-03, 04, 08 |
| `ruleEvidenceIsServedOnlyForRulesOfTheUsableApprovedChecklist` | 승인 항목의 규칙 ID로는 원문 문장과 위치(`/rules/0`) 제공. 다른 공문군(셀러론) 규칙 ID를 끼워 넣기, 미승인 공문군의 자기 규칙, 없는 규칙 ID는 모두 403이고 감사에 `EVIDENCE_NOT_AVAILABLE` 3건 | AC-04, 06 |
| `unusableStatesReturnReasonsOnlyWithoutItemsOrRuleIds` | 셀러론(검토 대기) `usable=false` + `HUMAN_REVIEW_PENDING`, 테스트용 기간 `FIXTURE_CHECKLIST_NOT_APPROVED`, 미래 업무일 `FUTURE_BUSINESS_DATE`. 모두 `approvedChecklist=null`이고 응답에 규칙 ID 문자열 없음 | AC-05, 07 |
| `rejectionAndWithdrawalAfterAQueryBlockItemsAndEvidenceAgain` | 조회 뒤 셀러론 변경안이 반려되면(2026-10-06) `usable=false` + `PROPOSAL_REJECTED`, 항목·근거 ID 없음. 중도상환수수료 v2가 철회되면(2026-10-07) `usable=false` + `EFFECTIVE_NOTICE_WITHDRAWN`이고 조금 전까지 유효했던 규칙 ID로 근거를 요청해도 403. 철회를 알기 전 평가 시각으로 돌아오면 같은 ID의 근거가 다시 제공됨(항상 현재 시각 기준) | AC-05, 06 |
| `unauthenticatedAndUnknownToolCallsAreRefusedAndAudited` | 토큰 없음·불일치 401과 감사 2행(`UNAUTHENTICATED`), `approve_checklist` 404와 감사, allowlist 상수 2개, `/api/v1/tools` 아래 라우트는 POST `{toolName}` 하나 | AC-01, 02, 09 |
| `pastKnownAtUnknownFieldsAndInstructionLikeValuesAreHandledAsSchemaAndData` | `knownAt`, 모르는 필드, 필수 필드 누락은 400. 없는 공문군 404. 지시문처럼 보이는 상담 ID는 데이터로만 취급되어 응답이 같고 감사에만 남음 | AC-07, 08 |
| `auditRowsNeverContainBodyEvidenceOrTokenAndAuditFailureFailsTheCall` | 감사 행에 서비스 ID와 결과 코드만 있고 토큰·원문·규칙 ID 없음. 감사 저장 실패 유도 시 500 `AUDIT_WRITE_FAILED`, 응답에 checklist 없음 | AC-09 |
| `everyToolCallIsRefusedWhenNoTokenIsConfigured` | 토큰 설정이 빈 환경에서는 토큰 없음·빈 토큰·임의 토큰 모두 401 | AC-01 |
| `toolServiceTokenIsRequiredInProduction` + 필수 설정 갱신 | production은 `TRUST_AGENT_TOOL_SERVICE_TOKEN` 없으면 기동 거부 | AC-10 |
| schema 테스트 갱신 | migration 9, 테이블 36, trigger 72, schema version 9 | AC-11 |
| Python 계약 2건 | Tool 응답 정답 파일이 schema를 통과하고 금지 필드 없음, `usable=false`에 항목이 있으면 거부, `knownAt` 거부, 근거 응답에 본문 필드 거부 | AC-03, 05 |

비밀 점검: 토큰 값은 환경변수 참조(`${TRUST_AGENT_TOOL_SERVICE_TOKEN:}`)만 저장소에 있고, 테스트의 임시 값은 `UUID`로 실행 때 만든다(운영 설정 테스트의 `temporary-token-for-test` 문자열은 실제 자격증명이 아니다). diff에 비밀번호, 개인키, 로컬 경로 없음.

### 실제 응답 사례 (테스트 실행 출력)

**사용 가능한 checklist 조회** (`applicable_checklist`, 공문군 중도상환수수료, 업무일 2026-10-01, 평가 시각 2026-10-05T05:00:00Z): `usable=true`, `blockingReasons=[]`, `selectedNotice=SIN-PREPAYMENT-FEE-V2`, `approvedChecklist.origin=HUMAN_REVIEW`, `decisionId=review-decision:3333…`, 항목 3개(`CHECK_PREPAYMENT_FEE_RATE` 0.8 PERCENT 시행일 2026-10-01, `CHECK_NOTICE_SOURCE`, `CHECK_CUSTOMER_CONTRACT_DATE` true), 각 항목의 `sourceRuleVersionId`. 정답 파일과 동일.

**미승인 자료 차단** (셀러론, 변경안과 PASS 검증은 있으나 사람 승인 없음): `usable=false`, `blockingReasons=["HUMAN_REVIEW_PENDING"]`, `approvedChecklist=null`. 응답 어디에도 규칙 ID가 없다.

**다른 공문군의 근거 접근 차단** (`rule_evidence`, 공문군 중도상환수수료에 셀러론 규칙 `CHECK_CORPORATE_LIMIT_SOURCE`의 version ID): 403, `code=EVIDENCE_NOT_AVAILABLE`, 응답에 원문 없음. 같은 규칙 ID를 셀러론 공문군으로 요청해도 승인 전이라 403.

**인증 없음**: 401, `code=UNAUTHENTICATED`, 감사에 `service_id=UNAUTHENTICATED`.

**조회 뒤 철회**: 2026-10-07 평가 시각에 중도상환수수료 조회 → `usable=false`, `blockingReasons=["EFFECTIVE_NOTICE_WITHDRAWN"]`, `selectedNotice=null`, `approvedChecklist=null`. 직전에 200으로 받았던 규칙 `…0827aed5…`로 근거 요청 → 403 `EVIDENCE_NOT_AVAILABLE`.

**조회 뒤 반려**: 2026-10-06 평가 시각에 셀러론 조회 → `usable=false`, `blockingReasons=["APPROVED_CHECKLIST_NOTICE_MISMATCH","PROPOSAL_REJECTED"]`, `approvedChecklist=null`.

**감사 기록 예시**(토큰·원문 없음): `{"service_id":"ai-service","tool_name":"rule_evidence","family_id":"SIN-SELLER-CHECKLIST","business_date":"2026-10-05","consultation_id":null,"outcome":"EVIDENCE_NOT_AVAILABLE","usable":false,"blocking_reasons":["APPROVED_CHECKLIST_NOTICE_MISMATCH","HUMAN_REVIEW_PENDING"],"trace_id":"aaef39cd…","called_at":"2026-10-05T14:00:00+09:00"}`. 인증 거부는 `service_id=UNAUTHENTICATED`, `outcome=UNAUTHENTICATED`.

## 한계

- 서비스 인증은 test/demo 수준의 공유 토큰 1개다. 사용자별 인증·권한, 조직 인증 연동, 토큰 교체는 미구현(후속 ADR).
- FastAPI AI 서비스 자체는 이번 범위에 없다. Core 쪽 endpoint, 계약, 테스트만 있다.
- 근거 Tool은 "오늘 업무일" 기준으로만 확인한다. 다른 업무일의 근거는 제공하지 않는다.
- 감사 로그는 결과 코드와 사유만 남긴다. 요청 본문 자체는 저장하지 않으므로 어떤 값으로 호출했는지는 상담 ID와 공문군·업무일로만 추적된다.
- 인간 검수와 완료 판정은 미기록이다.
