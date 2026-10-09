# AI 호출 계측 기준선 검증 기록 (TASK-021)

규칙 조립 상담 준비안 흐름(LLM 0회)에서 AI 서비스가 Core에 보내는 호출의 횟수와 소요 시간을 실측한 기록이다. 수치는 아래 명령의 실제 실행 결과이며 원본 계측 파일은 `docs/evidence/task-021/metrics-all.json`(60건), 요약은 `summary.md`·`summary.json`, 환경은 `environment.json`에 있다.

## 검증 대상과 환경

| 항목 | 값 |
|---|---|
| 검증 대상 revision | 브랜치 `feat/ai-call-metrics` 작업 트리(base `c54c923`). Core main 소스·DB 스키마·기존 계약 변경 없음 |
| 명령 | `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :apps:core-service:test --tests '*AiServiceCallMetricsRunner' -PaiCallMetrics=true -PaiServiceIntegration=true --no-daemon` |
| 하네스 | `AiServiceCallMetricsRunner`(테스트 소스, CI 미포함): Testcontainers PostgreSQL(`postgres@sha256:86c951e0…`), Spring Boot RANDOM_PORT, 고정 시계 2026-10-06T03:00:00Z, `PreparationScenario.load` 상태(중도상환수수료 v2 승인, 셀러론 v2 검토 대기). 실행마다 AI 서비스 CLI를 별도 프로세스로 실행하고 `--metrics-file`로 계측 |
| 환경 | macOS 27.0 aarch64(Apple M3 Pro), Java 21.0.11, Python 3.11.8(venv `build/ai-venv`), Docker 28.3.3. Core와 AI 서비스는 같은 호스트의 loopback, PostgreSQL은 Docker VM |
| 실행 수 | 시나리오당 30회, 요약에서 앞 5회 예열 제외(측정 N = 25) |
| Tool 시간 초과 설정 | 5초 |
| 결과 | `TEST SUCCESS: AiServiceCallMetricsRunner.measureScenarios()`, 실패 0, 계측 파일 60개 |

`--offline`은 새 worktree에서 Gradle 플러그인 해석에 실패해 쓰지 않았다(의존성은 캐시에서 해석됨).

## 시나리오

| 시나리오 | 업무일 | 준비안 상태 | 호출 구성(실행당) |
|---|---|---|---|
| `partial-2026-10-06` | 2026-10-06 | PARTIAL(중도상환수수료 READY 항목 3, 셀러론 HOLD) | Tool 1 두 번, Tool 2 세 번, 기록 한 번 = 6회 |
| `hold-2026-09-30` | 2026-09-30 | HOLD(FIXTURE 기간, 두 공문군 모두) | Tool 1 두 번, 기록 한 번 = 3회 |

같은 입력이라 첫 실행만 `RECORDED`이고 이후는 `ALREADY_RECORDED`(저장 전 재확인 포함) 경로다. 측정 N 25건은 모두 `ALREADY_RECORDED`다. 계획의 '재실행' 시나리오는 이 반복 자체가 그 경로라 별도로 두지 않았고, `RECORDED` 경로는 예열 구간 1건뿐이라 통계로 쓰지 않았다(TASK-021 문서 '계획과 다른 점').

## 결과 (최근접 순위 p50 / p95, 단위 ms)

| 시나리오 | 측정 N(예열 제외) | 전체 | Tool 1 호출당 | Tool 2 호출당 | 기록 호출 | 조립(호출 외) | 호출 결과 |
|---|---|---|---|---|---|---|---|
| partial-2026-10-06 | 25(5) | 45.5 / 49.5 (최소 32.2, 최대 71.4) | 2회, 6.8 / 14.2 | 3회, 6.0 / 8.4 | 1회, 8.1 / 14.5 | 1.9 / 2.2 | OK 150 |
| hold-2026-09-30 | 25(5) | 33.2 / 39.1 (최소 24.3, 최대 65.6) | 2회, 9.7 / 12.6 | 0회 | 1회, 12.1 / 14.1 | 1.4 / 1.6 | OK 75 |

예열 구간(참고, 요약에서 제외): partial 첫 실행 전체 117.4 ms(첫 Tool 1 호출 62.9 ms, 기록 19.1 ms), 2회째 54.6 ms, 3회째 43.1 ms. 첫 호출의 지연은 Core 쪽 JIT·커넥션 예열로 보이며 이후 안정된다.

## 교차 확인

- `tool_call_audit`에서 상담 ID `metrics-%`인 행 수 210 = 계측 파일의 Tool 호출 수 합(partial 30×5 + hold 30×2). runner가 단언으로 확인했다.
- 계측 파일 60개 모두 `contracts/ai-call-metrics.schema.json`을 따른다(단위 테스트가 같은 생성 경로를 검증).
- 계측 유무에 따른 준비안 출력·종료 코드 동일(`tests/ai_service/test_call_metrics.py`).

## 해석 (측정값에 한정)

- 한 실행의 시간은 거의 전부 Core HTTP 호출이다(partial p50 기준 호출 합 약 43.6 ms 중 조립은 1.9 ms). 그러나 호출 하나는 6~12 ms이고 Tool 2 세 번의 합은 p50 약 18 ms로 전체의 약 40%다. 이 환경에서 Tool 2를 일괄 조회로 바꿔도 실행당 절감은 10 ms대이며, "N+1이 병목"이라 할 수준이 아니다.
- 기록 호출이 Tool 호출보다 느린 편(8~12 ms)인 것은 저장 전 재확인과 SERIALIZABLE 트랜잭션 때문으로 보이며 설계된 비용이다.
- 따라서 일괄 조회, 병렬화, 캐시는 이 기준선으로는 정당화되지 않는다. 사용자 체감 지연은 이 측정 밖의 요소(아래 한계)가 결정할 가능성이 크므로 그쪽을 먼저 측정한다.

## 한계

- 측정 구간은 CLI 안의 `prepare()` 시작부터 끝까지다. Python 인터프리터 기동과 모듈 import, 프로세스 생성 시간은 포함하지 않는다. HTTP 진입점(`app.py`)은 계측하지 않았다.
- 같은 호스트 loopback과 Testcontainers 오버헤드가 포함된 값이며 클라우드 네트워크 지연은 반영되지 않는다. 동시 요청 없이 순차 단일 클라이언트다.
- 자료가 작다(공문군 2개, 항목 3개). 항목 수가 늘면 Tool 2 비중이 선형으로 커진다.
- 토큰·비용은 LLM 호출이 없어 0이다.
