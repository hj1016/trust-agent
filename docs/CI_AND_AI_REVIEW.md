# CI와 AI 리뷰 운영 기준

## 목적

Pull request마다 반복 가능한 자동 검증을 실행하고, 테스트와 사람 검토를 보완하는 Claude 리뷰를 사용합니다. Claude 의견은 참고 자료이며 merge 승인이나 테스트 결과를 대신하지 않습니다.

## CI 검증

`.github/workflows/ci.yml`은 pull request와 `main` push에서 다음 검증을 실행합니다.

- Python 3.11 dependency 설치
- 비공개 raw HTML이 없는 Public Git 조건의 contract 및 unit test
- 모든 contract와 dataset JSON 문법 검사
- Python source compile 검사
- raw HTML, 비공개 artifact, 환경 파일과 생성 파일의 Git 유입 차단

GitHub branch protection 또는 ruleset에는 `Python contracts` check를 필수로 지정합니다. Spring Boot 프로젝트가 추가되면 같은 CI에 Gradle test를 별도 필수 job으로 추가합니다.

## Claude 자동 리뷰

`.github/workflows/claude-pr-review.yml`은 다음 시점에 Draft가 아닌 pull request를 검토합니다.

- Pull request를 처음 열었을 때
- Draft pull request를 Ready for review로 전환했을 때
- 닫힌 pull request를 다시 열었을 때

작업 중 push마다 자동 리뷰하지 않습니다. 수정 후 재검토가 필요하면 pull request 댓글에 `@claude`와 요청 내용을 작성합니다. `.github/workflows/claude-mention.yml`은 저장소 write 권한이 있는 사용자의 mention에만 응답하며 코드 수정 권한은 갖지 않습니다.

## GitHub 설정

### Claude 인증 secret

다음 두 방식 중 하나를 선택해 Repository의 `Settings > Secrets and variables > Actions`에 secret을 추가합니다.

```text
ANTHROPIC_API_KEY
```

Claude Pro 또는 Max의 OAuth token을 사용하면 로컬에서 `claude setup-token`을 실행한 뒤 다음 이름으로 등록합니다.

```text
CLAUDE_CODE_OAUTH_TOKEN
```

두 값을 모두 등록할 필요는 없습니다. Secret 값을 저장소 파일, issue, pull request, action log에 입력하지 않습니다. Fork pull request에는 GitHub가 repository secret을 전달하지 않으므로 Claude review를 실행하지 않습니다.

Workflow는 별도 GitHub App 대신 실행할 때마다 발급되는 `GITHUB_TOKEN`을 사용합니다. 이 token은 workflow에 선언한 repository 읽기와 pull request 및 issue comment 작성 권한만 가집니다.

### Main 보호 규칙

CI workflow가 한 번 실행된 후 `main` ruleset에 다음 기준을 적용합니다.

- Pull request를 통한 변경만 허용
- `Python contracts` status check 필수
- Merge 전 branch 최신화 권장
- Force push와 branch 삭제 차단
- Claude review는 필수 approval로 지정하지 않음

현재 private repository 요금제에서는 branch protection API를 사용할 수 없습니다. Public repository 전환 직후 ruleset을 활성화하고, 전환 전에는 CI 결과를 직접 확인한 뒤 merge합니다.

## 권한과 안전 기준

- CI workflow는 `contents: read`만 사용합니다.
- Claude workflow는 repository content 읽기와 pull request 및 issue comment 작성만 허용합니다.
- `contents: write` 권한을 부여하지 않아 Claude가 code commit이나 branch push를 수행하지 못하게 합니다.
- 별도 GitHub App이나 장기 GitHub token 대신 workflow 단위의 단기 `GITHUB_TOKEN`을 사용합니다.
- 외부 pull request의 code로 secret 권한을 실행할 수 있는 `pull_request_target`을 사용하지 않습니다.
- Action dependency는 검증한 commit SHA로 고정합니다.
- AI review 결과와 무관하게 테스트 통과와 사람의 최종 검토를 merge 조건으로 유지합니다.

## 권장 작업 흐름

1. Feature branch 생성
2. Draft pull request 생성
3. 구현과 local test 후 push
4. CI 통과 확인
5. Ready for review 전환
6. Claude review 확인
7. 지적 수정 후 필요하면 `@claude`로 재검토 요청
8. 사람이 변경 파일과 evidence를 최종 확인
9. Squash and merge
