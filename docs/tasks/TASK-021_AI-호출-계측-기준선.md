# TASK-021 AI 호출 계측 기준선: Tool 1, Tool 2, 기록 호출과 전체 실행 시간의 p50/p95

- 상태: 구현 완료·로컬 측정 완료, 검증·검수 대기 (범위 승인: 사용자. 커밋·push·PR은 별도 지시)
- 담당자 / 인간 결정자: AI 구현·측정 / 사용자 범위 승인·검수
- 요구사항 출처: 사용자 지시(LLM 및 Tool 호출 횟수, 전체 응답 p50/p95, 단계별 호출 지연을 측정하되 기존 API 계약과 DB 스키마는 바꾸지 않고, 실제 병목을 측정하기 전에는 일괄 조회나 병렬화를 구현하지 않는다. 측정 결과를 만들어내지 않는다), TASK-014(질의당 Core 재확인 호출 수 지표), TASK-015(연결 검증 하네스)
- 관련 Task / ADR: TASK-015(완료, 하네스와 시나리오 재사용), TASK-016(평가 결과 파일의 지연 열이 이 계측 형식을 재사용), TASK-019(LLM 추가 여부의 비교 기준선)

## Goal / 관련 요구사항

- Goal: 현재 규칙 조립 흐름(LLM 0회)의 Core 호출 횟수와 단계별·전체 소요 시간을 실측해 기준선으로 남긴다. 이후 일괄 조회, 병렬화, LLM 추가는 이 기준선과 비교해 판단한다.
- 사용자: 설계 판단자(사용자), 후속 Task 구현자
- 사전조건: Docker, Java 21, Python 3.11. 측정 runner는 TASK-015 연결 검증과 같은 환경(Testcontainers PostgreSQL, 고정 시계, `PreparationScenario` 상태)을 쓴다.
- 입력: 합성 신청 `SW-APPLICATION-001`, 업무일 두 가지(2026-10-06 PARTIAL, 2026-09-30 전체 HOLD), 시나리오당 N회(기본 30)
- 업무규칙: (1) 준비안 출력·기록 계약(`consultation-preparation*.schema.json`)과 DB 스키마는 바꾸지 않는다. (2) 계측은 전송 계층을 감싸기만 하며 호출 순서·횟수·결과를 바꾸지 않는다. (3) 계측 파일에 토큰, 근거 원문, 항목 문장을 넣지 않는다. (4) 일괄 조회·병렬화·캐시·재시도를 추가하지 않는다. (5) 수치는 실제 실행 파일에서만 온다.
- Out of Scope: HTTP 진입점(`app.py`) 계측, Core 쪽 계측 코드, 성능 개선 구현, 토큰·비용(LLM이 없어 0)

## 요구사항과 범위

업무 문제: 호출 횟수와 지연을 기록하는 곳이 없어 오케스트레이션 변경(일괄 조회, 병렬화, 경량 LLM)을 근거 없이 판단하게 된다.
포함 범위: AI 서비스 `--metrics-file` 옵션과 계측 모듈, 계측 계약 schema(신규), 요약 스크립트, Java 측정 runner(CI 미포함), evidence.
제외 범위: 위 Out of Scope.
기존 자산: `CoreClient.call_count`(출력되지 않던 값)는 그대로 두고, `HttpTransport` 주입 지점을 재사용한다. 하네스는 `AiServicePreparationIntegrationTest`와 `PreparationScenario`를 재사용한다.

## Acceptance Criteria (구현 전 고정)

결정자 / 판단 근거 / 검토 대상: 사용자 / 범위 승인 지시 / 이 브랜치

| ID | 입력/상황 | 기대 결과 | 검증 방법 | 결과/evidence |
|---|---|---|---|---|
| AC-01 | `--metrics-file` 없이 실행 | stdout, 종료 코드, 기록 본문이 기존과 같다 | 기존 Python·Java 테스트 전부 통과, 같은 입력으로 옵션 유무 출력 비교 테스트 | `test_output_and_exit_code_identical_with_and_without_metrics` 통과, Python 115건 통과 |
| AC-02 | PARTIAL 시나리오(가짜 전송) | Tool 1 호출 수 = 매핑 공문군 수(2), Tool 2 = READY 섹션 항목 수 합(3), 기록 1, 순서 기록 | 단위 테스트 | `test_metrics_counts_match_tool_calls_and_follow_contract` 통과 |
| AC-03 | 계측 파일 | 토큰, 근거 원문, 항목 문장, 규칙 version ID 없음 | 단위 테스트 | `test_metrics_file_contains_no_tokens_text_or_evidence` 통과 |
| AC-04 | 시간 초과·연결 실패·401 | 소요와 결과 종류(TIMEOUT 등)가 남고 준비안 결과는 계측 없을 때와 같다 | 단위 테스트 | `test_failed_calls_are_recorded_and_preparation_is_unchanged` 통과 |
| AC-05 | 측정 runner | 시나리오 2개 × N회 실행, 계측 파일 N개씩, 실패 0 | 로컬 Gradle 실행 | 아래 결과 |
| AC-06 | 요약 스크립트 | 단계별·전체 p50/p95(최근접 순위), 예열 제외 수 표시, 입력 0건·버전 불일치·전부 예열 제외는 거부 | 단위 테스트 + 실행 | `test_summarize_ai_call_metrics.py` 통과, 아래 결과 |
| AC-07 | evidence | revision, 환경, 명령, N, 결과 표, `tool_call_audit` 교차 확인, 해석과 한계 | diff 검토 | `docs/evidence/AI_CALL_METRICS_EVIDENCE.md` |
| AC-08 | 변경 범위 | Core 코드(main), DB 스키마, 기존 계약 diff 없음. 일괄 조회·병렬화·캐시 없음 | diff 검토 | `git diff main --stat`: Core main 소스 변경 없음(test 소스와 build.gradle만) |
| AC-09 | CI | 기존 테스트 수 유지, 새 테스트 추가, runner는 CI에서 실행되지 않음 | CI run(push 뒤) | PR #43 CI run [37930635662](https://github.com/hj1016/trust-agent/actions/runs/37930635662): Python contracts pass, Gradle tests pass(연결 검증 포함). runner는 속성 없이 skip |

### 완료 기준 변경 이력

변경 없음.

## Implementation Plan

- 대안 비교(측정 하네스): (a) 사람이 Core를 직접 띄우고 runner 6개로 상태를 만든 뒤 CLI 반복 → 재현성 낮음. (b) TASK-015 Java 하네스 재사용(Testcontainers + 고정 시계 + `PreparationScenario.load`) → 한 명령으로 재현, 상태 동일. 하네스 오버헤드가 절대값에 포함되므로 evidence에 명시. (c) Python만으로 상태 구성 → TASK-011a 코드 중복. **(b) 채택.**
- 대안 비교(계측 위치): CLI stderr에 찍기 vs 별도 파일. 파일 방식이 요약 스크립트 입력으로 바로 쓰이고 stdout 계약을 건드리지 않아 채택. 시간 단위는 정수 마이크로초(부동소수점 회피).
- 시나리오: `partial-2026-10-06`(중도상환수수료 READY, 셀러론 HOLD. 첫 실행 RECORDED, 이후 ALREADY_RECORDED 경로), `hold-2026-09-30`(FIXTURE 기간이라 두 공문군 HOLD, Tool 2 없음).
- 예열: JVM JIT와 커넥션 풀 예열분으로 앞 5회를 요약에서 제외하고 그 수를 표에 적는다.

## Implementation Result

변경 파일: `apps/ai-service/ai_service/metrics.py`(신규), `apps/ai-service/ai_service/cli.py`(옵션 추가), `contracts/ai-call-metrics.schema.json`(신규), `scripts/summarize_ai_call_metrics.py`(신규), `tests/ai_service/test_call_metrics.py`·`tests/unit/test_summarize_ai_call_metrics.py`(신규), `apps/core-service/src/test/java/.../AiServiceCallMetricsRunner.java`(신규, 테스트 소스), `apps/core-service/build.gradle`(`-PaiCallMetrics` 속성), `apps/ai-service/README.md`, `docs/evidence/AI_CALL_METRICS_EVIDENCE.md`, `docs/evidence/task-021/`.

| AC ID | 검증 대상 revision | 실행 명령/절차 | 환경/버전 | 결과(실패/skip 포함) | evidence 경로 |
|---|---|---|---|---|---|
| AC-01~04, 06 | 이 브랜치 작업 트리(base `c54c923`) | `python3 -m unittest discover -s tests -t . -v` | Python 3.11.8 | 115건 통과, 실패 0, skip 2(기존 비공개 snapshot 사유). 신규 9건 포함 | 이 문서 |
| AC-05, 07 | 같음 | `./gradlew :apps:core-service:test --tests '*AiServiceCallMetricsRunner' -PaiCallMetrics=true -PaiServiceIntegration=true --no-daemon` | Java 21.0.11, Docker 28.3.3, Testcontainers PostgreSQL, macOS 27.0 aarch64 | TEST SUCCESS 1/1, 시나리오 2개 × 30회, 계측 파일 60개, 실패 0, `tool_call_audit` 행 210 = 계측 Tool 호출 합 | `docs/evidence/AI_CALL_METRICS_EVIDENCE.md`, `docs/evidence/task-021/` |
| AC-08 | 같음 | `git diff main --stat -- apps/core-service/src/main contracts/consultation-preparation*.schema.json` | 로컬 | 변경 없음. 변경은 AI 서비스 CLI·계측 모듈, 테스트 소스, build.gradle, 신규 계약·스크립트·문서뿐 | diff |
| AC-09 | 같음 | `./gradlew :apps:core-service:test --no-daemon`(로컬 기본) | 같음 | `186 tests, 179 passed, 0 failed, 7 skipped (SUCCESS)`. skip 7건은 로컬 기본의 명시적 skip(연결 검증 6건, TASK-015와 같음)과 `AiServiceCallMetricsRunner`(속성 없음) 1건. 기존 185건은 그대로이고 runner 1건이 더해져 186건 | `docs/evidence/task-021/` |

측정 결과 요약(최근접 순위 p50 / p95, ms. 상세와 해석은 evidence 문서):

| 시나리오 | 측정 N(예열 제외) | 전체 | Tool 1 호출당 | Tool 2 호출당 | 기록 호출 | 조립(호출 외) |
|---|---|---|---|---|---|---|
| partial-2026-10-06 (Tool 1 ×2, Tool 2 ×3, 기록 ×1) | 25(5) | 45.5 / 49.5 | 6.8 / 14.2 | 6.0 / 8.4 | 8.1 / 14.5 | 1.9 / 2.2 |
| hold-2026-09-30 (Tool 1 ×2, 기록 ×1) | 25(5) | 33.2 / 39.1 | 9.7 / 12.6 | 없음 | 12.1 / 14.1 | 1.4 / 1.6 |

판단에 쓰는 결론(측정값에 한정): 실행 시간은 거의 전부 Core 호출이지만 호출 하나가 6~12 ms이고 Tool 2 세 번의 합이 p50 약 18 ms라, 이 환경에서 일괄 조회·병렬화·캐시는 정당화되지 않는다. Python 프로세스 기동과 클라우드 네트워크 지연은 측정 밖이므로 TASK-023·024 환경에서 사용자 경로 전체를 다시 잰다.

### 계획과 다른 점 (완료 기준 변경 없음)

- 계획 단계에서는 시나리오 세 개(PARTIAL, 전체 HOLD, 같은 입력 재실행 = ALREADY_RECORDED)를 제안했다. 구현에서는 재실행을 별도 시나리오로 두지 않았다. 같은 신청·업무일로 N회 반복하면 첫 실행만 `RECORDED`이고 2회째부터는 그 자체가 ALREADY_RECORDED 경로(저장 전 재확인 뒤 기존 기록 반환)이기 때문이다. 따라서 **측정값 25건은 전부 ALREADY_RECORDED 경로**이며, 첫 기록(`RECORDED`) 경로는 예열 구간의 1건(전체 117.4 ms, Tool 1 첫 호출 62.9 ms, 기록 19.1 ms)뿐이라 통계로 쓰지 않았다. RECORDED 경로의 p50/p95가 필요하면 실행마다 다른 신청 자료나 업무일을 쓰는 시나리오를 추가해야 하며, 현재 합성 자료(신청 1건)로는 만들 수 없어 범위 밖으로 둔다.
- 측정 환경의 `--offline` 실패(플러그인 해석)로 네트워크 허용 실행을 썼다. 의존성 변경은 없다.

## AI self-review

- 검사 범위: 계약 불변(출력·기록 schema diff 없음), 계측이 호출 순서·횟수를 바꾸지 않음(가짜 전송 비교), 계측 파일의 비밀·원문 부재, 요약 계산 방식, runner의 교차 확인 단언, CI 미포함 조건(`-PaiCallMetrics` 없으면 `@EnabledIfSystemProperty`로 비활성).
- 발견과 처리: (1) 새 worktree에서 `--offline`이 Gradle 플러그인 해석에 실패해 네트워크 허용으로 실행했다. 의존성 자체는 캐시에서 해석됐다. (2) 첫 실행의 Tool 1 호출이 63 ms로 뒤 실행보다 10배 느려 예열 5회를 요약에서 제외하고 그 값을 evidence에 따로 적었다.
- 미해결 위험: 측정 구간이 프로세스 기동을 포함하지 않아 CLI 사용자 체감과 다르다. 자료가 작아 Tool 2 비중이 과소평가될 수 있다. CI에서 runner를 돌리지 않으므로 runner 자체의 회귀는 로컬 실행으로만 드러난다.

## 인간 검수와 Explainability Gate

검수자 / 검수 대상 revision / 결과: 판단 대기

## 결정 기록과 완료

최종 결정 / 결정자 / 승인 범위 / 검토 대상 PR: 판단 대기
