# 근거 검색 색인 재색인 검증 기록 (TASK-016 첫 구현 PR)

ADR-013에 따라 승인된 근거만 Elasticsearch에 색인하는 재색인 runner와 색인 불변식·멱등성 검증의 기록이다. 검색 API, 평가, 관련성 보류 기준은 이 PR에 없다(다음 PR).

## 검증 대상과 환경

| 항목 | 값 |
|---|---|
| 검증 대상 revision | 브랜치 `feat/search-index-reindex` 작업 트리(base `c54c923`) |
| 명령 | `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :apps:core-service:test -PaiServiceIntegration=true --no-daemon`(연결 검증 포함) |
| 환경 | macOS 27.0 aarch64, Java 21, Docker 28.3.3, Testcontainers PostgreSQL(`postgres@sha256:86c951e0…`)과 Elasticsearch 8.19.6(`docker.elastic.co/elasticsearch/elasticsearch@sha256:bfb21080…`, 보안 활성, HTTP TLS 없음, 비밀번호는 실행 중 생성) |
| 결과 | 196건 전부 통과, 실패 0, skip 0(연결 검증 실제 실행). 기존 185건 유지, 신규 11건(통합 7, 단위 4) |

## 신규 테스트가 보장하는 것

| 테스트 | 보장 |
|---|---|
| `SearchReindexIntegrationTest.reindexIndexesOnlyApprovedChecklistRules` | TASK-015 상태(중도상환수수료 v2 승인, 셀러론 v2 검토 대기)에서 재색인 → 문서 3개. 문서 ID 집합 = 사람 결정(APPROVE)이 있는 승인 checklist 항목의 규칙 version 집합(DB 질의로 계산). FIXTURE 출처 항목의 규칙(v1 수수료율 포함)과 미승인 규칙(셀러론 v2)은 색인에 없음. `_meta.content_hash`·`workspace_id`·`reindex_version` 기록. 접두사 색인은 하나뿐 |
| `reindexAgainIsIdempotent` | 두 번째 실행은 `ALREADY_CURRENT`, 같은 색인 이름, 문서 수 3 불변, alias 불변, 색인 수 1 |
| `documentFieldsStayWithinToolScopeAndSourceHashIsDeterministic` | 문서 필드 집합이 정의된 16개와 정확히 같음(공문 본문·변경안·검수자 ID 없음), `dataset_class=SYNTHETIC_INTERNAL`, `synthetic=true`, 시행 기간이 승인 일정과 일치 |
| `approvingAnotherFamilyAddsItsRulesAndReplacesIndex` | 셀러론 v2 승인 뒤 재색인 → 두 공문군 규칙이 모두 있고 옛 색인은 삭제, alias는 새 색인 |
| `withdrawnNoticeRulesDisappearAfterReindex` | 중도상환수수료 v2 철회 사건 뒤 재색인 → 그 공문의 규칙이 사라지고 셀러론만 남음 |
| `failedReindexKeepsCurrentIndexAndAliasAndRemovesPartialIndex` | 내용이 바뀐 상태에서 refresh 단계 실패를 주입 → 예외, alias와 기존 색인·문서 수 그대로, 부분 색인 삭제. 이후 정상 재색인은 새 색인으로 교체 |
| `readOnlySearchUserCannotWriteButCanSearch` | 검색 사용자(읽기 전용 역할)의 문서 쓰기 403, 색인 생성 403, 검색 200 |
| `SearchDocumentsTest`(4건) | 구조화 값 문장화의 결정성과 null 처리, 내용 해시가 `indexed_at`과 무관하고 내용 변경에 반응, 중복 규칙 version은 시행일이 늦은 쪽 유지, 색인 정의가 strict mapping과 요청 분석기 사용 |

## 보장 조건 대응 (ADR-013 표)

- 승인 항목 근거만, 구버전·FIXTURE 제외: **색인 불변식**. 위 첫 번째·네 번째·다섯 번째 테스트가 양성·음성 사례를 확인한다.
- 요청 공문군만, 현재 적용 공문만: **질의 시점 필터**. 색인 문서에 `family_id`, `effective_from/to`가 있고 필터 적용과 측정은 검색 API PR에서 한다. 이 PR은 필드 존재만 보장한다.
- 재색인 사이 상태 변화: **Core 재확인**. 검색 API PR 범위.

## 변경 범위

Core `search/` 패키지(설정, ES 클라이언트, 색인 대상 조회, 문서 생성, 재색인 서비스·runner·설정), `application.yml`·`application-prod.yml`의 `trust-agent.search`, prod 필수 설정에 ES 자격증명(재색인 켤 때), `infra/docker/compose.search.yml`과 `search/init-users.sh`, README 2곳, 테스트 helper `PreparationScenario`의 가시성을 public으로(내용 변경 없음). Core 승인·일정·변경안·검증 로직, Tool allowlist, 업무 DB 스키마 변경 없음. Gradle 의존성 추가 없음(ES 호출은 표준 HttpClient).

## 시점별 조회 요구와의 관계(설계 확인)

색인 대상 SQL은 공문군의 **최신 일정 revision에 있는 모든 항목**을 포함한다. 사람 결정이 있는 승인 버전이 새 승인으로 대체되면 이전 항목은 `effective_to`가 닫힌 채 최신 revision에 남으므로(HumanReviewService의 일정 revision 생성 방식), 그 과거 승인 버전의 규칙도 색인에 남고 `effective_from/to`로 업무일 필터를 받는다. 즉 "정상 승인된 과거 버전"은 삭제하지 않는다. 제외되는 것은 사람 결정이 없는 FIXTURE 버전, 미승인·반려 변경안, 철회 공문뿐이다. **다만 현재 합성 자료에는 사람 결정이 있는 승인 버전이 공문군당 하나뿐이라 "승인된 과거 버전 보존"을 통합 테스트로 확인하지 못했다.** 검색 API PR에서 평가 하네스에 두 번째 승인 버전을 만드는 사례를 추가해 확인한다(새 기능이 아니라 검증 추가).

## 한계

- 수동 재색인이므로 runner 실행 전까지 새로 승인된 규정은 색인에 없다(README에 명시). 자동 전달은 별도 ADR.
- nori 분석기는 플러그인이 필요해 이 PR의 테스트는 standard만 실행했다. 비교는 검색 API PR에서 초기 점검용 자료로 한다.
- CI 러너에서 ES 컨테이너(메모리 512 MB heap)를 추가로 띄우므로 Gradle Job 시간이 늘어난다. push 뒤 실제 시간을 기록한다.
