# CI와 리뷰 운영 기준

## 목적

Pull request마다 반복 가능한 자동 검증을 실행하고, 테스트 결과와 사람의 최종 검토를 merge 기준으로 사용합니다.

## CI 검증

`.github/workflows/ci.yml`은 pull request와 `main` push에서 다음 검증을 실행합니다.

- Python 3.11 dependency 설치
- 비공개 raw HTML이 없는 Public Git 조건의 contract 및 unit test
- 커밋된 공개·정제 audit baseline의 schema와 참조 무결성 검사
- 모든 contract와 dataset JSON 문법 검사
- Python source compile 검사
- raw HTML, 비공개 artifact, 환경 파일과 생성 파일의 Git 유입 차단

GitHub branch protection 또는 ruleset에는 `Python contracts` check를 필수로 지정합니다. Spring Boot 프로젝트가 추가되면 같은 CI에 Gradle test를 별도 필수 job으로 추가합니다.

Public CI에서는 raw HTML이 필요한 artifact hash, golden 재추출과 migration 재현 테스트만
명시적으로 skip합니다. 로컬 private 검증에서는
`TRUSTAGENT_REQUIRE_PRIVATE_SNAPSHOTS=1`을 사용해 이 세 테스트도 필수로 실행합니다.
Live KB 페이지를 CI에서 다시 수집하지 않으며, 커밋된 합성 fixture와 공개·정제
baseline으로 결정적인 검증을 유지합니다.

## AI 자동 리뷰 상태

Claude Code Action의 OAuth token 인증이 GitHub Actions 환경에서 실패하는 호환 문제를 확인했습니다. 별도 Anthropic API 비용을 사용하지 않는 프로젝트 기준에 따라 Claude 자동 리뷰 workflow와 mention workflow는 활성화하지 않습니다.

- CI와 사람 검토만 현재 merge 기준으로 사용
- 로컬 AI 리뷰는 필요할 때 별도로 실행
- OAuth 호환 문제가 해결되고 추가 비용 없이 사용할 수 있을 때 자동 리뷰 재검토
- 재도입 시 repository 읽기와 pull request comment 작성만 허용
- `contents: write`와 `pull_request_target` 미사용

사용하지 않는 OAuth token은 GitHub Actions secret에서 제거합니다.

## GitHub 설정

### Main 보호 규칙

CI workflow가 한 번 실행된 후 `main` ruleset에 다음 기준을 적용합니다.

- Pull request를 통한 변경만 허용
- `Python contracts` status check 필수
- Merge 전 branch 최신화 권장
- Force push와 branch 삭제 차단
- AI review를 필수 approval로 지정하지 않음

현재 private repository 요금제에서는 branch protection API를 사용할 수 없습니다. Public repository 전환 직후 ruleset을 활성화하고, 전환 전에는 CI 결과를 직접 확인한 뒤 merge합니다.

## 권한과 안전 기준

- CI workflow는 `contents: read`만 사용합니다.
- Action dependency는 검증한 commit SHA로 고정합니다.
- 테스트 통과와 사람의 최종 검토를 merge 조건으로 유지합니다.

## 권장 작업 흐름

1. Feature branch 생성
2. Draft pull request 생성
3. 구현과 local test 후 push
4. CI 통과 확인
5. Ready for review 전환
6. 사람이 변경 파일과 evidence를 최종 확인
7. Squash and merge
