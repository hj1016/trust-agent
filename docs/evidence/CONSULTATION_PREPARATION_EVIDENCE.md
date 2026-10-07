# AI 서비스 규칙 조립 상담 준비안·보류와 Core 기록 경로 검증 기록 (TASK-015)

## 구현 범위

FastAPI AI 서비스의 첫 연결 단계. 합성 신청 건 하나에 대해 AI 서비스가 **Core Tool API로 승인되고 지금 사용 가능한 checklist 항목과 근거만 읽어** 공문군별 READY/HOLD 섹션을 규칙으로 조립하고, 필수 공문군 기준으로 전체 READY/PARTIAL/HOLD를 정한 뒤, Tool이 아닌 **별도 기록 경로**로 Core에 남긴다. Core는 저장 직전에 매핑과 승인·근거 상태를 직접 다시 확인한다. LLM 생성은 없고, "AI 생성 완료"나 "LLM 안전성 검증 완료"를 뜻하지 않는다. ADR-012.

- 매핑·계약: `datasets/synthetic/work/consultation-family-mapping.json`(SYNTHETIC_WORK, v1, kb-seller-loan → 중도상환수수료·셀러론 둘 다 필수), `contracts/consultation-family-mapping.schema.json`, `contracts/consultation-preparation.schema.json`(AI 출력, 결정 필드 없음, `additionalProperties: false`), `contracts/consultation-preparation-record.schema.json`(Core 기록 요청. 근거 원문·메모·토큰 없음).
- Core(V10, 보호 목록 36 → 40표, trigger 72 → 80, migration 9 → 10): `consultation_family_mapping`(별도 적재 경로), `consultation_preparation`·`consultation_preparation_section`·`consultation_preparation_run`(기록 경로가 INSERT하는 세 표). 모두 append-only, runtime 역할은 SELECT·INSERT만. CHECK로 HOLD 섹션의 version ID·결정 ID·항목·근거 금지, `hold_claim_basis`(CORE_REPORTED/SERVICE_REPORTED) 구분.
- 실행 단위 추적: `consultation_preparation_run.section_evaluations`(jsonb)에 실행마다 섹션별 평가 시각·Tool 응답 해시·재확인 결과를 남긴다. 준비안·섹션 행은 첫 기록 그대로다.
- Core 기록 경로 `POST /api/v1/consultation-preparations`: 기록 토큰(`TRUST_AGENT_PREPARATION_RECORD_TOKEN`, Tool 토큰과 별개, 서로 바꿔 쓸 수 없음, production 필수, 두 토큰이 같은 값이면 모든 profile에서 기동 거부 `SERVICE_TOKEN_NOT_SEPARATED`). 공통 검사(허용 필드·형식·READY/HOLD 모양·ID 해시) → SERIALIZABLE 트랜잭션 하나에서 매핑 확인 M1~M5(활성 매핑 해시, 필수 섹션 유무, `required` 값, 매핑 밖 섹션, 전체 상태 계산. 필수 공문군 없으면 READY 불가 `NO_REQUIRED_FAMILY`) + READY 섹션 재확인 C1~C5(현재 시각의 적용 공문 조회로 사용 가능 여부·선택 공문·승인 version·결정 ID·항목 순서·근거 해시 대조) + HOLD 섹션 공통 검사와 재조회 값 저장 → 저장. 40001은 재확인부터 1회 재시도. 요청마다 실행 기록 1건(REQUIRES_NEW), 같은 `run_id` 재전송 409. 같은 ID 재요청도 재확인을 거친 뒤 200 ALREADY_RECORDED. **기록은 사용 허가가 아니다.**
- AI 서비스 HTTP 진입점(제안 9 수정 채택, AC-21): 기록 성공 200, Core 422 → 422, Core 409 → 409, Core 401·토큰 미설정 → 503, Core 400(계약 위반) → 500, 연결 실패·Core 5xx → 502, 시간 초과 → 504. 실패 본문은 `recorded=false`·원인 코드·사용 금지 안내 그대로, 토큰·내부 오류 상세 비노출. CLI 종료 코드 0/3과 일관.
- AI 서비스 `apps/ai-service/ai_service/`: `config.py`(환경변수만, DB 설정 항목 없음), `core_client.py`(표준 라이브러리 전송, Tool 토큰·기록 토큰 분리), `assembler.py`(조립·상태·기록), `messages.py`(고정 문구 표, `messages_hash`), `ids.py`(준비안 ID 해시·실행 ID), `cli.py`(종료 코드 0/2/3/4), `app.py`(FastAPI, 로컬 전용). 의존성 `requirements-ai.txt`(fastapi·uvicorn·httpx, DB 드라이버 없음).
- 연결 검증: Gradle 작업 `prepareAiServiceEnv`(venv + `requirements-ai.txt`)와 Java 통합 테스트 `AiServicePreparationIntegrationTest`가 Core를 띄우고 Python CLI를 실제로 실행. 로컬 기본은 명시적 skip, `-PaiServiceIntegration=true` 또는 `TRUST_AGENT_REQUIRE_AI_INTEGRATION=1`이면 실행하며 환경 누락은 `AI_INTEGRATION_ENV_MISSING` 실패. CI `gradle-tests` job이 Python 3.11을 설치하고 이 값으로 실행한다(필수 체크). `python-contracts` job은 `requirements-ai.txt`도 설치해 `tests/ai_service`를 함께 돌린다.
- 안전성 참조 자료 v2 `datasets/synthetic/search-goldenset/prepayment-fee-safety-v2.json`(책임을 `structural: TASK-015`, `question_based: TASK-019`로 구분, 질문·참조 ID는 v1과 같음), schema가 v1 문자열·v2 객체를 모두 허용, 계약 테스트 추가. 검색 골든셋은 v1 유지.

## 테스트 evidence

검증 대상 revision: 구현·자료·테스트 커밋 `87b553b`(브랜치 `feat/task-015-consultation-preparation`, base `006c880`). 문서 커밋과 CI 수정 커밋 `63fee24`(워크플로·Gradle 로그 설정만)는 그 뒤에 따로 올렸고 검증 대상 코드는 바꾸지 않았다. 4·5차 보완(승인 전 HOLD 테스트, 토큰 동일값 기동 거부, HTTP 기록 실패 상태 구분)의 검증 대상 커밋은 `2e330d2`이며 이 문서의 수치와 표본은 그 커밋으로 다시 실행한 것이다. 6차 보완(READY '기준 자료 준비 완료' 표현과 직원 확인 안내)의 검증 대상 커밋은 `f6ca899`이며 수치와 표본은 그 커밋으로 다시 실행한 것이다. 고정 문구 표가 바뀌어 준비안 ID와 `messages_hash`가 이전 표본과 다르다. CI 결과는 아래 "CI 결과" 절과 PR #40 본문에 있다. 환경: 로컬 macOS arm64, Java 21, Docker, PostgreSQL 18.6 Testcontainers(digest 고정), Python 3.11.

```text
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew clean test bootJar --offline --no-daemon -PaiServiceIntegration=true
185 tests completed (기존 158 + 신규 27: 토큰 분리 4 + Core 통합 13 + 전체 READY 별도 환경 3 + 해시 단위 2 + 연결 검증 5), 통과 185, failures 0, errors 0, skipped 0. BUILD SUCCESSFUL (bootJar 포함). 보존: docs/evidence/task-015/test-results-full-with-ai-integration.md(저장소), gradle-full-with-ai-integration.log(로컬 보관)

# 로컬 기본(속성 없음, 전체): CLI를 실행하는 테스트가 명시적 skip으로 보고서에 남음
./gradlew clean test --offline --no-daemon
전체 185건 중 통과 179, 실패 0, skip 6(연결 검증 5건과 전체 READY CLI 1건, 명시적 skip 목록은 docs/evidence/task-015/test-results-local-default.md), BUILD SUCCESSFUL

# 의도적 환경 누락(필수 조건은 켜고 venv 준비는 끔): 건너뜀이 아니라 실패
TRUST_AGENT_REQUIRE_AI_INTEGRATION=1 ./gradlew clean :apps:core-service:test --tests '*AiServicePreparationIntegrationTest' -PaiServiceIntegration=false
AiServicePreparationIntegrationTest > initializationError FAILED (AI_INTEGRATION_ENV_MISSING: python venv ...), BUILD FAILED

python3 -m unittest discover -s tests -t .
Ran 106 tests (기존 75 + 신규 31: tests/ai_service 30 + 골든셋 계약 1), 통과 104, 실패 0, 건너뜀 2 (기존 사유: "비공개 snapshot artifact가 제공되지 않았습니다.")
```

기존 테스트 변경은 기대값 갱신뿐이다(TASK-015 문서 보완 5절 대조표): schema version 9 → 10(3곳), 보호 표 36 → 40·trigger 72 → 80, production 필수 설정에 기록 토큰 추가(2 테스트). 기존 승인·일정·변경안·검증·Tool 코드는 바꾸지 않았다(`apps/core-service/src/main` 변경 파일: `ProductionRequiredSettingsConfiguration.java`, `application.yml`, `application-prod.yml`과 새 `preparation` 패키지·V10뿐).

### Core 통합 `ConsultationPreparationIntegrationTest` (13건)

| 테스트 | 보장하는 것 | AC |
|---|---|---|
| partialPreparationIsRecordedWithRecheckValuesAndRunRecord | Tool 1·2 응답으로 만든 PARTIAL 준비안 201, 세 표 행(상태·완료 여부·섹션 수·필수 보류 수, READY 섹션의 version·규칙 version 3개·근거 해시, HOLD 섹션의 `hold_claim_basis`·`recheck_usable=false`·`recheck_reasons`에 HUMAN_REVIEW_PENDING), 실행 기록 RECORDED, 원문 열 없음 | 06, 18 |
| sameContentIsRecordedOnceAndRerunStillRechecksCurrentState | 평가 시각·Tool 응답 해시를 바꿔도 같은 ID로 200 ALREADY_RECORDED(행 그대로, 새 run, 재실행 값은 실행 기록 `section_evaluations`에만), 승인 전 시각으로 돌린 재요청은 기존 행이 있어도 422 PREPARATION_NOT_USABLE(재확인 생략 없음) | 08, 13 |
| tamperedReadyClaimsAreRejectedWithoutRows | 결정 ID 변조·항목 순서 변경·근거 해시 변조·선택 공문 변경·항목 누락 → 409 PREPARATION_STALE, FIXTURE 기간(2026-09-30) → 422 PREPARATION_NOT_USABLE, 행 없음, 실행 기록 REJECTED | 07 |
| mappingAndStatusClaimsAreRejected | 필수 섹션 누락 422, `required` 변경 422, 섹션과 다른 전체 READY 주장 422, `preparation_complete` 거짓 주장 422, 다른 매핑 해시 409, 매핑 밖 섹션 422 | 16 |
| holdSectionsAreCheckedAndStoredWithClaimBasis | 항목이 있는 HOLD 400, 종류와 맞지 않는 사유 코드 400, 서비스 보고 보류(CORE_TIMEOUT)는 전체 HOLD로 201 저장되고 Core가 지금 본 값(`recheck_usable=true`, 사유 없음)이 함께 저장됨 | 03, 18 |
| idHashRunIdAndSchemaRulesAreEnforced | ID 해시 불일치 400, 같은 `run_id` 재전송 409(실행 기록 추가 없음), 모르는 필드 400, `run_id` 형식 400 | 08, 09, 19 |
| recordAndToolTokensAreNotInterchangeable | 토큰 없음·Tool 토큰으로 기록 401, 기록 토큰으로 Tool 401, 실행 기록 없음 | 09 |
| concurrentSameIdRecordsOnce | 같은 ID 동시 2회 → 201+200, 준비안 행 1, 실행 기록 2 | 08 |
| businessTablesAreUntouchedAndRecordTablesAreAppendOnly | 승인·일정·변경안·검증·공문 사건·매핑 표 행 수 불변, UPDATE/DELETE 42501, runtime 권한 INSERT만, 보호 목록 4표, HOLD 섹션 version ID CHECK 23514 | 10 |
| duplicateKeyConflictPathRechecksBeforeReturningExistingRecord | 저장소 대역으로 실제 PK 충돌(23505)을 재현: 충돌 뒤 새 트랜잭션에서 매핑 확인·READY 재확인을 다시 한 뒤 ALREADY_RECORDED(조회 각 2회, 재확인 결과 실행 기록에 저장), 그 사이 매핑이 바뀌면 409 MAPPING_MISMATCH, 사용 불가면 422 PREPARATION_NOT_USABLE | 08 |
| serializationFailureIsRetriedOnceAndSecondFailureIsRecordedAsFailed | commit 단계에 40001 주입: 1회 실패 뒤 재확인부터 재시도해 RECORDED, 2회 실패는 500 SERIALIZATION_FAILED·행 없음·FAILED 실행 기록 1건 | 19 |
| withdrawalKnownAfterRecordingRejectsTheSameClaim | 철회 사건이 알려진 뒤 같은 READY 주장은 422, 행 없음 | 07, 08 |
| mappingWithoutRequiredFamilyNeverAllowsReady | 필수 없는 매핑(직접 삽입)에서 READY 주장 422 NO_REQUIRED_FAMILY | 17 |

### 전체 READY 별도 환경 `ConsultationPreparationReadyIntegrationTest` (3건, 셀러론까지 승인)

| 테스트 | 보장하는 것 | AC |
|---|---|---|
| fullyReadyPreparationIsRecorded | 두 필수 공문군 모두 READY인 기록 201, status READY·complete true·필수 보류 0·섹션 2행 READY·셀러론 결정 ID 일치·재확인 usable 2건 | 06 |
| readyClaimMustMatchComputedStatus | 같은 내용을 PARTIAL로 낮춰 주장하면 422 PREPARATION_STATUS_INVALID | 16 |
| prepareCommandProducesFullyReadyPreparation (연결 검증 조건에서만) | CLI가 READY·complete true·"기준 자료 준비 완료"·"상담·대출 결정이 끝났다는 뜻이 아닙니다"·`staff_check_notice`에 직원 확인 안내·remaining_checks 0·record RECORDED/recorded true를 내고 DB에 READY 행 | 01, 06, 13 |

### 토큰 분리 `ServiceTokenSeparationTest` (4건, DB 없음, AC-20)

읽기 토큰과 기록 토큰이 같은 값이면 `SERVICE_TOKEN_NOT_SEPARATED`로 기동 거부(오류에 토큰 값 없음), 다른 값·빈 값은 통과, Spring 컨텍스트에서 같은 값 refresh 실패·다른 값 정상.

### 해시 대상 단위 `ConsultationPreparationHashTest` (2건, DB 없음)

run_id·consultation_id·evaluated_at·tool_response_hash·preparation_id를 바꿔도 같은 ID, checklist version·결정·항목 순서·항목 누락·근거 해시·선택 공문·섹션 상태·보류 종류·사유·필수 여부·매핑·전체 상태·완료 여부·업무일·신청·source_hash·문구 표·조립기 버전을 바꾸면 다른 ID.

### 연결 검증 `AiServicePreparationIntegrationTest` (5건, Core 기동 + Python CLI 실제 실행)

| 테스트 | 보장하는 것 | AC |
|---|---|---|
| prepareCommandAssemblesPartialPreparationAndRecordsIt | 명령 한 번으로 PARTIAL 준비안(중도상환수수료 READY 항목 3개와 "0.8퍼센트" 근거·구조화 값, 셀러론 HOLD CORE_DECISION/CORE_REPORTED, HUMAN_REVIEW_PENDING 포함, 항목 없음), "끝나지 않았습니다" headline, `remaining_checks` 1건, Core 기록 RECORDED와 ID 일치, DB 행·실행 기록·근거 Tool 감사 3건, 출력에 토큰 없음 | 01, 02, 13 |
| rerunRechecksCoreAndRecordsOnlyOnce | 시계를 60초 뒤로 옮긴 재실행은 Tool 1을 다시 호출하고 같은 ID로 ALREADY_RECORDED(recorded true), 행 1·실행 기록 2, 실행별 Tool 응답 해시 2종이 실행 기록에 남고 첫 섹션 행은 그대로 | 08, 13 |
| stateChangeProducesNewHoldPreparation | 승인 전 시각(04:00Z)에 평가 당일 업무일(2026-10-05)로 실행하면 새 ID의 HOLD 준비안이 기록됨. 두 섹션 모두 `HUMAN_REVIEW_PENDING` 있음·`FUTURE_BUSINESS_DATE` 없음·항목 0·`hold_message` "검토 대기" | 02, 13 |
| withdrawalAfterRecordingProducesNewHoldPreparation | 철회 뒤 재실행은 새 ID·HOLD·EFFECTIVE_NOTICE_WITHDRAWN·항목 없음으로 기록됨 | 13 |
| wrongRecordTokenLeavesPreparationUnrecorded | 기록 토큰이 틀리면 준비안은 나오되 REJECTED/UNAUTHENTICATED, `recorded=false`, 사용 금지 안내, 종료 코드 3 | 05, 14 |

### Python (`tests/ai_service` 30건 + 계약 테스트 1건 추가. 기록 결과 테스트는 `record.recorded`·`core_http_status`·원인별 안내를, HTTP 테스트는 기록 실패 상태 200/422/409/503/500/502/504와 헤더·본문 일관을 확인)

가짜 Core 전송으로 READY 조립, CORE_DECISION 보류, 404 POLICY_FAMILY_NOT_FOUND는 Core 판정, UNVERIFIED 보류(401·403·5xx·시간 초과·schema 위반), 근거 1건 실패 시 섹션 전체 HOLD, PARTIAL/HOLD 상태와 headline·`remaining_checks`, 선택 공문군 HOLD는 상태 불변, 필수 없는 매핑은 READY 불가, 매핑 없는 상품·없는 신청은 입력 오류, 준비안 ID 해시 대상(run_id·consultation_id·evaluated_at·tool_response_hash 제외, 섹션 상태는 반영), 재실행 시 Tool 재호출, 기록 결과 → 상태·`usage_notice`, 기록 토큰 없으면 기록 시도 없음, Tool/기록 토큰 분리, 고정 문구 표 금지 표현 없음, 사유 표가 Core 코드 목록을 모두 덮음, 서비스 사유 코드가 기록 계약 허용 목록과 같음, 계약에 결정 필드 없음, 설정 누락 시작 거부(토큰 값 노출 없음), DB 설정·드라이버 없음, CLI 종료 코드 0/2/3/4, HTTP 진입점 400/503.

## 보존한 실행 기록 (`docs/evidence/task-015/`)

Gradle 결과 파일은 실행마다 덮어써지므로 실행별로 로그와 JUnit XML 요약을 따로 남겼다. `RUN_SUMMARY.txt`가 네 실행의 합계를 한 번에 보여 준다. **원본 `.log` 파일은 저장소 규칙(`.gitignore`의 `*.log`)으로 Git에 올리지 않는 로컬 보관 자료이며**, 저장소에서 읽을 수 있는 근거는 `.md` 결과 요약, `RUN_SUMMARY.txt`, CLI 출력 표본과 PR 본문의 CI 실행 링크다. 검증 대상 코드는 커밋 `87b553b`이다.

| 실행 | 로그 | 결과 요약 | 비고 |
|---|---|---|---|
| 연결 검증 포함 전체(`-PaiServiceIntegration=true`) | `gradle-full-with-ai-integration.log`(로컬 보관) | `test-results-full-with-ai-integration.md` | CLI 출력 표본 `cli-output-partial-sample.json`(PARTIAL), `cli-output-ready-sample.json`(READY: 기준 자료 준비 완료, 직원 확인 안내), `cli-output-hold-before-approval-sample.json`(HOLD: 평가 시각 2026-10-05T04:00Z·업무일 2026-10-05, 두 섹션 사유 `APPROVED_CHECKLIST_NOTICE_MISMATCH`·`HUMAN_REVIEW_PENDING`, `FUTURE_BUSINESS_DATE` 없음), `cli-output-hold-after-withdrawal-sample.json`(HOLD: 철회 뒤 재실행, `EFFECTIVE_NOTICE_WITHDRAWN`), `cli-output-rejected-sample.json`·`cli-stderr-rejected-sample.txt`(기록 거부, 종료 3). 합성 자료이며 토큰 값은 없다. HOLD 표본의 조건 구분은 TASK-015 문서 3차 보완 1절 |
| 로컬 기본(속성 없음) | `gradle-local-default.log`(로컬 보관) | `test-results-local-default.md` | CLI 실행 테스트 6건이 명시적 skip으로 나열됨 |
| 의도적 환경 누락 | `gradle-env-missing-failure.log`(로컬 보관) | `test-results-env-missing.md` | `AI_INTEGRATION_ENV_MISSING` 실패 1건 |
| Python | `python-unittest.log`(로컬 보관) | `RUN_SUMMARY.txt` 4절 | 건너뜀 2건은 기존 비공개 snapshot 사유 |

## CI 결과 (PR #40)

- 1차 실행(https://github.com/hj1016/trust-agent/actions/runs/37659077359): `Gradle tests` 성공, `Python contracts` **실패**. 원인은 CI가 `unittest discover -s tests`를 top-level 지정 없이 실행해 `tests/ai_service`가 패키지 `ai_service`를 가리고 `ai_service.config`를 찾지 못한 것(import 오류 3건). 로컬에서 같은 명령으로 재현했다.
- 수정 커밋 `63fee24`(`.github/workflows/ci.yml`, `apps/core-service/build.gradle`만): discover 명령을 README와 같은 `-s tests -t .`로 고치고, Gradle 로그에 preparation 패키지 테스트의 통과·skip·실패와 전체 합계를 출력하게 했다. 테스트나 완료 기준은 바꾸지 않았다.
- 2차 실행(https://github.com/hj1016/trust-agent/actions/runs/37659799630): 두 job 모두 성공. [Gradle tests](https://github.com/hj1016/trust-agent/actions/runs/37659799630/job/112924094547) 로그에 `TEST SUMMARY: 181 tests, 181 passed, 0 failed, 0 skipped`와 연결 검증 `AiServicePreparationIntegrationTest` 5건·전체 READY CLI 1건의 `TEST SUCCESS`가 있고 `TEST SKIPPED`는 0건이다(필수 CI에서 Core와 Python CLI 연결 테스트가 실제 실행됨). [Python contracts](https://github.com/hj1016/trust-agent/actions/runs/37659799630/job/112924094871) 로그는 `Ran 106 tests`, `OK (skipped=2)`이며 skip 2건은 기존 비공개 snapshot artifact 사유로 TASK-015와 무관하다.

## 확인된 사실과 한계

- 셀러론(필수, 미승인) 섹션의 Core 사유는 `HUMAN_REVIEW_PENDING`과 함께 예시 checklist 일정 불일치 `APPROVED_CHECKLIST_NOTICE_MISMATCH`도 온다(TASK-014 고정 조건의 실제 Core 응답). 둘 다 사용 불가 사유로 그대로 전달·저장된다.
- 전체 상태 **READY**인 준비안은 별도 환경(셀러론까지 승인, 30일 공개 근거 정책에서 PASS)에서 Core 기록과 CLI 모두 확인했다. 기본 고정 조건(셀러론 미승인)의 PARTIAL·HOLD 사례는 그대로다.
- 같은 ID 다른 내용(409 PREPARATION_CONFLICT)은 ID가 내용 해시라 정상 경로에서는 400 PREPARATION_ID_MISMATCH로 먼저 걸린다. 저장되는 content_hash가 ID와 같은 함수의 값이라 409는 해시 함수 변경이나 충돌 없이는 만들 수 없는 방어선이며 테스트로 만들지 않았다.
- 직렬화 실패 재시도는 실제 동시 철회가 아니라 commit 단계에 SQLSTATE 40001을 주입해 검증했다. 실제 충돌 타이밍은 비결정적이라 테스트로 만들지 않았다. PK 충돌(23505) 해결 경로는 저장소 대역으로 실제 23505를 재현해 충돌 뒤 재확인이 다시 실행됨을 검증했다.
- 필수 CI에서의 실제 실행은 PR #40의 2차 CI 로그로 확인했다(위 "CI 결과"). 로컬에서는 실행·skip·환경 누락 실패 세 경우를 확인했다.
- 안전성 검증은 규칙 조립 출력의 **구조 검증**(SAFE-A·B·D·E·F)이다. 질문 기반 사례(S10·E22·E23)와 LLM 출력 검증은 TASK-019이며 이 기록은 LLM 안전성 검증 완료가 아니다.
- 행원 메모 입력, 검색, 화면, 사용자별 인증·권한은 범위 밖이다. 매핑은 합성 시나리오의 사용자 승인 설정 1건이다.
