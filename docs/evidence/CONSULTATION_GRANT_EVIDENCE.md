# 상담 건·AI 요청 승인(grant)·신청 자료 Core 적재 검증 기록 (TASK-017a 두 번째 PR)

ADR-014 5·7·8·9·9-1항과 TASK-017a 계획(A안)의 구현 기록이다. 화면(017b)과 체험 공간(026)은 없다.

## 검증 대상과 환경

| 항목 | 값 |
|---|---|
| 검증 대상 revision | 브랜치 `feat/consultation-and-grant` 작업 트리(base main `677b2b3`) |
| 명령 | `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :apps:core-service:test -PaiServiceIntegration=true --no-daemon`, `python3 -m unittest discover -s tests -t .` |
| 환경 | macOS aarch64, Java 21, Docker 28.3.3, Testcontainers PostgreSQL(제어 DB는 같은 컨테이너의 별도 데이터베이스), AI 서비스는 venv의 uvicorn 실제 기동(연결 검증) |
| 결과 | Java 233건 중 232 통과, 0 실패, 1 skip(TASK-021 계측 runner `AiServiceCallMetricsRunner`는 `-PaiCallMetrics=true`일 때만 실행). Python 117건 통과, 2 skip(기존) |

## 변경 범위

- 업무 DB V11: `synthetic_work_company`, `synthetic_work_application`(canonical `source_hash`), `consultation`. 보호 목록·가드 트리거·권한. schema expected-version 11.
- 제어 DB V2: `ai_request_grant`(상태 ISSUED/CONSUMING/CONSUMED/EXPIRED, TTL·읽기 상한 기록, 기록 시작 때의 `record_run_id`·`record_preparation_id`, CONSUMING이면 run_id 필수), `ai_request_grant_use`(append-only).
- Core: `consultation/`(적재기·runner, 저장소·서비스·컨트롤러·예외 처리), `grant/`(설정·서비스·예외), `ai/`(Core → AI 서비스 클라이언트), Tool 서비스와 기록 컨트롤러의 grant 검사, 기록 경로의 신청 해시 대조, prod 필수 설정(AI 서비스 주소·수신 토큰, require-grant 강제), 보안 경로 `/api/v1/consultations/**`(STAFF 활성).
- AI 서비스: 수신 토큰 검사, `grantId` 수신과 헤더 전달, CLI `--grant-id`.
- 테스트: `ConsultationGrantIntegrationTest`(12), `AiGrantEndToEndIntegrationTest`(2, 연결 검증), 기존 기록 테스트 2개의 신청 해시를 등록 해시로, 운영 기동 거부 1건, Python 1건(수신 토큰·헤더 전달).

## 테스트가 보장하는 것

| 테스트 | 보장(AC) |
|---|---|
| `syntheticApplicationsAreRegisteredWithSourceHashAndReloadIsIdempotent` | 합성 신청·기업 1건씩 등록, 해시 기록, 재실행은 skip 2·삽입 0(AC-16) |
| `consultationRequiresRegisteredApplicationAndIsVisibleOnlyToOwner` | 미등록 신청 422와 행 미생성, 생성 201(담당자·상품·workspace), 타 사용자 404와 보안 사건, REVIEWER의 생성 403(AC-05·16) |
| `toolPathRequiresGrantTogetherWithServiceTokenAndEnforcesScope` | 토큰만 401 `GRANT_REQUIRED`, grant만 401, 둘 다 200, 다른 공문군·다른 상담 ID·모르는 grant 403, 읽기 상한(5) 초과 403 `GRANT_EXHAUSTED`와 사용 기록, Tool 감사 5건(AC-07·08) |
| `businessDateMustMatchGrantForToolAndRecord` | (병합 전 보완) grant 업무일과 다른 업무일 Tool 403 `GRANT_SCOPE_MISMATCH`, 업무일 생략도 403(생략 시 Tool이 오늘로 채우므로), 거부는 읽기 횟수 미증가. 다른 업무일 기록 403, grant ISSUED 유지, 실행 기록 없음. 맞는 업무일 기록은 201 |
| `workspaceIsCheckedOnToolAndRecordAndNullIsRejected` | (병합 전 보완) 다른 workspace grant로 Tool·기록 403 `WORKSPACE_UNAVAILABLE`. 서비스 계층에 workspace null을 넘기면 우회가 아니라 `WORKSPACE_UNAVAILABLE` |
| `expiredGrantIsRefusedAfterTtl` | 고정 시계 61초 뒤 403 `GRANT_EXPIRED`, 상태 EXPIRED(AC-08) |
| `recordPathChecksApplicationHashAndConsumesGrantOnce` | 변조 해시 422 `APPLICATION_SOURCE_MISMATCH`와 grant ISSUED 복귀, 헤더 없음 401, 기록 201과 CONSUMED·사용 기록, 재사용 409 `GRANT_CONSUMED`, 소비된 grant의 Tool 호출 409(AC-08·12·16) |
| `concurrentRecordsWithTheSameGrantLetOnlyOneThrough` | 같은 grant 동시 기록 두 건 → 하나만 통과(201 또는 ALREADY_RECORDED 200), 다른 하나 409, 실행 기록 1건(AC-08) |
| `consumedUpdateFailureAfterCommitKeepsGrantUnusableAndIsSettledByReconciliation` | **실제 장애 주입**(제어 DB 트리거가 그 grant의 CONSUMED 갱신을 실패시킴): 응답 201, grant CONSUMING 유지(ISSUED 아님), 업무 기록 1건. 같은 grant 재전송 409 `GRANT_CONSUMED`, 새 grant + 같은 run_id 409 `RUN_ID_CONFLICT`(새 grant는 ISSUED 복귀), 새 grant + 새 run_id 200 `ALREADY_RECORDED`, 업무 기록 여전히 1건. 만료 전 대조는 건드리지 않고, 만료 뒤 대조가 run_id로 CONSUMED 정리, 두 번째 대조는 다시 정리하지 않음(AC-12, ADR-014 9-1 두 번째 행) |
| `runAuditFailureAfterCommitDoesNotReleaseGrant` | **실제 장애 주입**(업무 DB 트리거가 성공 실행 기록 INSERT를 실패시킴): 준비안은 커밋, 응답 500 `FAILURE_AUDIT_WRITE_FAILED`, grant CONSUMING(`RECORD_UNCERTAIN`), `RECORD_RELEASE` 없음, 재사용 409. 만료 뒤 대조가 준비안 행(같은 상담 건, 발급 뒤 기록)으로 커밋을 확인해 CONSUMED. 수정 전 동작(모든 예외에서 ISSUED 복귀)을 되살리면 이 테스트가 `expected CONSUMING but was ISSUED`로 실패함을 확인 |
| `uncommittedConsumingGrantExpiresOnReconciliation` | 기록이 없는 CONSUMING 고아(Core 재시작 흉내)는 만료 뒤 대조에서 EXPIRED(ISSUED 아님) |
| `preparationRequestIssuesGrantAndFailsClosedWhenAiServiceIsDown` | AI 서비스 없음 → 502 `AI_SERVICE_UNAVAILABLE`, 응답에 grant ID 헤더·본문 없음, grant는 ISSUED로 남아 만료, 잘못된 업무일 400 |
| `AiGrantEndToEndIntegrationTest.staffSessionGetsPreparationThroughCoreIssuedGrant` | 세션 → 상담 건 → Core grant → uvicorn AI 서비스(수신 토큰) → Tool 5회·기록 1회(헤더) → PARTIAL 준비안 200, 응답에 grant ID 헤더·본문 없음(grant는 제어 DB에서 확인), grant 업무일 2026-10-06, CONSUMED, 읽기 5회, 토큰 비노출, 재요청은 새 grant와 ALREADY_RECORDED(AC-09·10) |
| `aiServiceRejectsCallsWithoutInboundToken` | 수신 토큰 없는 직접 호출 401(AC-10) |
| Python `test_inbound_token_is_required_when_configured_and_grant_id_is_forwarded` | 토큰 없음·불일치 401과 Core 미호출, 일치 시 6회 호출 전부에 grant 헤더, 본문에 grant 없음, 토큰 미설정 모드에서는 헤더 없음 |

## 판단과 한계

- **병합 전 보완(사용자 검토).** (1) 업무일: `applicable_checklist` Tool의 `businessDate`와 기록 본문의 `business_date`가 grant 업무일과 같아야 한다(다르거나 생략이면 403 `GRANT_SCOPE_MISMATCH`). `rule_evidence`는 업무일 인자가 없고 Core 시계 기준이라 대조하지 않는다. (2) 브라우저 응답: 준비안 요청 응답에서 `X-TrustAgent-Grant` 헤더를 뺐다. grant ID는 Core와 AI 서비스 사이에서만 오간다. (3) workspace: Tool·기록 경로가 workspace를 null로 넘겨 검사를 건너뛰던 것을 Core가 서비스하는 workspace(`main`)로 대조하도록 고쳤고 null은 거부한다. 새 오류 코드와 설계 변경은 없다.

- **이중 원본.** AI 서비스는 여전히 합성 JSON을 직접 읽는다. 기록 경로의 해시 대조가 불일치를 막지만, 최종적으로 AI 서비스가 Core API로 신청 정보를 받는 구조는 후속 Task다.
- **grant 검사 위치.** Tool 경로는 토큰 필터 뒤 `ToolService.call`에서, 기록 경로는 컨트롤러에서 검사한다. 헤더를 읽기 전에 본문을 파싱해야 범위를 알 수 있기 때문이며, 필터에서 끝나는 토큰 검사와 달리 감사(`tool_call_audit`, `ai_request_grant_use`)에 거부 사유가 남는다.
- **grant 반환(ISSUED 복귀) 조건.** 기록 실패 뒤 grant 처리는 기록 컨트롤러가 정한다. (1) 업무 트랜잭션 전 또는 트랜잭션 안에서 던져 롤백되는 오류(허용 목록 `PRE_COMMIT_CODES`: 입력·매핑·재확인 거부, `RUN_ID_CONFLICT`, `PREPARATION_CONFLICT`, `SERIALIZATION_FAILED`, 신청 불일치)만 ISSUED로 되돌린다. (2) 커밋 뒤에 날 수 있는 `FAILURE_AUDIT_WRITE_FAILED`, 커밋 결과를 알 수 없는 `RECORD_WRITE_FAILED`, 예상 밖 예외, 목록에 없는 새 코드는 업무 DB에서 이번 run_id의 성공 실행 기록을 찾아 있으면 CONSUMED, 없거나 조회가 실패하면 CONSUMING으로 둔다. (3) 커밋 뒤 CONSUMED 갱신 실패는 CONSUMING으로 남긴다. 어느 경우에도 대조는 ISSUED로 돌리지 않고 CONSUMED(사후) 또는 EXPIRED로 끝낸다.
- **첫 구현의 결함(이번에 수정).** 처음 구현은 기록 서비스의 모든 예외에서 grant를 ISSUED로 되돌렸다. 업무 트랜잭션 커밋 뒤 성공 실행 기록 쓰기가 실패하면(`FAILURE_AUDIT_WRITE_FAILED`) 준비안이 저장됐는데도 grant가 재사용 가능해지는 문제였다. 중복 업무 기록은 run_id PK·preparation_id 멱등이 막지만 grant의 "기록 1회" 통제가 깨진다. 위 규칙과 주입 테스트로 고쳤다.
- **대조 runner의 상태.** 대조 메서드(`GrantService.reconcile`)와 업무 DB 조회(`PreparationRecordOutcomeLookup`), **수동** runner(`--trust-agent.grant-reconcile.enabled=true`)까지 구현했다. **주기 실행(스케줄)은 없다.** 따라서 커밋 뒤 갱신 실패나 Core 재시작으로 남은 CONSUMING grant는 사람이 runner를 실행하기 전까지 CONSUMING으로 남는다(재사용은 막힘, fail-closed). 주기 실행과 Core 재시작 복구 검증은 ADR-014 9-1항대로 TASK-026 범위다.
- **주입하지 않은 장애.** 커밋 결과를 알 수 없는 연결 단절(커밋 요청 뒤 응답 전 단절)은 주입하지 않았다. 이 경우 서비스는 `RECORD_WRITE_FAILED`를 던지고 컨트롤러는 불확실로 처리해 CONSUMING을 유지하는 코드 경로를 타지만, 그 경로를 실제 단절로 재현한 테스트는 없다. 업무 DB 조회 실패 분기도 테스트로 재현하지 않았다.
- **미구현(완료로 표시하지 않음).** 화면, 체험 코드·workspace, 절대 세션 만료, 요청 제한, AI 서비스가 Core API로 신청을 받는 구조.
