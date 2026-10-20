# 승인 근거 검색 기반 분리 반영 검증

## 대상과 출처

- 원격 기준선: origin/main ac62801.
- 분리 브랜치: codex/search-foundation. 커밋 전 상태를 아래 검사로 확인했으며 PR의 최종 커밋에서 CI를 다시 확인한다.
- 기존 검색 커밋 0bd0bfd, 00e68f2, fce03d2, ceed2b3, e334d04 중 비UI 코드, 계약, 합성 자료와 테스트만 선별했다. 실행 로그와 과거 품질 결과를 일괄 복사하지 않았다.
- TASK-028과 기존 TASK-016, ADR-013의 Core 재확인 경계를 따른다. 검색 모델 선택과 품질 완료, 인간 검수 통과는 주장하지 않는다.

## 자동 검증

| 항목 | 명령 및 조건 | 결과 |
|---|---|---|
| Python | Python 3.11 기존 AI 검증 venv에서 `python -m unittest discover -s tests -t .` | 173건 중 168 통과, 실패 0, skip 5 |
| Java 컴파일 | Java 21, `./gradlew :apps:core-service:compileTestJava --offline --no-daemon` | 성공 |
| Java 전체 및 실행 jar | `TRUST_AGENT_REQUIRE_AI_INTEGRATION=1 ./gradlew :apps:core-service:test :apps:core-service:bootJar --offline --no-daemon -PaiServiceIntegration=true -x :apps:core-service:prepareAiServiceEnv` | 252건 중 249 통과, 실패 0, skip 3. jar 생성 성공 |
| 실제 연결 | SearchGrantIntegrationTest 7건, AiGrantEndToEndIntegrationTest 2건 | 모두 실행 및 통과. 실제 Core, PostgreSQL, Elasticsearch와 uvicorn 사용 |
| 재색인 회귀 | SearchReindexIntegrationTest 10건 | 모두 통과 |
| 경로 검사 | origin/main 대비 frontend, UI 하네스, CI diff | 변경 없음 |

로컬 Java 검증은 기존 AI 검증 venv를 새 checkout의 build/ai-venv에서 재사용해 환경 생성만 제외했다. 테스트 자체는 제외하지 않았다. CI는 기존 prepareAiServiceEnv를 통해 requirements를 설치하고 필수 연결 검증을 실행한다.

Python skip 5건은 비공개 원문 artifact 2건과 실제 e5 모델 디렉터리 미지정에 따른 추론 3건이다. Java skip 3건은 AiServiceCallMetricsRunner, SearchEvaluationRunner, UiEndToEndRunner의 전용 플래그 미지정이다. 실제 e5 모델 추론 및 하이브리드 품질 측정은 위 자동 검증 통과와 구분한다.

## 검증 범위와 한계

- 정상 요청과 토큰 누락, grant 누락 및 만료, 공문군·상담 건·업무일 범위 밖 요청을 검증했다.
- BM25, vector, hybrid의 코드 경로와 RRF 순위 결합은 단위 테스트에서 검증했다. 모델 파일을 사용한 실제 임베딩 품질은 이번 실행에서 측정하지 않았다.
- 기존 준비안, 인증과 기록 경로는 전체 회귀 및 실제 연결 테스트로 확인했다.
- 전용 평가 하네스는 포함하지만 품질 기준 미달이었던 과거 결과를 통과로 바꾸지 않았다. BGE-M3 비교, 보류 기준 확정, 최신 공통 JSON 프로필 및 검색 조정은 후속이다.
- LLM 생성과 상담 업무 API, UI 변경은 이 PR 범위에 없다. 기존 원격 UI를 삭제하지 않는다.

## AI self-review 및 보안 검사

- 선별 경로에 frontend, 화면 전용 하네스, 모델 가중치, .env, 개인키, 원본 snapshot, 대량 실행 기록 및 worktree.patch가 없음을 확인했다.
- 기존 검색 이력 보존 여부와 원격 main의 코드 삭제가 없는지 확인했다. 기존 PR #50은 수정하거나 닫지 않았다.
- 최종 staged 변경을 Gitleaks 기본 규칙, 전체 가림(redact=100)으로 검사해 탐지 0건을 확인했다. push 전 새 커밋 이력도 검사하며 탐지 시 push를 중단한다.
- 인간 검수와 Explainability Gate는 사용자 대기다.
