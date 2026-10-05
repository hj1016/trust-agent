# TASK-001 미커밋 합성 공문과 적용 공문 조회 자산을 검증 가능한 Pre-SDLC 기준선으로 보존

- 상태: **검증/검수 대기.** 구현과 자동 검증 완료, PR #11 병합됨(사용자). 인간 검수와 Explainability Gate는 미기록.
- 담당자 / 인간 결정자: AI(조사, 초안, 실행, 자동 검증, self-review) / 사용자(범위, AC 확정, 검수, Explainability Gate)
- 요구사항 출처: [자산 audit](TASK-000_자산-audit-초안.md) 5절 처리안 1 조건부 채택(결정자 사용자, 검토 대상 audit 초안 미커밋 revision)
- 관련: [TASK-000](TASK-000_초기-수집-자산-기준선-점검.md), [ADR-008](../adr/ADR-008-internal-notice-effective-policy-and-review.md), [ADR-009](../adr/ADR-009-oracle-core-database.md), [PLAN-001](PLAN-001_중도상환수수료-흐름-Task-분할-초안.md)

## Goal / 관련 요구사항

- Goal: 작업 트리에만 있는 적용 공문 조회와 최종 기획서 정합화 작업(25항목)을 Git history에 보존해, 대표 시나리오 데이터, 계약, 적용 공문 조회 로직, 테스트가 검증 가능한 Pre-SDLC 기준선이 되게 한다. 후속 기능 Task(proposal, validation, 사람 결정)는 이 기준선 위에서 PostgreSQL schema를 확장한다.
- 관련 요구사항: 자산 audit 4.1, 4.3, 4.4, 4.6 판정(KEEP, MODIFY 보존), ADR-009 5절 evidence 표기.
- 사용자: 저장소 관리자(사용자), 후속 Task를 수행하는 AI.
- 사전조건: 복구용 사본 `/Users/faker/Dev/trust-agent-backups/2026-10-05-uncommitted-day5/`(target-list 25항목, patch, tar) 존재. 로컬 `main`이 `origin/main`(PR #9 병합 커밋 `acd572b`)보다 뒤에 있음.
- 입력: `git status --porcelain` 25항목(추적 수정 13, 미추적 12). 묶음 A~D는 audit 5절.
- 업무규칙: 코드 로직을 바꾸지 않는다. Pre-SDLC 자산을 소급해 AI-native로 기록하지 않는다. 커밋 메시지에 AI 사용 사실을 쓰지 않는다. 승인 범위 밖 파일을 포함하지 않는다.
- 상태전이: 미커밋 작업 트리 → 새 브랜치 커밋 → PR → (사용자 검수) → 병합. 금지 전이: main 직접 커밋, force push, 사본 삭제.
- 데이터 영향: 저장소 파일만. DB 없음.
- API: 변경 없음(기존 미커밋 endpoint가 history에 들어감).
- 트랜잭션: 해당 없음.
- 권한: push와 PR 생성은 사용자 승인 뒤 AI가 실행하거나 사용자가 직접 실행.
- 실패 시나리오: 커밋 후 테스트 실패 시 커밋을 되돌리지 않고 원인을 Task에 기록해 사용자 판단. 사본과 작업 트리 불일치 시 중단.
- 테스트: 기존 Python 58개, Java 82개 재실행(PostgreSQL). 새 테스트 없음.
- Out of Scope: DB 제품 변경, 코드 로직 변경, migrate 스크립트 삭제(TASK-002), 문서 날짜와 허용된 표기 외 내용 변경.

## 요구사항과 범위

업무 문제: 대표 시나리오(중도상환수수료 1.2 → 0.8퍼센트)와 적용 공문 조회 로직이 작업 트리에만 있어 유실 위험이 있고 비교 기준이 없다.

포함 범위(audit 5절 묶음):
- A. `datasets/synthetic/internal/notices/prepayment-fee-v1.json`, `-v2.json`, `datasets/synthetic/internal/receipts/prepayment-fee-*.receipt.json` 2개, `datasets/derived/synthetic-internal/policy-extraction-attempts/prepayment-fee-*.extraction.json` 2개, `contracts/synthetic-internal-notice.schema.json` diff, `datasets/synthetic/internal/notices/seller-loan-checklist-v1.json`, `-v2.json` diff, `tests/contract/test_synthetic_notice_contracts.py` diff
- B. `apps/core-service/src/main/java/com/trustagent/core/internalpolicy/query/` 6개 파일, `apps/core-service/src/test/java/com/trustagent/core/bootstrap/AppendOnlyBootstrapChecksTest.java`
- C. `apps/core-service/src/main/resources/db/migration/V5__add_structured_internal_policy_changes.sql`, `SyntheticInternalImporter.java` diff, `application.yml` diff, 테스트 4개 diff(`CoreApplicationIntegrationTest`, `RuntimeDatasourcePropertiesTest`, `PublicProductSchemaIntegrationTest`, `SyntheticInternalImporterIntegrationTest`), `apps/core-service/src/test/java/com/trustagent/core/internalpolicy/query/InternalPolicyApplicableIntegrationTest.java`
- D. `docs/evidence/INTERNAL_POLICY_APPLICABLE_QUERY_EVIDENCE.md`, `docs/evidence/FINAL_PROPOSAL_ALIGNMENT_EVIDENCE.md`, `README.md` diff, `apps/core-service/README.md` diff, `AGENTS.md` diff

제외 범위: 이번 세션에서 작성한 audit, ADR-009, PLAN-001, TASK-001~003 문서와 개발 규칙/템플릿/CLAUDE.md 수정은 별도 문서 PR로 분리한다(이 Task의 업무 결과가 아님). `docs/tasks/TASK-000_*.md` 수정도 같다.

기존 자산 구분: 전부 Pre-SDLC Asset. 커밋 `a6149bb`(합성 공문 계약과 저장 구조) 이후 작성됐고 PostgreSQL 기준으로 검증됐다.

## Acceptance Criteria (구현 전 고정)

결정자 / 판단 근거 / 검토 대상 revision 또는 PR: 사용자 / audit 5절 처리안 1 조건 / 이 Task 미커밋 초안. **AC 확정됨.** 승인 시 사용자가 지시한 수정 5건(변경 이력 참조)을 반영했다.

| ID | 입력/상황 | 기대 결과(실패/경계 포함) | 검증 방법 | 결과/evidence |
|---|---|---|---|---|
| AC-01 | 커밋 직전 | 보존 대상을 **파일 단위 목록**(추적 수정 13 + 미추적 17 = 30파일. status 항목 25개는 디렉터리 포함 수)으로 확정하고, 각 파일의 내용을 현재 작업 트리와 sha256으로 대조. 허용된 변경(AC-03, AC-04 대상 3파일)만 다를 수 있음. 그 외 차이 있으면 중단 | 파일 목록과 sha256 비교 스크립트 출력 | 미검증 |
| AC-02 | 새 브랜치(origin/main 기준)에 커밋 | 커밋 변경 파일이 AC-01의 30파일과 정확히 일치. **제외 목록**(이번 세션 문서 PR 대상과 그 외 변경)은 별도 목록으로 관리하고 작업 트리에 그대로 보존. 제외 목록 파일이 커밋에 없음 | `git show --stat` 대조, 제외 목록 파일의 작업 트리 존재 확인 | 미검증 |
| AC-03 | evidence 2건 | 머리에 "PostgreSQL 18.6 Testcontainers 기준 Pre-SDLC 증거. Core 업무 DB는 PostgreSQL 유지로 결정(ADR-009). 현재 기준의 인간 검수 통과를 뜻하지 않음" 표기 1단락 추가. 본문 숫자와 서술은 변경 없음. 이 변경과 앞서 승인된 날짜 표기 제거는 **백업 대비 허용된 변경**으로 구분해 기록 | diff 검토 | 미검증 |
| AC-04 | `apps/core-service/README.md` | "Core 업무 DB는 PostgreSQL이며(ADR-009) 구현 설명은 PostgreSQL 18.6 기준" 1문장 추가. 그 외 변경 없음. 백업 대비 허용된 변경으로 구분 | diff 검토 | 미검증 |
| AC-05 | 커밋 후 Python | **커밋 대상만 포함한 별도 체크아웃(git worktree)**에서 `python3 -m unittest discover -s tests` 58개 통과, 실패 0. Public Git 조건(`TRUSTAGENT_PRIVATE_ARTIFACT_ROOT` 빈 경로)에서 skip 3, 실패 0. 제외된 미커밋 파일에 의존하지 않음을 확인 | 명령 출력 | 미검증 |
| AC-06 | 커밋 후 Java | 같은 별도 체크아웃에서 `./gradlew clean test bootJar --offline --no-daemon` 82개 통과, 실패 0, skip 0, jar 생성(PostgreSQL 18.6 Testcontainers) | 명령 출력, `build/test-results` | 미검증 |
| AC-07 | 커밋 메시지 | `feat: 합성 중도상환수수료 공문과 적용 공문 조회를 Pre-SDLC 기준선으로 보존` 형식(타입 + 한국어). AI 사용 사실 없음 | 메시지 검토 | 미검증 |
| AC-08 | push 전 | (a) CI 금지 파일 패턴(`.html`, `.env`, `.private-artifacts/`) 없음. (b) **변경 내용과 추적 파일 내용**에 비밀 패턴(password, secret, token, api key, private key, JDBC URL 자격증명, AWS key 형식) 검색. (c) 검색 후보를 하나씩 검토해 실제 비밀 아님을 기록 | 금지 파일 검사 + 내용 검색 출력 + 후보 검토 표 | 미검증 |
| AC-09 | PR | 제목 한국어, 본문에 묶음 A~D 요약과 AC-05/06 결과. 병합 방식 `Squash and merge`. 예외 기록 없음 | PR 검토 | 미검증 |
| AC-10 | 복구용 사본 | Task 완료 후에도 사본 삭제 없음 | 디렉터리 존재 확인 | 미검증 |

### 완료 기준 변경 이력

| 기준 ID | 변경 전 | 변경 후 | 이유 | 결정자 | 승인 범위 | 검토 대상 revision/PR | 영향/재검증 |
|---|---|---|---|---|---|---|---|
| AC-01 | git status 25항목을 백업 목록과 비교 | 파일 단위 30파일 목록과 sha256 내용 대조. 디렉터리 항목 수와 파일 수 구분 | 작업 트리에 이번 세션 문서가 섞여 status 비교가 모순 | 사용자 | 승인 범위 모순 해결, 재승인 불필요 | 이 Task 초안 | 대조 스크립트로 검증 |
| AC-02 | 제외 범위 파일 없음 | 제외 목록을 별도 관리하고 보존 | 같음 | 사용자 | 같음 | 같음 | 제외 목록 존재 확인 |
| AC-03, AC-04 | 표기 추가 | 백업 대비 허용된 변경으로 구분 | 승인된 변경과 미승인 차이를 분리 | 사용자 | 같음 | 같음 | diff 검토 |
| AC-05, AC-06 | 커밋 후 테스트 | 커밋 대상만 포함한 체크아웃에서 테스트 | 제외된 미커밋 파일 의존 배제 | 사용자 | 같음 | 같음 | worktree 실행 |
| AC-08 | 금지 파일명 검사 | 내용 검사와 후보 검토 포함 | 파일명만으로는 secret 누락 | 사용자 | 같음 | 같음 | 검색 출력과 검토 표 |
| AC-03, AC-04 | Oracle 전환 전제 표기("Oracle 기준 미검증", "Oracle 전환 미완") | PostgreSQL 유지 결정(ADR-009) 반영 표기 | Core DB 결정 변경. 기능 범위 확대 없음 | 사용자 | 충돌 문구와 링크 정정만 허용 | PR #11 | diff 검토, 테스트 재실행 |
| 범위 D | README.md, AGENTS.md의 "Core 업무 DB Oracle" 문장은 그대로 보존 | PostgreSQL 유지와 ES/Core Tool API 미구현으로 정정 | 새 DB 결정과 충돌하는 문구 정정 | 사용자 | 같음 | PR #11 | diff 검토 |

## Implementation Plan (AI 구현 전 계획)

1. `git fetch origin`, `git switch -c feat/day-05-pre-sdlc-baseline origin/main`. 작업 트리의 미커밋 변경은 브랜치 전환 시 그대로 따라온다(현재 브랜치 `task/000-baseline-audit`는 origin/main의 조상이므로 충돌 없음). 충돌 시 중단.
2. AC-01 대조.
3. evidence 2건과 core README에 AC-03, AC-04 표기 추가(이 Task에서 허용된 유일한 내용 변경).
4. 포함 범위 25항목 + 3단계 변경만 `git add`. 제외 범위(이번 세션 문서)는 add하지 않는다.
5. AC-05, AC-06 실행 후 커밋.
6. AC-08 검사 후 사용자 승인 시 push와 PR.

대안: 처리안 2(PostgreSQL 결합 부분 제외)와 3(전부 제거)은 audit에서 기각됐다.
위험: 작업 트리에 이번 세션의 문서 변경이 섞여 있어 선택 staging 실수 가능. AC-02로 잡는다.
트랜잭션/동시성/권한: 해당 없음.
인간의 계획 판단 / 승인 범위: **승인.** 1~6단계. 검증, secret 점검, self-review 후 commit, push, PR 생성까지. 병합은 사용자(Squash and merge). 2단계는 별도 체크아웃(git worktree)에서 수행해 현재 작업 트리의 미커밋 파일을 건드리지 않는다.

## AI 제안 및 인간 판단 기록

### 제안 1: 이번 세션 문서(audit, ADR-009, PLAN-001, TASK-001~003, 규칙/템플릿/CLAUDE.md 수정)를 별도 문서 PR로 분리

**AI 제안**
내용: 보존 PR은 Pre-SDLC 자산만 담고, AI-native SDLC 문서는 별도 PR(PR #10)로 낸다.
대안: 한 PR에 합친다.
기대 효과: "하나의 PR은 하나의 업무 결과" 유지. Pre-SDLC 자산과 현재 기준 문서가 history에서 구분된다.
위험: PR 2개로 검수 횟수 증가.

**판단**
- [x] 채택
- 판단자 / 검토 대상 revision 또는 PR: 사용자 / 이 Task 초안
- 이유 / 승인 범위: 이번 세션의 audit, ADR, PLAN, Task 문서와 개발 규칙, 템플릿, CLAUDE.md 변경은 별도 문서 PR. 자산 보존 PR에 혼입하지 않음. 문서 PR에서도 실행 승인, 구현 완료, 인간 검수 통과를 구분

## Implementation Result (구현 결과와 자동 검증)

실제 변경: 브랜치 `feat/day-05-pre-sdlc-baseline` 커밋 2개(`d6a0fbc` 보존 30파일, `ce64bee` Core DB 표기 PostgreSQL 유지 정정 5파일). PR #11을 사용자가 Squash and merge해 main 커밋 `598f165`("feat: 합성 중도상환수수료 공문과 적용 공문 조회 추가 (#11)")가 됐다. 계획 대비 차이: 허용 변경 3건에 더해 README.md와 AGENTS.md의 Core DB 문장 정정이 추가됐다(변경 이력 참조). 코드 로직 변경 없음.

| AC ID | 검증 대상 revision | 실행 명령/절차 | 환경/버전 | 결과(실패/skip 포함) | evidence 경로 |
|---|---|---|---|---|---|
| AC-01 | 작업 트리 vs worktree | 파일 단위 목록(추적 수정 13 + 미추적 17 = 30)과 sha256 대조 | 로컬 macOS arm64, Python 3.11.8 | **통과.** 허용 변경 3파일만 차이 | 복구용 사본 `task-001-file-lists.json` |
| AC-02 | `d6a0fbc` | `git show --stat`, 제외 목록 11파일 작업 트리 존재 확인 | 같음 | **통과.** 30파일 커밋, 제외 목록 미포함 | PR #11 diff |
| AC-03 | `d6a0fbc`, `ce64bee` | evidence 2건 diff 검토 | — | **통과.** 머리 표기 1단락, 본문 숫자와 서술 불변 | PR #11 diff |
| AC-04 | 같음 | core README diff 검토 | — | **통과.** 1문장 추가 | PR #11 diff |
| AC-05 | `d6a0fbc`, `ce64bee` | `python3 -m unittest discover -s tests` (worktree, 비공개 artifact 지정) / Public Git 조건 | 같음 | **통과.** 58 통과, 실패 0, skip 0 / 55 통과, 3 skip | 로컬 출력. 원격: CI run 37258568194 Python contracts 통과 14초 |
| AC-06 | `d6a0fbc` | `./gradlew clean test bootJar --offline --no-daemon` (worktree) | Java 21(ms-21.0.11), Docker, PostgreSQL 18.6 Testcontainers | **통과.** 82 통과, 실패 0, skip 0, jar 생성 | 로컬 출력. 원격: CI run 37258568194 Gradle tests 통과 1분 47초 |
| AC-07 | `d6a0fbc` | 커밋 메시지 검토 | — | **통과.** `feat: 합성 중도상환수수료 공문과 적용 공문 조회를 Pre-SDLC 기준선으로 보존`. 병합 커밋 메시지는 사용자가 지정 | git log |
| AC-08 | `d6a0fbc`, `ce64bee` | 금지 파일 패턴 검사, 변경 내용과 추적 파일 비밀 패턴 검색, 후보 검토 | — | **통과.** 금지 파일 없음. 후보는 테스트 fixture 문자열("secret", "runtime-password" 등 Testcontainers role), 설정 placeholder, 변수명. 실제 비밀 없음 | PR #11 본문 |
| AC-09 | PR #11 | PR 검토 | — | **통과.** 한국어 제목, 본문에 묶음 A~D와 검증. Squash and merge, 예외 기록 없음 | https://github.com/hj1016/trust-agent/pull/11 |
| AC-10 | — | 디렉터리 존재 확인 | — | **통과.** 복구용 사본 유지 | `/Users/faker/Dev/trust-agent-backups/2026-10-05-uncommitted-day5/` |

원격 CI 기록(이 Task의 검증): PR #11 브랜치 run `37258568194`(Python contracts 통과, Gradle tests 통과). 병합 뒤 main run `37259090769`(통과). 링크: https://github.com/hj1016/trust-agent/actions/runs/37258568194 , https://github.com/hj1016/trust-agent/actions/runs/37259090769

## AI self-review

- 검사 범위: 파일 목록과 sha256 대조, 제외 목록, 두 커밋의 diff, 비밀 패턴 검색, 로컬과 원격 테스트 결과.
- 발견 사항: (1) 처음 worktree 복사가 동시 실행된 다른 명령의 `cd` 때문에 빈 상태로 끝났고, 절대 경로로 다시 실행해 AC-01을 통과했다. (2) worktree에는 `.private-artifacts`가 없어 비공개 artifact 경로를 메인 작업 트리로 지정해 58개 전부 실행했다. (3) 두 번째 커밋은 Core DB 결정 변경에 따른 문구 정정이며 Java 재실행은 하지 않았다(Java 외 문서만 변경). 원격 CI가 두 커밋 모두 통과를 확인했다.
- 미해결 위험: 없음. 후속 Task는 이 기준선 위에서 schema를 확장한다.

## 인간 검수와 Explainability Gate

미기록. 사용자의 실행 승인과 PR 병합은 검수 통과가 아니다. REVIEW_CHECKLIST 적용과 설명은 사용자가 기록한다.

## 결정 기록과 완료

- 병합: 사용자가 PR #11을 Squash and merge(main `598f165`).
- Acceptance Criteria: AC-01~AC-10 전부 통과(위 표).
- 완료 판정: **대기.** 인간 검수와 Explainability Gate 기록 뒤 사용자가 결정한다.
- 잔여 위험 / 후속 Task: TASK-002(migrate 스크립트 DROP 실행), TASK-005(proposal 생성).
