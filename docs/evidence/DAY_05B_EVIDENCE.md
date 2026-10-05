# 다섯째 날 B단계 검증 기록

> 이 문서는 PostgreSQL 18.6 Testcontainers 기준의 Pre-SDLC 증거다. Core 업무 DB는 PostgreSQL 유지로 결정됐다(ADR-009). 본문의 결과는 AI-native SDLC 도입 이전 검증 기록이며 현재 기준의 인간 검수 통과를 뜻하지 않는다.

## 구현 범위

Day 5b에서는 합성 내부 공문 family를 `businessDate`와 `knownAt` 두 축으로 조회하는
Core service endpoint를 구현했습니다. 시스템이 해당 시점까지 받은 공문만 대상으로
시행 기간과 철회 사건을 확인하고, 명시적인 supersedes chain으로 적용 공문을 선택합니다.

변경 proposal 생성, 자동 validation, 사람 검토와 새 checklist 승인은 Day 5c 범위이므로
완료로 처리하지 않습니다. Day 5c 데이터가 없을 때 `internalChecklistUseAllowed`를 true로
만들지 않습니다.

## 서비스에서 담당하는 역할

- `businessDate`: 어느 업무일에 적용되는 공문과 checklist인지 결정
- `knownAt`: 그 순간까지 시스템이 실제로 받은 공문, 추출 결과와 schedule만 노출
- 적용 공문 선택: 시행 기간, 철회 여부와 supersedes chain을 함께 판단
- checklist 준비 상태: 공문 선택과 별도로 extraction, validation 대기 또는 승인 schedule
  존재 여부 반환
- fail-closed 응답: 중첩 후보를 임의 선택하지 않고 candidate ID와 차단 원인 반환

조회 endpoint는 다음과 같습니다.

```text
GET /api/v1/internal-policy/checklists/{familyId}/applicable
    ?businessDate=YYYY-MM-DD
    &knownAt=RFC3339 instant
```

## 필요한 이유

시행일만 사용하면 시스템이 아직 받지 못한 공문을 과거에 알고 있었던 것처럼 노출할 수
있습니다. 수신 시각만 사용하면 미래 시행 공문이 너무 일찍 적용됩니다. 두 축을 분리해야
특정 업무일에 적용되는 규칙과 특정 시점에 실제로 알 수 있었던 기록을 함께 재현할 수
있습니다.

## 주요 결정과 검증

### 적용 공문 선택

다음 네 조건을 모두 만족한 공문만 후보로 사용합니다.

1. `receivedAt <= knownAt`
2. 발행된 공문이고 `knownAt`까지 보이는 철회 사건이 없음
3. `effectiveFrom <= businessDate`
4. `effectiveTo`가 없거나 `businessDate < effectiveTo`

한 후보가 다른 모든 후보를 끊김 없는 `supersedesNoticeId` chain으로 잇는 경우에만 chain의
마지막 공문을 선택합니다. 후보가 관계없거나 visible 후보 사이에서 chain이 끊기면
`200`, `noticeSelectionStatus=AMBIGUOUS`, `AMBIGUOUS_EFFECTIVE_NOTICE`와 candidate notice
ID를 반환합니다. version 숫자나 수신 시각이 크다는 이유로 임의 선택하지 않습니다.

### 시간축과 사용 차단

- `knownAt`을 생략하면 요청 시작 시 한 번 읽은 `evaluatedAt`을 사용합니다.
- 미래 `knownAt`은 `400 FUTURE_KNOWN_AT_NOT_ALLOWED`로 거부합니다.
- 과거 `knownAt`은 조회할 수 있지만 `HISTORICAL_KNOWN_AT`으로 현재 사용을 차단합니다.
- 미래 `businessDate`는 계획 조회를 위해 허용하지만 `FUTURE_BUSINESS_DATE`로 현재 사용을
  차단합니다.
- 소급 시행 공문은 실제 receipt 전에는 보이지 않으며 receipt 후에는
  `RETROACTIVE_NOTICE` warning을 반환합니다.
- 날짜와 시각은 문자열이 아니라 `LocalDate`와 `Instant`로 비교합니다.

### Schedule revision 선택

조회는 `createdAt <= knownAt`인 schedule revision만 대상으로 visible leaf를 선택합니다.
승인 checklist의 `createdAt`도 `knownAt`으로 제한합니다. 따라서 나중에 만들어진
checklist나 schedule이 과거 조회에 나타나지 않습니다.

선형성은 V4의 family별 root partial unique와 `supersedesScheduleRevisionId` unique 및
foreign key가 DB의 모든 쓰기 경로에서 보증하므로 서비스가 chain 전체를 다시 검증하지
않습니다. 대신 visible leaf가 둘 이상이면 migration 또는 DB 무결성이 깨진 것으로 보고
`POLICY_INTEGRITY_VIOLATION`으로 중단합니다. 이는 DB 제약을 우회한 환경에서 조용히 한
schedule을 선택하지 않기 위한 방어입니다.

선택된 새 공문과 연결된 checklist가 없으면 이전 공문의 checklist를 대신 반환하지 않고
`PENDING_VALIDATION`을 반환합니다. Day 5c에서 validation freshness, 현재 공개 evidence와
semantic match를 연결하기 전에는 승인 schedule fixture가 있어도 현재 업무 사용은
허용하지 않습니다.

### 오류 응답

잘못된 날짜와 시각, 미래 `knownAt`, 등록되지 않은 family는 안정적인 오류 code와 trace ID를
반환합니다. DB 장애와 정책 데이터 무결성 오류는 원인을 응답에 노출하지 않고 각각
`DATABASE_UNAVAILABLE`, `POLICY_INTEGRITY_VIOLATION`으로 구분합니다.

## 검토한 대안

- 가장 큰 version 선택: 수신되지 않았거나 관계없는 공문을 적용할 수 있어 제외
- 가장 늦게 받은 공문 선택: 시행일과 supersession 의미를 무시하므로 제외
- `businessDate`만 받는 조회: 과거 지식 상태를 재현할 수 없어 제외
- 중첩 시 HTTP 409: 클라이언트 요청 오류가 아니라 데이터 상태이므로 200 상태 응답 선택
- schedule chain을 애플리케이션에서도 전수 재검증: DB가 선형성을 강제하므로 중복 구현
  대신 visible leaf cardinality만 fail-closed로 확인
- 이전 승인 checklist fallback: 새 공문의 미검증 상태를 감춰 잘못된 확정을 만들 수 있어
  제외

## 테스트 evidence

검증 대상 revision: 커밋 `a6149bb` 이후의 미커밋 작업 트리. 환경: 로컬 macOS, Java 21, PostgreSQL 18.6 Testcontainers(Docker). 로컬 실행 결과입니다.

```text
./gradlew test bootJar --offline --no-daemon
BUILD SUCCESSFUL
81 tests completed
Spring Boot executable jar 생성 성공
```

Day 5b 통합 테스트 7개는 실제 PostgreSQL과 HTTP endpoint를 사용해 다음을 검증합니다.

- 시행 직전, 당일과 종료일 `[)` 경계
- v2 수신 전 비노출과 수신 후 선택
- 철회 사건 전후 상태
- 끊긴 chain의 `AMBIGUOUS`와 candidate notice ID
- 소급 시행 공문의 receipt 전 비노출과 receipt 후 warning
- 같은 업무일에서 `knownAt`에 따른 root/후속 schedule revision 선택
- 새 공문에 승인 checklist가 없을 때 이전 checklist fallback 금지
- 미래 업무일과 과거 지식 시점의 명시적 사용 차단
- 미래·잘못된 입력과 미등록 family의 안정적인 오류 code 및 trace ID

Day 5a 검토에서 권장된 공통 `AppendOnlyBootstrapChecks.verifyExactCounts`의 정확히 일치,
초과, 미달 경계도 직접 단위 테스트 3개로 고정했습니다.

Python 계약과 공개 데이터 파이프라인은 변경하지 않았으며 전체 회귀 검증 결과는 다음과
같습니다.

```text
python3 -m unittest discover -s tests
Ran 57 tests
OK

TRUSTAGENT_PRIVATE_ARTIFACT_ROOT=/tmp/trust-agent-public-git-no-artifacts \
  python3 -m unittest discover -s tests
Ran 57 tests
OK (skipped=3)
```

## 위험과 제한사항

- 실제 은행 내부 공문, 인증, 권한과 전자결재를 사용하지 않습니다.
- 영업일 holiday calendar는 포함하지 않고 명시적인 Asia/Seoul 달력 날짜를 사용합니다.
- Day 5c 전에는 validation, review decision과 공개 근거 재평가가 없어 현재 업무 사용을
  허용하지 않습니다.
- 승인 schedule fixture는 선택 경계를 검증하기 위한 합성 테스트 데이터이며 baseline에
  승인 결과를 미리 커밋하지 않습니다.
- 인증, 백업, 복구, 고가용성과 SLO가 없어 운영 준비 완료가 아닙니다.

## 다음 작업과의 연결

Day 5c는 선택된 공문의 rule과 직전 승인 checklist를 비교해 proposal revision을 만들고,
공개 상품 evidence validation과 사람의 승인·수정·반려 기록을 연결합니다. 승인된 결과만
새 immutable checklist와 schedule revision으로 추가합니다.
