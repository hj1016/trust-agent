# TASK-016 근거 검색 기준선: Elasticsearch BM25 + metadata filter + Core 최종 확인

- 상태: **계획 승인**, 첫 구현 PR(색인·재색인 runner·불변식·멱등·compose) 착수 승인 (결정자 사용자, PR #46. 채택: 검색 범위 합성 공문 4건, 색인 B, 분석기는 초기 점검용으로 nori·standard 비교, 보류는 절대 점수+상대 비율 조합 우선 시험(다른 방식도 비교), 초기 재색인 수동 runner, Core Tool 재확인, 진단·행원 응답 분리. 수용 기준 미달 시 통과 처리하지 않고 실측값과 원인을 보고한 뒤 별도 판단. 이전 상태: 검색 범위 현재 합성 공문 4건, 초기 수용 기준 Recall@5 ≥ 0.80·MRR ≥ 0.70·Precision(반환 수 기준) ≥ 0.70·무관 근거 혼입률 ≤ 0.25 채택, 금지 근거 노출 0건·놓친 보류 0% 유지. ADR-013 승인(PR #44), ADR-014는 보완 뒤 승인 대기. 구현 착수는 계획·ADR 검수 뒤 별도 승인)
- 담당자 / 인간 결정자: AI 조사·계획·구현·평가 실행 / 사용자 범위·기준·판정·검수
- 요구사항 출처: ADR-013(승인, 색인 불변식과 질의 시점 필터의 보장 조건 대응표), PLAN-002 TASK-016 절(ES 색인, BM25 + filter 검색 API, 결과마다 Tool 2 재확인, 골든셋 평가 결과 기록), TASK-014(골든셋 3종, 세 단계 지표, 평가 스크립트 명세, 관련성 보류 기준 고정 절차, AC-05b·05c 후속 판단), CLAUDE.md 검색 baseline(BM25 + dense vector k-NN + metadata filter + reranker 중 이 Task는 BM25 + filter 기준선), ADR-014(진입 구조, 초안), 사용자 지시(검색 API 접근 권한, 최소 정보 제공, 진단 정보 분리, 요청 제한, fail-closed, 검색 범위는 현재 합성 공문 4건, Recall@5·MRR은 TASK-014 제안값을 초기 수용 기준으로, 반환 수 기준 Precision과 무관 근거 혼입률 보완 지표 추가)
- 관련 Task / ADR: TASK-014(완료), TASK-015(완료), TASK-021(완료 대기, 지연 열 형식 재사용), TASK-017c(행원용 Core 검색 경로 연결), TASK-018(벡터·reranker, 같은 골든셋으로 비교), ADR-011·012·013·014

## Goal / 관련 요구사항

- Goal: 행원 질문으로 승인된 근거를 찾아 주되, 후보는 Elasticsearch BM25가 고르고 **내용과 사용 가능 여부는 Core Tool 1·2가 정한다.** 같은 골든셋으로 측정한 기준선 수치를 남겨 TASK-018(벡터·reranker)과 비교한다.
- 사용자: 행원(TASK-017c에서 Core 경로로 연결), 검색 구현자·판정자(평가 하네스)
- 사전조건: TASK-015 상태(중도상환수수료 v2 승인, 셀러론 v2 검토 대기), ES 컨테이너, Tool 토큰
- 입력: `query`(1~200자), `familyId`(필수), `businessDate`(선택, 생략 시 서울 오늘. prepare와 같은 규칙), `consultationId`(선택), `topK`(기본 5, 최대 10)
- 업무규칙: (1) 질의 문장에서 공문군이나 날짜를 추론해 채우지 않는다. (2) ES 응답은 규칙 version ID와 점수만 쓴다. 행원에게 가는 문장·위치·해시·구조화 값은 Tool 2와 Tool 1 응답에서만 온다. (3) Tool 1이 사용 불가면 후보가 있어도 근거 보류이며 Core 사유를 그대로 전달한다. (4) 후보마다 Tool 2를 호출해 통과한 것만 반환하고 제거된 후보와 사유를 기록한다. (5) ES·Tool 통신 실패, 시간 초과, 계약 위반 응답은 전부 근거 보류(fail-closed). (6) 관련성 보류 기준(점수)은 구성별로 초기 점검용 자료로 정하고 버전과 함께 고정한다. (7) 행원 응답에는 통과한 근거만 담고, 재확인 전 후보·점수·제거 사유는 진단 모드에서만 돌려준다.
- 상태전이: 없음(검색은 상태를 바꾸지 않는다). 색인은 재색인 runner로만 바뀐다.
- 데이터 영향: Core 업무 DB 읽기만(재색인 runner). ES 색인 쓰기(Core runner). AI 서비스는 ES 읽기 전용 사용자. 업무 DB 변경 없음.
- API: 아래 "검색 API 계약".
- 트랜잭션: 없음. 재색인 runner는 DB 읽기 트랜잭션 하나와 ES bulk 쓰기(외부 호출은 DB 트랜잭션 밖).
- 권한: 이 Task의 AI 서비스 검색 API는 demo 모드(require-grant=false, 내부망·로컬 전용)로 구현한다. 행원용 경로(세션·상담 건·허용 공문군 검사, grant)는 TASK-017a의 기반 위에서 TASK-017c가 Core에 붙인다. ES는 비밀번호와 읽기 전용 사용자.
- 실패 시나리오: ES 연결 실패·시간 초과 → `EVIDENCE_HOLD`(`SEARCH_UNAVAILABLE`). Tool 1 사용 불가 → `EVIDENCE_HOLD`(Core 사유). Tool 2 403·오류 → 그 후보 제거(사유 기록). 후보 0건 → `EVIDENCE_HOLD`(`NO_RELEVANT_CANDIDATE`). 재색인 runner 실패 → alias 교체 없음(옛 색인 유지).
- 테스트: 아래 "테스트 범위".
- Out of Scope: dense vector k-NN·reranker(TASK-018), 행원 화면과 Core 검색 경로(017c), 업무 매뉴얼·내부 규정 색인(범위 확장은 골든셋 버전 상승과 함께 별도 Task), LLM 질의 해석, 자동 재시도.

## 요구사항과 범위

업무 문제: 행원이 질문으로 근거 원문을 찾는 기능이 없고 골든셋과 평가 명세만 있다. 검색이 승인 전 근거나 요청 범위 밖 근거를 섞으면 안 되며, 측정 없이 품질을 말할 수 없다.
포함 범위: Core 재색인 runner, ES 색인 설계와 compose, AI 서비스 검색 API(demo 모드), 관련성 보류 기준 고정, 평가 스크립트(TASK-014 명세 구현)와 하네스, 초기 점검·최종 평가 실행과 evidence, TASK-016 문서.
제외 범위: 위 Out of Scope.
기존 자산: 골든셋 3종과 schema·계약 테스트(TASK-014, 그대로 사용), `evaluation_context` 대조용 자료 해시 계산(계약 테스트 함수 재사용), Tool 1·2와 `CoreClient`(TASK-008·015), Java 하네스와 고정 시계(TASK-015·021), 계측 형식(TASK-021).

## 설계

### 색인 (ADR-013 승인)

제외 조건별 보장 방법과 지표 대응은 ADR-013 "보장 조건 대응" 표를 따르며, 평가 결과 파일은 그 표의 "보장" 열과 색인 불변식 테스트 통과 기록(revision, 테스트 이름)을 함께 적는다.

- 색인 이름 `trustagent-rule-evidence-<workspace>-<내용 해시+설정 해시 12자>`에 alias `…-<workspace>-current`. 이 Task에서는 workspace `main` 하나. 생략 판단은 이름이 아니라 현재 색인 메타(내용·설정 해시, reindex 버전)와 문서 수로 하며, alias가 가리키는 현재 색인은 새 색인의 준비·검증·전환 전에 지우지 않는다(PR #49).
- 문서 = HUMAN_REVIEW 승인 checklist 항목이 가리키는 규칙 version 하나(ADR-013 3-1항, PR #49 구현과 일치). 필드: `rule_version_id`(문서 ID), `family_id`, `notice_id`, `rule_key`, `evidence_text`(규칙 문장), `structured_text`(구조화 값을 문장화: 변경 전후 값·단위·시행일·조건·예외), `json_pointer`, `evidence_hash`, `approvals[]`(승인 checklist version ID, 결정 ID, 적용 시작·종료. 같은 규칙 version이 여러 승인·구간에 쓰이면 모두), `effective_ranges[]`(ES `date_range`, 시작 포함·종료 제외 `[from, to)`, 종료 없음은 무기한), `dataset_class`, `synthetic`, `source_hash`, `indexed_at`. 같은 규칙 version의 문장·위치·해시·구조화 값·공문군이 승인마다 다르면 병합하지 않고 `SEARCH_RULE_CONFLICT`로 재색인을 중단한다.
- 재색인 runner(Core, `--trust-agent.search-reindex.enabled=true`): 업무 DB에서 현재 승인 checklist(사람 결정 있음)와 항목·규칙 근거를 읽어 새 색인을 만들고 alias를 교체한다. 두 번 실행 시 문서 수와 `source_hash` 집합이 같다(멱등). 실패 시 alias를 바꾸지 않는다.
- 한국어 분석기: 제안 1(아래).

### 검색 API 계약 (AI 서비스, `contracts/ai-search-request.schema.json`, `ai-search-response.schema.json`)

요청: `{"query", "familyId", "businessDate"?, "consultationId"?, "topK"?}`. 모르는 필드 400, 길이·범위 위반 400.

응답(행원 모드):

```text
status            EVIDENCE | EVIDENCE_HOLD
family_id, business_date, evaluated_at, consultation_id?
hold_reasons[]    EVIDENCE_HOLD일 때. Core 사유 코드(HUMAN_REVIEW_PENDING 등) 또는 서비스 코드(SEARCH_UNAVAILABLE, CORE_UNAVAILABLE, CORE_TIMEOUT, TOOL_RESPONSE_INVALID, NO_RELEVANT_CANDIDATE)
evidence[]        {rank, rule_version_id, rule_key, instruction, structured_change, evidence{notice_id, evidence_text, json_pointer, evidence_hash}, score}
                  instruction·structured_change는 Tool 1 항목에서, evidence는 Tool 2 응답에서 온다
notices           human_decision_notice, source_notice, search_notice(후보는 검색이 고르고 사용 가능 여부는 Core가 정했다는 고정 문구)
relevance_hold    {method, threshold, version}  이 응답에 적용한 관련성 보류 기준
```

진단 모드(설정 `TRUST_AGENT_SEARCH_DIAGNOSTICS=1`, 평가 하네스 전용)에서만 추가: `diagnostics{raw_candidates[], forwarded_candidates[], removed[]{rule_version_id, stage, reason}, tool_calls{applicable_checklist, rule_evidence}, timings}`.

### 흐름

1. 입력 검증 → ES 질의(`multi_match` on `evidence_text`, `structured_text`; filter: `term family_id` = 요청값, `range effective_ranges`에 `businessDate` 하나를 `gte`·`lte` 같은 값과 `relation: intersects`로. 구간 사이 공백이면 후보에서 빠지고 종료 없는 구간은 무기한) → 원시 후보 상위 topK×2(기록용).
2. 관련성 보류 기준 적용 → Core로 전달할 후보(topK 이하).
3. Tool 1(`familyId`, `businessDate`) → 사용 불가면 `EVIDENCE_HOLD`(후보는 진단에만).
4. 후보마다 Tool 2 → 통과한 것만, Tool 1 항목의 구조화 값과 합쳐 순위 유지로 반환. 후보 0건이면 `NO_RELEVANT_CANDIDATE` 보류.
5. 모든 ES·Tool 호출은 TASK-021 계측 형식으로 기록(진단 모드).

### 관련성 보류 기준 (AC-05c, 제안 2)

후보 방식: (a) 절대 최소 점수, (b) 상위 점수 대비 비율, (c) (a)와 (b) 동시. 초기 점검용 10건으로 세 방식을 비교해 "보류 질의 후보 유출 0건"과 "잘못된 보류 최소"를 만족하는 값을 고르고 `relevance_hold.version`을 `bm25-hold-v1`로 고정한다. 최종 평가용으로 조정하지 않는다. 부족하면 조정용 자료(`…-tuning-v1.json`)를 추가한다.

### 실패 처리 계약 (HTTP 상태와 Core 오류 코드를 함께 본다)

| 상황 | 판정 근거 | 처리 |
|---|---|---|
| Tool 2가 403 `EVIDENCE_NOT_AVAILABLE`(요청 공문군의 현재 사용 가능 승인 항목 근거가 아님) | Core의 정상 판정 | **해당 후보만 제외**, 진단 `removed`에 사유 |
| Tool 1 200 `usable=false` | Core의 정상 판정 | 전체 근거 보류, `hold_reasons`에 Core 사유 코드 그대로 |
| Tool 1 404 `POLICY_FAMILY_NOT_FOUND` | Core의 정상 판정 | 전체 근거 보류(`POLICY_FAMILY_NOT_FOUND`) |
| Tool 1·2가 401 또는 403 `UNAUTHENTICATED`(토큰 거부) | 인증 실패 | 전체 근거 보류 `TOOL_AUTH_FAILED` |
| 연결 실패, 시간 초과, Core 5xx | 통신 실패 | 전체 근거 보류 `CORE_UNAVAILABLE` 또는 `CORE_TIMEOUT` |
| 200이지만 응답이 계약 schema와 다름, 또는 `ruleVersionId` 불일치 | 계약 위반 | 전체 근거 보류 `TOOL_RESPONSE_INVALID` |
| ES 연결 실패·시간 초과·오류 응답 | 검색 실패 | 전체 근거 보류 `SEARCH_UNAVAILABLE` |
| 후보 0건(필터·관련성 보류 뒤) | 정상 | 전체 근거 보류 `NO_RELEVANT_CANDIDATE` |

403은 Core 오류 코드로 두 경우를 가른다. `EVIDENCE_NOT_AVAILABLE`은 후보 제외, 그 밖의 403(인증·권한)은 전체 보류다. 코드가 없거나 모르는 코드면 보수적으로 전체 보류(`TOOL_RESPONSE_INVALID`)다. 부분 결과를 "일부 보류"로 돌려주지 않는다.

### 수동 재색인의 한계

재색인은 runner를 사람이 실행하므로, 새로 승인된 규정(또는 철회)은 다음 재색인 전까지 검색 후보에 반영되지 않는다. 그 사이 Core 재확인이 사용 불가 근거를 막지만, 새로 승인된 근거가 검색되지 않는 **누락**은 막지 못한다. 이 한계를 결과 파일과 README에 명시한다. 후속 승인 체험(TASK-027)은 승인 뒤 해당 workspace 색인 갱신까지 검증 범위에 넣는다. 자동 전달(Outbox 등)은 별도 ADR.

### 인프라

`infra/docker/compose.search.yml`: ES 8 단일 노드, 이미지 digest 고정, `127.0.0.1` 바인딩, 보안 활성(비밀번호는 환경변수 `TRUST_AGENT_ES_PASSWORD`), heap 1g. 사용자 2개(`trustagent_reindex` 쓰기, `trustagent_search` 읽기)는 compose 기동 뒤 초기화 스크립트로 만든다. 운영 compose(TASK-023)는 이 설정을 가져다 쓴다.

### 평가 (TASK-014 명세 구현)

- `scripts/evaluate_search.py`: 입력 골든셋 경로, 검색 API 주소(진단 모드), Tool 토큰(환경변수), 구성 ID와 `relevance_hold` 고정값·버전. `evaluation_context` 대조: `dataset_fingerprint` 재계산(계약 테스트 함수 재사용), 승인 상태는 Tool 1의 `decisionId` 비교, 평가 시각은 하네스 고정 시계. 불일치면 거부. `kind: tuning` 자료는 최종 평가로 기록하지 않는다.
- 하네스: `SearchEvaluationRunner`(테스트 소스, `-PsearchEvaluation=true`일 때만): Testcontainers PostgreSQL + ES, 고정 시계 2026-10-06T03:00:00Z, `PreparationScenario.load`, 재색인 runner 실행, AI 서비스 HTTP 기동(진단 모드), 평가 스크립트 실행. TASK-021 runner와 같은 구조.
- 결과 파일: `docs/evidence/SEARCH_EVALUATION_bm25_v1.md`(질의별 재확인 전 후보 상위 5, 제거 사유, 통과 목록, 세 단계 지표 표, 실패 질의, 보류 기준 고정값·버전, 호출 수와 지연 p50/p95) + 원본 JSON `docs/evidence/task-016/`.

### 지표와 통과 기준

TASK-014의 세 단계 지표를 그대로 계산한다. 통과 기준은 다음과 같다. **실제 측정 전에는 달성을 주장하지 않는다.**

| 지표 | 기준선(BM25) 기준 | 상태 |
|---|---|---|
| 최종 노출(`must_not`) | 0건 | 필수(유지) |
| 놓친 보류 | 0% | 필수(유지) |
| 검색 단계 제외 조건 준수 | 0건(공문군·업무일 필터). 승인·구버전 제외는 색인 불변식 테스트 | 필수 |
| 보류 질의 후보 유출 | 0건 | 필수 |
| Recall@5 | ≥ 0.8 | 초기 수용 기준(TASK-014 제안값, 사용자 채택) |
| MRR | ≥ 0.7 | 초기 수용 기준(사용자 채택) |
| 잘못된 보류 | ≤ 10% | TASK-014 제안값 |
| 필드 제공 | 100% | TASK-014 |
| Precision(반환 수 기준) | ≥ 0.70 | 초기 수용 기준(사용자 채택, 제안 3) |
| 무관 근거 혼입률 | ≤ 0.25(혼입 평균 ≤ 0.5건/질의는 보조 기록) | 초기 수용 기준(사용자 채택, 제안 3) |
| 평균 반환 수 | 기록만 | 보조 |

## Acceptance Criteria (구현 전 고정)

결정자 / 판단 근거 / 검토 대상: 사용자 / 이 계획 PR

| ID | 입력/상황 | 기대 결과(실패/경계 포함) | 검증 방법 | 결과/evidence |
|---|---|---|---|---|
| AC-01 | 재색인 runner 2회 실행 | 문서 수와 `source_hash` 집합 불변, alias가 최신 색인. 분석기만 바꾸면 새 색인, 실패 시 현재 색인·alias 유지 | Java 통합 테스트(ES Testcontainer) | PR #49에서 구현·검증(병합 뒤 결과 기록) |
| AC-02 | 색인 불변식 | 색인 문서 집합 = 사람 결정 있는 승인 checklist 항목의 규칙 version 집합. 셀러론 v2(미승인)·v1 수수료율(FIXTURE)·철회 규칙 0건. 셀러론 승인 뒤 재색인 → 생김, 철회 뒤 → 사라짐. 같은 규칙 version의 여러 승인 구간은 한 문서의 `approvals[]`·`effective_ranges[]`에 모두 남고 내용 충돌은 거부 | Java 통합 테스트 | PR #49에서 구현·검증(병합 뒤 결과 기록) |
| AC-03 | 검색 단계 후보 | 요청 공문군 밖·업무일 밖(`effective_ranges` 구간 밖, 구간 사이 공백 포함) 규칙 0건(골든셋 37건 전체) | 평가 결과 | 미검증 |
| AC-04 | 응답 내용 출처 | `evidence_text`·`json_pointer`·`evidence_hash`는 Tool 2 응답과 바이트 일치, `instruction`·`structured_change`는 Tool 1 항목과 일치. ES 문서 본문이 응답에 쓰이지 않음 | Python 단위 테스트(가짜 ES·가짜 Core) | 미검증 |
| AC-05 | Tool 1 사용 불가 | 후보가 있어도 `EVIDENCE_HOLD`, Core 사유 그대로, `evidence` 빈 배열 | Python 단위 테스트 + 평가(`fixture_period`, `unapproved`) | 미검증 |
| AC-06 | Tool 2 403·오류·계약 위반 | 그 후보만 제거, 진단 `removed`에 사유, 응답에 없음 | Python 단위 테스트 | 미검증 |
| AC-07 | 입력 오류 | `familyId` 없음·200자 초과·`topK` 11·모르는 필드 400. 질의에서 인자를 만들지 않음 | Python 단위·계약 테스트 | 미검증 |
| AC-08 | ES·Tool 통신 실패 | `EVIDENCE_HOLD`와 서비스 사유 코드, 추측 결과 없음 | Python 단위 테스트 | 미검증 |
| AC-09 | 진단 분리 | 진단 설정 없이 응답에 `diagnostics` 없음, 설정 시에만 포함 | Python 테스트 + 계약 schema | 미검증 |
| AC-10 | 평가 거부 | `evaluation_context` 불일치(자료 해시·승인 상태·평가 시각) 시 평가 스크립트가 거부 | Python 테스트 + 하네스 | 미검증 |
| AC-11 | 보류 기준 고정 | 초기 점검용 10건으로 방식·값을 정해 버전과 함께 설정·결과 파일에 기록. 최종 평가용으로 조정하지 않음 | 하네스 실행 로그 + 판단 기록 | 미검증 |
| AC-12 | 최종 평가 | 27건 결과 파일에 세 단계 지표 전부와 호출 수·지연. 필수 항목(노출 0, 놓친 보류 0, 유출 0, 제외 조건 0) 충족. Recall@5·MRR·Precision·혼입률은 측정값을 기록하고 기준 충족 여부를 표시 | evidence | 미검증 |
| AC-13 | 변경 범위 | Core 승인·일정·변경안·검증 코드와 Tool allowlist 변경 없음. 업무 DB 스키마 변경 없음 | diff 검토 | 미검증 |
| AC-14 | 테스트 수 | 기존 Python·Java 테스트 유지, 새 테스트 추가, 평가 하네스는 CI 미포함 | CI run | 미검증 |

### 완료 기준 변경 이력

변경 없음.

## Implementation Plan

예상 변경 파일: Core `search/`(재색인 runner, ES 클라이언트(Spring RestClient), 설정, 통합 테스트), AI 서비스 `search.py`, `es_client.py`, `app.py` 라우트, `config.py`(ES 주소·읽기 사용자·진단 설정), 계약 2개, `scripts/evaluate_search.py`, `tests/ai_service/test_search*.py`, `tests/unit/test_evaluate_search.py`, 하네스 `SearchEvaluationRunner`, `infra/docker/compose.search.yml`, README 3곳, evidence.

구현 순서(PR 단위):

1. **색인 PR**: ADR-013 승인 뒤. 재색인 runner, ES compose, 색인 불변식·멱등 테스트(AC-01·02·13).
2. **검색 API PR**: 검색 모듈, 계약, 단위·계약 테스트, 진단 모드, 초기 점검용 10건으로 보류 기준 고정(AC-04~11).
3. **평가 PR**: 평가 스크립트와 하네스, 최종 평가 실행, evidence(AC-03·12·14).

위험: 문서 3개의 BM25는 점수 분포가 거칠어 보류 기준이 불안정할 수 있다(조정용 자료로 보완). ES 컨테이너 메모리. 한국어 분석기 선택에 따른 recall 차이(제안 1로 비교).
예상 크기: 큼. 의존: ADR-013 승인, TASK-021(형식), TASK-015 상태. TASK-017a와 병렬 가능(공유 파일 없음).

## AI 제안 및 인간 판단 기록

### 제안 1: 한국어 분석기는 초기 점검용 10건으로 비교해 고른다

내용 / 대안 / 기대 효과 / 위험: nori 플러그인을 넣은 커스텀 이미지 vs standard analyzer(+ `multi_match` fuzziness). nori가 형태소 분리로 recall이 높을 가능성이 크지만 이미지 빌드가 추가된다. 초기 점검용 10건으로 둘을 돌려 Recall@5·유출 건수를 비교하고 선택 이유를 기록한다.

**판단** - [x] 채택 [ ] 수정 [ ] 거절 / 판단자 / 검토 대상: 사용자 / PR #46(초기 점검용 자료로 nori·standard 비교해 선택)

### 제안 2: 관련성 보류 방식은 (c) 절대 최소 점수 + 상위 대비 비율을 우선 시험한다

내용 / 대안 / 기대 효과 / 위험: (a)만 쓰면 질의 길이에 따라 점수 규모가 달라 불안정하고, (b)만 쓰면 전부 무관한 질의에서 상위 1건이 항상 통과한다. (c)는 둘을 막지만 값 2개를 고정해야 한다. 초기 점검용으로 세 방식을 모두 기록하고 (c)가 조건을 만족하면 채택.

**판단** - [x] 채택 [ ] 수정 [ ] 거절 / 판단자 / 검토 대상: 사용자 / PR #46(조합 우선 시험, 다른 방식도 비교 기록)

### 제안 3: 보완 지표 통과 기준 Precision(반환 수 기준) ≥ 0.70, 무관 근거 혼입률 ≤ 0.25(혼입 평균 ≤ 0.5건/질의)

내용: 자료 특성을 계산했다. 최종 평가용 비보류 질의 17건의 필수 근거 수는 16건이 1개, 1건이 2개이고 보조 근거를 더한 "적절한 반환" 수는 1개 10건, 2개 7건이다. 요청 범위 안 사용 가능 근거가 3개뿐이므로 질문과 무관하게 3개를 전부 반환하는 전략은 Recall@5 = 1.0이지만 Precision 평균 0.47, 혼입률 0.53(혼입 평균 1.59건/질의)이다. 보완 지표의 목적은 이 "전부 반환" 전략을 떨어뜨리는 것이므로 기준은 그 값보다 분명히 높아야 하고, 동시에 적절한 반환이 1~2개뿐인 질의에서 1건의 혼입(precision 0.5 또는 0.67)이 섞여도 전체 평균이 통과할 여지를 둬야 한다. 제안값 0.70은 "17건 중 약 10건은 혼입 0, 나머지는 혼입 1건 이내"에 해당하고, 혼입률 0.25는 "반환 4건 중 1건 미만"이다. TASK-018 최종 구성 목표는 Precision ≥ 0.80, 혼입률 ≤ 0.15를 제안한다. 대안: 더 엄격한 정확 일치율은 BM25 기준선에서 의미 있는 값이 나오기 어렵다(TASK-014 평가 한계). 위험: 자료가 작아 값이 거칠다(질의 1건이 약 0.06을 움직인다). 수치는 측정 전 제안이며 측정 결과를 보고 유리하게 바꾸지 않는다.

**판단** - [x] 채택 [ ] 수정 [ ] 거절 / 판단자 / 검토 대상: 사용자 / 계획 문서 검토(Recall@5 ≥ 0.80, MRR ≥ 0.70, Precision(반환 수 기준) ≥ 0.70, 무관 근거 혼입률 ≤ 0.25를 초기 수용 기준으로 채택. 측정 전 달성 주장 없음)

### 제안 4: 재색인 runner는 사람 결정 runner 뒤 수동 실행으로 시작한다

내용: 자동 트리거(결정 서비스 안에서 ES 호출)는 DB 트랜잭션 안의 외부 호출이 되어 규칙에 어긋난다. 이 Task는 runner 명령으로 두고, 자동화는 Outbox 등 전달 방식을 정하는 후속 ADR에서 다룬다. Core 재확인이 재색인 지연을 막는다.

**판단** - [x] 채택 [ ] 수정 [ ] 거절 / 판단자 / 검토 대상: 사용자 / PR #46(초기 재색인은 수동 runner. 한계는 '수동 재색인의 한계' 절)

## Implementation Result (구현 결과와 자동 검증)

미착수.

## AI self-review

미착수.

## 인간 검수와 Explainability Gate

판단 대기.

## 결정 기록과 완료

판단 대기.

## 선택: RAG / ingestion 설계 비교

| 비교 | 대안 A | 대안 B | 평가 자료/사전 지표 | 인간 판단/이유 |
|---|---|---|---|---|
| 단순 Hybrid vs Ontology-aware Retrieval | BM25 + metadata filter(이 Task) → dense k-NN + reranker(TASK-018) | 같은 ES에 공문-상품-기준-조건-예외-시행일 관계를 structured metadata로 두고 필터·부스트에 활용 | 같은 골든셋 37건, 세 단계 지표 | 이 Task는 A의 기준선만 측정한다. B는 `structured_text`와 `effective_from/to` 필드로 최소 형태를 색인에 포함하되 질의 부스트는 TASK-018에서 비교. 판단 대기 |
| 포맷별 parsing / OCR | 해당 없음(합성 공문은 이미 구조화 JSON) | 해당 없음 | | 이 Task 범위 밖 |
