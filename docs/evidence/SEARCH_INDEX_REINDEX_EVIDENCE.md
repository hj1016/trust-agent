# 근거 검색 색인 재색인 검증 기록 (TASK-016 첫 구현 PR)

ADR-013에 따라 승인된 근거만 Elasticsearch에 색인하는 재색인 runner와 색인 불변식·멱등성 검증의 기록이다. 검색 API, 평가, 관련성 보류 기준은 이 PR에 없다(다음 PR).

## 검증 대상과 환경

| 항목 | 값 |
|---|---|
| 검증 대상 revision | 브랜치 `feat/search-index-reindex` 작업 트리(base `c54c923`) |
| 명령 | `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :apps:core-service:test -PaiServiceIntegration=true --no-daemon`(연결 검증 포함) |
| 환경 | macOS 27.0 aarch64, Java 21, Docker 28.3.3, Testcontainers PostgreSQL(`postgres@sha256:86c951e0…`)과 Elasticsearch 8.19.6(`docker.elastic.co/elasticsearch/elasticsearch@sha256:bfb21080…`, 보안 활성, HTTP TLS 없음, 비밀번호는 실행 중 생성) |
| 결과 | 201건 전부 통과, 실패 0, skip 0(연결 검증 실제 실행). 기존 185건 유지, 신규 16건(통합 10, 단위 6) |

## 신규 테스트가 보장하는 것

| 테스트 | 보장 |
|---|---|
| `SearchReindexIntegrationTest.reindexIndexesOnlyApprovedChecklistRules` | TASK-015 상태(중도상환수수료 v2 승인, 셀러론 v2 검토 대기)에서 재색인 → 문서 3개. 문서 ID 집합 = 사람 결정(APPROVE)이 있는 승인 checklist 항목의 규칙 version 집합(DB 질의로 계산). FIXTURE 출처 항목의 규칙(v1 수수료율 포함)과 미승인 규칙(셀러론 v2)은 색인에 없음. `_meta.content_hash`·`workspace_id`·`reindex_version` 기록. 접두사 색인은 하나뿐 |
| `reindexAgainIsIdempotent` | 두 번째 실행은 `ALREADY_CURRENT`, 같은 색인 이름, 문서 수 3 불변, alias 불변, 색인 수 1 |
| `documentFieldsStayWithinToolScopeAndSourceHashIsDeterministic` | 문서 필드 집합이 정의된 16개와 정확히 같음(공문 본문·변경안·검수자 ID 없음), `dataset_class=SYNTHETIC_INTERNAL`, `synthetic=true`, 시행 기간이 승인 일정과 일치 |
| `approvingAnotherFamilyAddsItsRulesAndReplacesIndex` | 셀러론 v2 승인 뒤 재색인 → 두 공문군 규칙이 모두 있고 옛 색인은 삭제, alias는 새 색인 |
| `withdrawnNoticeRulesDisappearAfterReindex` | 중도상환수수료 v2 철회 사건 뒤 재색인 → 그 공문의 규칙이 사라지고 셀러론만 남음 |
| `sameRuleVersionInTwoScheduleRangesKeepsBothRangesAndFiltersByDateWithGap` | 같은 승인 버전이 최신 일정의 두 구간([09-15,09-20), [10-01,∞))에 있는 자료를 만들어 재현. 문서는 규칙 version당 하나이며 `approvals[]` 둘(승인 version·결정 ID·구간)과 `effective_ranges[]` 둘을 가진다. ES date_range 질의로 날짜별 후보 수 확인: 시작 전 0, 시작일 포함 3, 종료일 제외 0, 구간 사이 공백 0, 두 번째 구간 시작 3, 무기한 종료(2027-12-31) 3 |
| `analyzerChangeIsNotServedByTheExistingIndex` | 같은 문서에서 분석기만 nori로 바꾸면 생략하지 않고 새 색인 생성을 시도한다(이 환경에는 플러그인이 없어 `SEARCH_INDEX_CREATE_FAILED`로 끝나고 alias·색인은 그대로). standard로 돌아오면 ALREADY_CURRENT |
| `currentIndexWithUnexpectedDocumentCountIsNeverDeletedBeforeSwap` | 현재 색인에서 문서 하나를 지워 문서 수가 어긋난 상태에서 실패를 주입 → alias와 검색 가능 상태 유지, 현재 색인 미삭제, 부분 색인 없음. 성공 재색인은 현재 이름과 다른 이름(`-r<초>`)으로 만든 뒤 전환하고 옛 색인을 지운다. **그 뒤 같은 자료로 다시 실행하면 복구 접미사 이름이라도 메타·문서 수가 맞아 ALREADY_CURRENT** |
| `failedReindexKeepsCurrentIndexAndAliasAndRemovesPartialIndex` | 내용이 바뀐 상태에서 refresh 단계 실패를 주입 → 예외, alias와 기존 색인·문서 수 그대로, 부분 색인 삭제. 이후 정상 재색인은 새 색인으로 교체 |
| `readOnlySearchUserCannotWriteButCanSearch` | 검색 사용자(읽기 전용 역할)의 문서 쓰기 403, 색인 생성 403, 검색 200 |
| `SearchDocumentsTest`(6건) | 분석기만 달라도 설정 해시와 색인 이름이 달라지고 `_meta.settings_hash`에 기록됨, 같은 규칙 version의 구간·승인이 모두 보존되고 무기한은 `lt` 없음, 같은 규칙 version의 문장·구조화 값 충돌은 `SEARCH_RULE_CONFLICT`로 거부(내용은 메시지에 없음), | 구조화 값 문장화의 결정성과 null 처리, 내용 해시가 `indexed_at`과 무관하고 내용 변경에 반응, 중복 규칙 version은 시행일이 늦은 쪽 유지, 색인 정의가 strict mapping과 요청 분석기 사용 |

## 보장 조건 대응 (ADR-013 표)

- 승인 항목 근거만, 구버전·FIXTURE 제외: **색인 불변식**. 위 첫 번째·네 번째·다섯 번째 테스트가 양성·음성 사례를 확인한다.
- 요청 공문군만, 현재 적용 공문만: **질의 시점 필터**. 색인 문서에 `family_id`, `effective_from/to`가 있고 필터 적용과 측정은 검색 API PR에서 한다. 이 PR은 필드 존재만 보장한다.
- 재색인 사이 상태 변화: **Core 재확인**. 검색 API PR 범위.

## 변경 범위

Core `search/` 패키지(설정, ES 클라이언트, 색인 대상 조회, 문서 생성, 재색인 서비스·runner·설정), `application.yml`·`application-prod.yml`의 `trust-agent.search`, prod 필수 설정에 ES 자격증명(재색인 켤 때), `infra/docker/compose.search.yml`과 `search/init-users.sh`, README 2곳, 테스트 helper `PreparationScenario`의 가시성을 public으로(내용 변경 없음). Core 승인·일정·변경안·검증 로직, Tool allowlist, 업무 DB 스키마 변경 없음. Gradle 의존성 추가 없음(ES 호출은 표준 HttpClient).

## 재색인 경계 조건 보완(검토 요청 반영)

- **분석기 변경.** 색인 이름과 생략 판단에 문서 내용 해시뿐 아니라 설정 해시(분석기·mapping·reindex 버전)를 넣었다. 생략은 **이름이 아니라** 현재 색인의 `_meta`(content_hash, settings_hash, reindex_version)와 실제 문서 수가 예상과 모두 같을 때 한다(복구 접미사 색인도 그대로 쓴다).
- **사용 중인 색인 보호.** alias가 가리키는 현재 색인은 새 색인의 준비·검증·전환이 끝나기 전에 지우지 않는다. 현재 색인 이름이 목표 이름과 같은데 내용이 어긋나면 `-r<초>` 접미사를 붙인 다른 이름으로 만든 뒤 전환한다. 이전 실행이 남긴 잔여 색인(현재 색인이 아닌 것)만 미리 지운다.
- **인증정보.** compose healthcheck는 비밀번호를 curl 인자(`-u`, `-H`)로 두지 않고 셸 내장 `printf`로 netrc 파일(umask 077)을 만들어 `--netrc-file`로 넘긴다. 환경변수 자체는 컨테이너 환경에 있으며 이는 ES 이미지의 `ELASTIC_PASSWORD` 방식과 같다.

## 과거 승인 버전: 문서 하나에 적용기간 여러 개(결정 반영, ADR-013 3-1항)

같은 규칙 version이 여러 승인 checklist·일정 구간에 쓰이면 문서를 나누거나 구간을 합치지 않고 `approvals[]`(승인 version·결정 ID·구간)와 `effective_ranges[]`(date_range)에 모두 담는다. 구간은 시작 포함·종료 제외, 종료가 없으면 무기한이다. 같은 규칙 version인데 문장·위치·해시·구조화 값·공문군이 다르면 병합하지 않고 `SEARCH_RULE_CONFLICT`로 재색인을 중단한다(기존 alias 유지). 업무일 필터 질의(`effective_ranges` range, intersects)는 검색 API PR에서 연결하며, 위 통합 테스트가 같은 질의로 경계·공백·무기한을 확인했다. 현재 합성 자료에는 다중 구간 사례가 없어 테스트 자료로 재현했다.

## 시점별 조회 요구와의 관계(설계 확인)

색인 대상 SQL은 공문군의 **최신 일정 revision에 있는 모든 항목**을 포함한다. 사람 결정이 있는 승인 버전이 새 승인으로 대체되면 이전 항목은 `effective_to`가 닫힌 채 최신 revision에 남으므로(HumanReviewService의 일정 revision 생성 방식), 그 과거 승인 버전의 규칙도 색인에 남고 `effective_from/to`로 업무일 필터를 받는다. 즉 "정상 승인된 과거 버전"은 삭제하지 않는다. 제외되는 것은 사람 결정이 없는 FIXTURE 버전, 미승인·반려 변경안, 철회 공문뿐이다. 다른 승인 버전(다른 규칙 version)의 과거 구간은 이 설계로 보존된다. **같은 규칙 version이 두 구간에 쓰이는 경우**만 위 절의 한계가 있다.

## 한계

- 수동 재색인이므로 runner 실행 전까지 새로 승인된 규정은 색인에 없다(README에 명시). 자동 전달은 별도 ADR.
- nori 분석기는 플러그인이 필요해 이 PR의 테스트는 standard만 실행했다. 비교는 검색 API PR에서 초기 점검용 자료로 한다.
- CI 러너에서 ES 컨테이너(메모리 512 MB heap)를 추가로 띄우므로 Gradle Job 시간이 늘어난다. push 뒤 실제 시간을 기록한다.
