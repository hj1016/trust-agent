# TASK-020 비밀 검사 CI 추가와 pre-commit 설정 추적

- 상태: 구현 완료·로컬 검증 완료, 검증·검수 대기 (범위 승인: 사용자. 커밋·push·PR은 별도 지시)
- 담당자 / 인간 결정자: AI 구현·검증 / 사용자 범위 승인·검수
- 요구사항 출처: 사용자 지시(공개 저장소 보안 체계 유지, 기존 CI 유지, 비밀정보 비노출 검증), DEVELOPMENT_RULES "push 전 secret 점검", 기존 로컬 Gitleaks·pre-commit 설정(`.pre-commit-config.yaml`, 사용자 작성)
- 관련 Task / ADR: 없음. TASK-021 이후 Task의 PR이 이 검사를 통과해야 한다

## Goal / 관련 요구사항

- Goal: 저장소 전체 이력과 추적 파일에 대한 비밀 검사를 CI에서 실행하고, 로컬 hook 설정을 저장소에 추적해 어느 환경에서도 같은 검사를 재현한다. 탐지 시 값은 출력하지 않는다.
- 사용자: 기여자(로컬 hook), 검수자(CI 결과)
- 사전조건: GitHub Actions ubuntu 러너, gitleaks 공개 릴리스
- 입력: 저장소 git 이력(`fetch-depth: 0`), staged 변경(로컬 hook)
- 업무규칙: (1) 기존 CI Job 두 개는 바꾸지 않는다. (2) gitleaks 바이너리는 버전과 sha256을 고정하고 불일치면 설치 단계에서 실패한다. (3) `--redact`로 값을 가린다. (4) allowlist 설정 파일(`.gitleaks.toml`)을 두지 않는다. (5) `.private-artifacts/`는 git 추적 대상이 아니므로 CI 검사 범위 밖이며 기존 "Reject private or generated files" 단계가 추적을 막는다.
- Out of Scope: `.private-artifacts/` HTML 안의 generic-api-key 패턴 3건 검토(사용자 판단), GitHub 설정 변경, 다른 도구 도입

## 요구사항과 범위

업무 문제: 저장소가 Public이지만 비밀 검사는 로컬 hook뿐이고 그 설정 파일이 미추적이라 재현되지 않으며 CI에는 내용 검사가 없다.
포함 범위: `.github/workflows/ci.yml`에 `secret-scan` Job 추가, `.pre-commit-config.yaml` 추적, DEVELOPMENT_RULES 한 줄, 이 문서.
제외 범위: README 변경(PR #41과 충돌 회피), 기존 Job 수정.
기존 자산: `.pre-commit-config.yaml`은 사용자가 작성한 미추적 파일이며 내용을 바꾸지 않고 그대로 추적한다. `gitleaks-system` hook은 로컬에 설치된 gitleaks 바이너리를 쓰므로 `rev`는 hook 정의만 고정한다.

## Acceptance Criteria (구현 전 고정)

결정자 / 판단 근거 / 검토 대상: 사용자 / 범위 승인 지시 / 이 브랜치

| ID | 입력/상황 | 기대 결과 | 검증 방법 | 결과/evidence |
|---|---|---|---|---|
| AC-01 | PR과 main push | `secret-scan` Job이 실행되고 기존 Job 2개의 설정 diff가 없다 | diff 검토, CI run | diff 확인(아래). CI run은 push 뒤 기록 |
| AC-02 | 설치 단계 | 바이너리 버전 8.30.1과 sha256 고정. 체크섬 불일치면 실패 | 로컬에서 같은 아카이브를 받아 잘못된 체크섬과 올바른 체크섬으로 `shasum -a 256 -c` | 불일치 exit 1, 일치 exit 0 |
| AC-03 | 현재 저장소 전체 이력 | 탐지 0건, Job 통과 | 로컬 `gitleaks git --redact --no-banner --exit-code 1 .` | 131 commits scanned, no leaks found, exit 0 |
| AC-04 | 탐지 능력 | scratchpad 임시 저장소에 시험용 패턴(생성된 더미 값)을 커밋하고 같은 명령 실행 → exit 1, 출력과 보고서의 값은 전부 REDACTED | 로컬 실행 | exit 1, 규칙 `aws-access-token`·`generic-api-key` 2건, `Secret == REDACTED` 전부 true. 더미 값은 기록하지 않았고 임시 저장소는 삭제함 |
| AC-05 | 로컬 hook | `.pre-commit-config.yaml`이 변경 없이 추적되고 `pre-commit run --all-files` 통과 | 로컬 실행 | `Detect hardcoded secrets ... Passed` |
| AC-06 | 비추적 자료 | `.private-artifacts/`는 checkout에 없고 기존 거부 단계가 그대로다 | diff 검토 | 기존 단계 무변경 |
| AC-07 | 문서 | 날짜·외부 제출 목적 없음 | diff 검토 | 확인 |

### 완료 기준 변경 이력

변경 없음.

## Implementation Plan

- 대안 비교: (a) gitleaks-action(SHA 고정): 설정이 짧지만 PR 댓글·SARIF artifact 업로드가 기본값이라 탐지 내용이 표면화될 수 있어 비활성 설정이 따로 필요하고, 조직 계정으로 옮기면 라이선스 키가 필요하다. (b) 바이너리 설치 + 체크섬: 로컬 hook과 같은 `gitleaks git` 명령을 쓰고 설치 무결성을 검증할 수 있으며 외부 action 의존이 없다. 버전은 손으로 올린다. **(b) 채택.**
- 검사 범위: `gitleaks git`은 커밋된 내용(전체 이력)을 본다. 작업 트리의 미추적 파일은 CI checkout에 없다. 로컬 hook은 `--staged`로 커밋 직전 변경만 본다.
- 트리거: 기존 workflow와 같은 pull_request, main push.

## Implementation Result

변경 파일: `.github/workflows/ci.yml`(Job 추가만), `.pre-commit-config.yaml`(신규 추적, 내용 동일), `docs/development/DEVELOPMENT_RULES.md`(한 문장), 이 문서.

| AC ID | 검증 대상 revision | 실행 명령/절차 | 환경/버전 | 결과 | evidence |
|---|---|---|---|---|---|
| AC-01, 06, 07 | 이 브랜치 작업 트리(base `c54c923`) | `git diff main -- .github/workflows/ci.yml` | 로컬 | 추가 줄만 있고 기존 Job 줄 변경 없음 | diff |
| AC-02 | 같음 | 아카이브 다운로드 뒤 `shasum -a 256 -c` | macOS, curl | 잘못된 값 FAILED(exit 1), 고정값 OK(exit 0) | 이 문서 |
| AC-03 | 같음 | `gitleaks git --redact --no-banner --exit-code 1 .` | gitleaks 8.30.1(darwin_arm64) | 131 commits, no leaks, exit 0 | 이 문서 |
| AC-04 | scratchpad 임시 저장소 | 같은 명령 + json 보고서 | 같음 | exit 1, 규칙 2종, 값 전부 REDACTED | 이 문서(값 미기록) |
| AC-05 | 이 브랜치 | `pre-commit run --all-files` | pre-commit 4.6.2 | Passed | 이 문서 |
| CI run | push 뒤 | GitHub Actions | ubuntu-latest | 미실행(push 미승인) | 추후 링크 |

## AI self-review

- 검사 범위: workflow YAML 파싱, 기존 Job 무변경, 체크섬 값이 공식 `gitleaks_8.30.1_checksums.txt`와 일치, 출력에 값이 없음.
- 발견과 처리: 첫 탐지 시험에서 임의 문자 집합이 규칙 정규식과 맞지 않아 0건이 나왔다. 규칙이 요구하는 문자 집합으로 더미를 다시 만들어 2건 탐지를 확인했다. 시험 자료는 삭제했다.
- 미해결 위험: CI 러너(linux_x64)에서의 실제 실행은 push 뒤 확인한다. gitleaks 버전 상향은 체크섬과 함께 수동으로 바꿔야 한다. `.private-artifacts/` HTML의 3건은 이 Task 범위 밖이며 사용자 검토가 남아 있다.

## 인간 검수와 Explainability Gate

검수자 / 검수 대상 revision / 결과: 판단 대기

## 결정 기록과 완료

최종 결정 / 결정자 / 승인 범위 / 검토 대상 PR: 판단 대기
