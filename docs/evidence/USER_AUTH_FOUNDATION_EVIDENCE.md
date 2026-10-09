# 사용자 인증·역할·보안 사건 검증 기록 (TASK-017a 첫 구현 PR)

ADR-014에 따라 Spring Security 세션 인증, 제어 DB, 합성 직원 3명, 활성 역할, 기존 조회 API 인증, 보안 사건 기록을 구현한 기록이다. 상담 건 표·객체 권한, AI 요청 승인(grant), Core → AI 서비스 호출은 두 번째 PR이며 이 기록에 없다.

## 검증 대상과 환경

| 항목 | 값 |
|---|---|
| 검증 대상 revision | 브랜치 `feat/user-auth-foundation` 작업 트리(base `c54c923`) |
| 명령 | `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :apps:core-service:test -PaiServiceIntegration=true --no-daemon`(연결 검증 포함), `python3 -m unittest discover -s tests -t .` |
| 환경 | macOS 27.0 aarch64, Java 21, Docker 28.3.3, Testcontainers PostgreSQL(`postgres@sha256:86c951e0…`). 보안 통합 테스트는 같은 컨테이너에 **별도 데이터베이스**(`trust_agent_control_test`)를 만들어 제어 DB로 쓴다 |
| 결과 | Java `203 tests, 203 passed, 0 failed, 0 skipped (SUCCESS)`(연결 검증 실제 실행, skip 0. 기존 185건 + 신규 18건: 보안 통합 14, 운영 기동 거부·분리 검사 4). Python 106건 통과(skip 2, 기존 비공개 snapshot 사유). 변경 없음 |

## 신규 테스트가 보장하는 것 (`SecurityIntegrationTest`, 14건)

| 테스트 | 보장(AC) |
|---|---|
| `unauthenticatedApiRequestsReturn401ProblemWithoutRedirect` | 세션 없는 세션·조회·검수자 경로 4개 전부 401 `application/problem+json`(`UNAUTHENTICATED`), `Location` 헤더 없음, 사건 기록(AC-01) |
| `loginIssuesSessionWithProtectedCookieAndRecordsEvent` | 로그인 200 JSON(principal, roles, activeRole STAFF, workspace main), `JSESSIONID` 쿠키 HttpOnly·SameSite=Lax, 응답에 비밀번호 없음, 재로그인 시 세션 ID가 바뀜(세션 고정 방지), LOGIN_SUCCESS 기록(AC-02) |
| `loginFailureIs401AndRecordedWithoutPassword` | 잘못된 비밀번호 401 `LOGIN_FAILED`, 세션 없음, LOGIN_FAILURE 기록에 비밀번호 없음 |
| `staffCannotReadReviewerPathAndReviewerCan` | STAFF의 검수자 경로 403 `FORBIDDEN`과 ACCESS_DENIED 기록, REVIEWER는 200, 조회 경로는 REVIEWER 활성도 인증·권한 통과, REVIEWER의 STAFF 전환 403 `ROLE_NOT_HELD`(AC-03·04) |
| `bothRolesUserSwitchesActiveRoleWithoutReloginAndSwitchIsRecorded` | 겸임 계정 기본 활성 STAFF, 보유해도 활성 아니면 403, 전환 뒤 200, ADMIN 400, ROLE_SWITCH 사건에 수행 역할(AC-03) |
| `roleSwitchIsNotAppliedWhenSecurityEventCannotBeRecorded` | 제어 DB의 `security_event` 표를 잠시 다른 이름으로 바꿔 기록 실패를 주입 → 전환 503 `SECURITY_EVENT_WRITE_FAILED`, 활성 역할은 이전 값(STAFF) 유지, 검수자 경로 403. 복구 뒤 전환 200과 기록 |
| `bodyActorFieldsAreIgnored` | 본문의 principal·roles 문자열은 무시되고 세션 principal만 쓰임(AC-06) |
| `stateChangingRequestWithoutCsrfTokenIsRejectedAndRecorded` | CSRF 헤더 없는 POST 403 `CSRF_REJECTED`와 기록 |
| `logoutInvalidatesSession` | 로그아웃 204, 이후 401, LOGOUT 기록 |
| `serviceTokenPathsIgnoreSessionsAndNeverRedirect` | Tool·기록 경로는 토큰 없으면 기존대로 401 JSON, 리다이렉트·세션 쿠키 없음, 토큰 있으면 401·403 아님(AC-07 공존, AC-11) |
| `loggedInSessionCannotSubstituteServiceTokenOnToolAndRecordPaths` | 로그인 세션으로 Tool·기록·allowlist 밖 Tool 경로를 토큰 없이 호출 → 전부 401. 토큰 필터(HIGHEST_PRECEDENCE+20·21)가 보안 체인(-100)보다 먼저 실행되어 permitAll로 우회되지 않음 |
| `managementHealthNeedsNoLogin` | 관리 포트 liveness가 401·403이 아님 |
| `controlTablesLiveOnlyInControlDatabaseAndDemoUsersHaveRoles` | 업무 DB에 `app_user`·`security_event` 없음, 제어 DB에 있음과 별도 Flyway history, 합성 사용자 3명과 역할, 해시는 bcrypt이고 평문 없음(AC-13) |
| `securityEventTableIsAppendOnly` | `security_event` DELETE가 append-only 트리거로 거부 |

그 밖에 `DemoFeatureProductionGuardTest`에 demo-users 운영 기동 거부, 같은 DB를 다른 URL 표기(포트 명시·대소문자·질의 문자열)로 가리켜도 거부(`CONTROL_DB_NOT_SEPARATED`), 같은 계정 거부(`CONTROL_DB_ACCOUNT_NOT_SEPARATED`) 사례를 더했고, 기존 조회 테스트 5개(공개 상품 조회, 적용 공문 조회 2개, Tool API의 조회 교차 확인, Core 전체 흐름)는 합성 STAFF 세션으로 로그인해 같은 단언을 유지한다. TASK-015 연결 검증(`AiServicePreparationIntegrationTest`)은 변경 없이 통과해 서비스 토큰 경로가 세션 도입과 공존함을 보인다.

## 판단과 한계

- **제어 DB 로컬 기본값(개발 편의용, 조건부).** 로컬 기본은 업무 DB 설정으로 대체되며 같은 데이터베이스 안에 별도 Flyway history(`flyway_control_schema_history`, baseline 0)로 제어 표가 만들어진다. 기존 통합 테스트 수정을 최소화하기 위한 선택이며, `prod` profile은 `TRUST_AGENT_CONTROL_DB_*` 세 설정을 요구하고 업무 DB와 같은 URL이면 기동을 거부한다. 보안 통합 테스트는 실제 별도 데이터베이스로 검증했다. 로컬 단일 DB 구성은 운영 분리 검증을 대신하지 않으며, 체험 공간 템플릿(TASK-026)은 제어 표가 없는 업무 DB에서 만들어 제어 표가 복제되지 않게 해야 한다(TASK-026 AC로 연결).
- **역할 전환의 fail-closed.** 활성 역할은 세션에 먼저 바꾼 뒤 사건을 기록하고, 기록이 실패하면 이전 역할로 되돌리고 503으로 답한다. 감사 기록 없이 바뀐 역할이 남지 않는다.
- **CSRF 토큰 회전.** 로그인 성공 시 Spring Security가 토큰을 회전하므로 클라이언트는 로그인 뒤 한 번 조회해 새 `XSRF-TOKEN` 쿠키를 받아야 한다(화면은 자연히 그렇게 한다). 테스트 클라이언트에 반영했다.
- **구현되지 않은 것(완료로 표시하지 않음).** 상담 건 표와 객체 권한 검사, grant, Core → AI 서비스 호출, AI 서비스 수신 토큰, 절대 세션 만료(24시간), 서버 측 세션 저장소, 요청 제한, OIDC. 이 PR의 인증은 합성 계정 기반이며 실제 행원 인증이 아니다.
- **의존성 추가.** `spring-boot-starter-security`(Boot BOM 4.1.1 → Spring Security 7.1.1). `gradle.lockfile`과 `verification-metadata.xml`에 체크섬을 기록했다.
