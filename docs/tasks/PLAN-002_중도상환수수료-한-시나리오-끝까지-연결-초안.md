# PLAN-002 중도상환수수료 한 시나리오를 Core Tool API → 검색 → 상담 준비와 보류 → 화면까지 연결하는 Task 분할 초안

- 상태: **초안** (사용자 지시로 작성. 각 Task는 시작 전에 사용자가 검토·승인한다)
- 출처: PLAN-001(TASK-001~013 완료·진행 이력), README MVP 7~9단계, CLAUDE.md 기술 경계(검색 baseline = BM25 + dense vector k-NN + metadata filter + reranker), DEVELOPMENT_RULES(Vertical Slice, fail-closed, 검색은 골든셋과 지표를 구현 전 정의)
- 원칙(사용자 지시): 기술 계층을 각각 완성한 뒤 연결하지 않는다. **각 Task가 끝나면 중도상환수수료 시나리오가 그 시점의 범위에서 실제로 실행된다.** 검색 구현 전에 골든셋과 평가 기준을 먼저 확정한다. BM25는 중간 기준선이며 벡터 검색과 reranker가 최종 목표다. 사용 불가 근거 제외와 최종 Core 확인은 모든 단계에서 유지한다.

## 0. 지금 직접 실행 가능한 것과 아직 없는 것

**지금 실행 가능(Core만으로, 명령과 HTTP)**

| 단계 | 할 수 있는 것 | 실행 방법 |
|---|---|---|
| 공문 수신·구조화·적용 공문 선택 | 합성 공문 v1/v2 적재, 업무일·인지 시각 기준 적용 공문 선택, 모호·철회·미래 차단 | `SyntheticInternalImporter`, `GET /api/v1/internal-policy/checklists/{familyId}/applicable` |
| 변경안 생성 → 자동 검증 → 사람 결정 → 승인 checklist 발행 | 변경안(1.2 → 0.8) 생성, PASS/WARN/FAIL 판정, 승인·수정·반려, 승인 시 checklist와 일정 revision 발행 | core README의 demo 명령 4개(`proposal-generation`, `proposal-validation`, `human-review`, 예시 적재) |
| 행원용 적용 checklist 조회 | 승인 뒤 `AVAILABLE`과 사용 허용 true, 항목 3개, 사용 불가 사유 | 같은 applicable 조회 |
| AI 서비스용 Tool(PR #31, 검수 대기) | `applicable_checklist`, `rule_evidence`, 토큰 인증, 감사 | `POST /api/v1/tools/{toolName}` |
| 공개 상품 근거 | KB 공개 상품 관측·확인 상태 조회, 셀러론 법인 한도 교차 검증 | `GET /api/v1/public-products/{productKey}/observed-state` |

**아직 없는 것**

| 없는 것 | 뜻 |
|---|---|
| AI 서비스(FastAPI) | Tool을 호출하는 쪽이 없다. 지금은 Core가 "읽을 수 있게 열어 둔" 상태다 |
| 상담 준비안 생성과 보류 처리 | 합성 기업·신청 건에 대해 "이 상담에 쓸 checklist와 근거"를 묶어 주고, 사용 불가면 보류 사유를 남기는 흐름(MVP 7단계) |
| 근거 검색 | 행원이 질문으로 근거 원문을 찾는 기능(MVP 8단계 일부). 골든셋과 평가 기준도 아직 없다 |
| 행원의 최종 확인 기록 | 상담 전 행원이 근거와 함께 확인했다는 기록(MVP 8단계) |
| 화면 | 전부 없다 |
| 사용자별 인증·권한 | Tool 인증은 서비스 토큰 1개뿐이다 |

## 1. 끝까지 연결할 한 시나리오

합성 기업 `SW-COMPANY-001`(가상상사)이 셀러론 상담 신청 `SW-APPLICATION-001`을 냈다. 행원이 2026-10-06에 상담을 준비한다. 적용 공문은 중도상환수수료 v2(0.8퍼센트, 2026-10-01 시행), 승인 checklist는 TASK-007로 발행된 것이다.

1. 행원(또는 AI 서비스)이 "이 신청 건의 상담 준비안"을 요청한다.
2. AI 서비스가 Core Tool 1로 적용 checklist를 읽는다. 사용 가능하면 준비안을 만들고, 사용 불가면 **보류**(사유 그대로 전달, 수기 checklist 안내).
3. 행원이 "중도상환수수료 예외"처럼 질문하면 근거를 검색한다. 검색 결과는 후보일 뿐이고, **각 후보를 Core Tool 2로 다시 확인**해 사용 불가·다른 공문군·미승인 근거는 제외한다.
4. 행원이 준비안과 근거를 보고 최종 확인을 기록한다. 기록은 Core에 남는다.
5. AI 서비스가 꺼져 있으면 화면은 Core의 수기 checklist를 그대로 보여 준다(MVP 9단계).

## 2. Task 분할 (세로 조각, 각 Task 끝에 실행 가능한 흐름)

| Task | 업무 결과 | 이 Task가 끝나면 실행되는 흐름 | 의존 |
|---|---|---|---|
| TASK-008 (진행 중, PR #31) | Core Tool API | AI 서비스 없이도 HTTP로 Tool 호출 가능 | TASK-007 |
| **TASK-014 검색 골든셋과 평가 기준** | 골든셋 파일, 지표 정의, 평가 스크립트 명세, 통과 기준. 코드 변경 없음 | (문서·데이터) 검색 구현 전 심사 기준 확정 | TASK-008 |
| **TASK-015 AI 서비스 최소 흐름: 상담 준비안과 보류** | FastAPI 서비스가 Tool 1만으로 준비안을 만들거나 보류한다. LLM 없음(규칙 기반 조립). Core에 준비안 기록 | 명령 한 번으로 "신청 건 → 준비안 또는 보류 사유" 끝까지 실행 | TASK-008 |
| **TASK-016 근거 검색 기준선(BM25 + metadata filter) + Core 최종 확인** | ES 색인(승인 checklist 항목과 규칙 원문, dataset_class·승인 상태 metadata), BM25 + filter 검색 API, 결과마다 Tool 2 재확인 | 행원 질문 → 후보 검색 → Core 확인 → 사용 가능한 근거만 반환. 골든셋 평가 결과 기록 | TASK-014, TASK-015 |
| **TASK-017 행원 최종 확인 기록과 최소 화면** | 준비안·근거·보류 사유를 보여 주고 행원이 "근거 확인"을 기록하는 화면 1개. AI 중단 시 수기 checklist 표시 | 화면에서 시나리오 1~5 전부 실행 | TASK-015, TASK-016 |
| **TASK-018 검색 최종 범위: dense vector k-NN + reranker, ADR-010** | 벡터 색인과 reranker 추가, 골든셋으로 BM25 기준선과 비교, 인간 선택 기록 | 같은 화면·같은 API로 검색 품질만 향상 | TASK-016 |
| TASK-011 대표 E2E(기존) | S1~S9 한 번에 재현하는 E2E와 evidence | MVP 완료 근거 | TASK-017 |

번호 014~018은 새 번호다(004, 010은 취소로 재사용 금지, 013은 완료).

### TASK-014 검색 골든셋과 평가 기준 (검색 구현 전)

- 골든셋 위치와 형식: `datasets/synthetic/search-goldenset/prepayment-fee-goldenset-v1.json`. 질의마다 `query`, `business_date`, `family_id`, `relevant`(사용 가능해야 하는 규칙 version ID 목록), `must_not`(사용 불가·구버전·다른 공문군 규칙 version ID 목록), `expected_hold`(보류여야 하는지), 작성 근거. 버전 번호를 붙여 바꾸면 새 버전으로 기록한다.
- 질의 초안(10건 이상, 전부 합성 공문 기준): "중도상환수수료율 변경", "기업여신 중도상환 0.8퍼센트 시행일", "수수료 면제 예외", "고객 약정일 확인", "2026-10-01 이후 중도상환 조건", "적용 공문 버전 확인", "1.2퍼센트 수수료"(구버전: `must_not`에 v1 규칙), "셀러론 법인 한도 20억"(다른 공문군: 중도상환수수료 상담에서는 제외), "매출 정산 내역"(셀러론 미승인 규칙: 제외), "대출 승인 기준"(관련 없음: 결과 없음과 보류).
- 지표: Recall@5, MRR, **미승인·사용 불가·다른 공문군 근거 노출률 0건(필수)**, 보류 정확도(expected_hold와 일치), 질의당 Core 재확인 호출 수.
- 통과 기준(초안): BM25 기준선 Recall@5 ≥ 0.8, MRR ≥ 0.7, 노출률 0, 보류 정확도 100%. 최종(TASK-018) 목표: Recall@5 ≥ 0.9, MRR ≥ 0.8, 노출률 0. 기준선이 이 값을 못 넘으면 검색 구현을 시작하지 않고 골든셋과 색인 설계를 다시 본다.
- 평가 방법: 평가 스크립트 명세(입력 골든셋, 검색 API 호출, Core 확인 뒤 결과, 지표 계산, 결과 파일 `docs/evidence/SEARCH_EVALUATION_<버전>.md`). 같은 골든셋으로 BM25와 최종 구성을 비교한다.
- 완료 확인 조건 초안: 골든셋이 schema를 통과하고 모든 규칙 version ID가 실제 합성 공문에 존재, 구버전·다른 공문군·미승인 항목이 `must_not`에 있음, 지표 정의와 통과 기준이 문서로 확정, 사용자 승인 기록.

### TASK-015 AI 서비스 최소 흐름: 상담 준비안과 보류

- 범위: Python FastAPI 서비스(`apps/ai-service`). 업무 DB 접근 없음, Core Tool만 호출. LLM 없음(이 Task는 조립과 보류 규칙만). 준비안 = 신청 건(합성) + 적용 공문 + 승인 checklist 항목 + 항목별 근거 ID. 보류 = `usable=false`면 사유 코드와 사람이 읽을 설명, 수기 checklist 안내 문구.
- Core 쪽 추가: 준비안·보류 기록 테이블(V10, append-only)과 기록용 쓰기 endpoint 1개(**Tool이 아니라 별도 경로**, 같은 서비스 토큰, 기록만 가능). 준비안은 Core가 다시 Tool 1 결과와 대조해 사용 불가 상태면 저장을 거부한다(최종 Core 확인).
- 실행 가능한 흐름: `python -m ai_service prepare --application SW-APPLICATION-001 --business-date 2026-10-06` → 준비안 JSON 또는 보류 JSON, Core에 기록 ID.
- 완료 확인 조건 초안: (1) 승인된 기간은 준비안에 항목 3개와 근거 ID. (2) 미승인 기간은 보류, 사유 `HUMAN_REVIEW_PENDING` 등, 항목 없음. (3) Core 거부: AI가 사용 가능이라고 주장해도 Core가 사용 불가면 기록 거부. (4) Tool 401/403은 보류로 변환(fail-closed). (5) AI 서비스에 DB 자격증명 없음(설정 검사). (6) 감사: Tool 호출 감사와 준비안 기록이 연결됨(상담 ID).

### TASK-016 근거 검색 기준선 + Core 최종 확인

- 색인 대상: 승인 checklist 항목(instruction, structured_change 텍스트화)과 규칙 원문(evidence_text). 문서 metadata: `dataset_class`, `synthetic`, `family_id`, `notice_id`, `rule_version_id`, `approval_status`(승인 checklist 항목의 근거인지), `effective_from/to`. 색인은 Core에서 ES로 보내는 재색인 job(멱등, 두 번 실행 시 문서 수 불변).
- 검색: BM25 + metadata filter(승인 항목 근거만, 공문군 일치). 결과 후보를 **Core Tool 2로 하나씩 재확인**해 통과한 것만 반환. 검색 인덱스는 업무 원장이 아니다(CLAUDE.md).
- 실행 가능한 흐름: `POST /api/v1/ai/search`(AI 서비스) → ES 후보 → Core 확인 → 근거 목록. TASK-014 평가 스크립트로 지표 산출.
- 완료 확인 조건 초안: 골든셋 통과 기준 충족, 노출률 0, 색인 멱등, compose에 ES 추가, 평가 결과 evidence. **dense k-NN과 reranker는 이 Task에서 빼지 않는다. 다음 Task에서 추가하며 이 Task는 기준선 측정이다.**

### TASK-017 행원 최종 확인 기록과 최소 화면

- 화면 1개(상담 준비): 신청 건 선택 → 준비안 또는 보류 사유 → 근거 검색 → 항목별 "근거 확인" 체크 → 최종 확인 기록. AI 서비스가 꺼져 있으면 Core applicable 조회로 수기 checklist를 그대로 표시하고 "AI 준비안 없음"을 알린다.
- Core 쪽: 행원 최종 확인 기록(V11, append-only, 행원 ID는 합성, 근거 ID와 준비안 ID 참조). 승인·거절 결정은 기록하지 않는다(AI와 화면은 여신 결정 주체가 아니다).
- 완료 확인 조건 초안: 화면에서 시나리오 1~5 재현, 보류 상태에서 확인 기록 불가, AI 중단 시 수기 checklist 표시, 기록에 근거 ID 포함.

### TASK-018 검색 최종 범위(dense vector k-NN + reranker)와 ADR-010

- 벡터 색인(로컬 임베딩 모델, 외부 API 없음 가정 → 판단 필요), reranker, 단순 Hybrid와 경량 온톨로지 metadata 비교. 같은 골든셋으로 BM25 기준선과 비교표, 인간 선택 기록. Core 최종 확인은 그대로.
- 완료 확인 조건 초안: 최종 목표 지표 충족 또는 미달 시 대안과 별도 판단, 노출률 0 유지, 응답 시간 기록.

## 3. 의존 순서

```text
TASK-008 Core Tool API (PR #31)
   ├─► TASK-014 골든셋·평가 기준 (문서) ─┐
   └─► TASK-015 AI 준비안·보류 (실행 가능 흐름 1) ─┴─► TASK-016 BM25 검색 + Core 확인 (흐름 2)
                                                         └─► TASK-017 화면 + 최종 확인 (흐름 3) ─► TASK-011 E2E
                                                         └─► TASK-018 벡터 + reranker (품질 향상)
```

## 4. 사용자가 요청한 보완 사항과 반영 위치

| 요청 사항 | 반영 |
|---|---|
| 한 시나리오(중도상환수수료)가 Tool API → 검색 → 상담 준비와 보류 → 화면까지 연결 | 1절 시나리오 1~5, 2절 Task 표의 "이 Task가 끝나면 실행되는 흐름" |
| 기술 계층을 각각 완성한 뒤 연결하지 않고 중간에도 실행 가능한 흐름 확보 | TASK-015(명령 한 번으로 준비안·보류), TASK-016(질문 → 후보 → Core 확인 → 근거), TASK-017(화면에서 전부). 각 Task가 독립 실행 가능 |
| 검색 구현 전에 골든셋과 평가 기준 제시 | TASK-014(코드 없음, 골든셋·지표·통과 기준·평가 스크립트 명세). TASK-016은 TASK-014 승인 뒤 시작 |
| BM25는 중간 기준선, 벡터 검색과 reranker는 최종 목표 유지 | TASK-016 = 기준선 측정(범위 축소 아님 명시), TASK-018 = dense k-NN + reranker + ADR-010. PLAN-001 범위 축소 규칙 유지 |
| 사용 불가 근거 제외와 최종 Core 확인 유지 | TASK-015 준비안 저장 전 Core 재확인, TASK-016 검색 후보마다 Tool 2 재확인, 골든셋 지표 "노출률 0건 필수", TASK-017 보류 상태에서 확인 기록 불가 |
| 후속 계획은 각 Task 시작 전 검토 | 각 Task 절에 완료 확인 조건 초안만 두고 상태 "초안". 구현 착수는 Task별 별도 승인 |

## 5. 최종 판단 항목

사용자가 정해야 다음 Task(TASK-014)를 시작할 수 있는 것.

1. PLAN-002의 Task 분할과 순서(014 → 015 → 016 → 017 → 018 → 011) 자체의 승인 여부.
2. 제안 1: TASK-015 준비안을 LLM 없이 규칙으로 조립(LLM 문장 생성은 화면 뒤 별도 Task).
3. 제안 2: 준비안·보류 기록은 Tool이 아닌 별도 쓰기 endpoint이고 Core가 저장 전 사용 가능 여부를 다시 확인(ADR-011 "쓰기 Tool 없음" 유지).
4. 제안 3: 골든셋 통과 기준 수치(기준선 Recall@5 0.8 / MRR 0.7, 최종 0.9 / 0.8)와 질의 수(10건 이상)를 TASK-014에서 확정.
5. 제안 4: 임베딩 모델은 로컬 실행으로 제한(합성 공문이라도 외부 API에 보내지 않음). TASK-018 전 판단.
6. TASK-014 계획 작성 착수 승인 여부.

## 6. 판단이 필요한 제안 (상세)

- 제안 1: TASK-015의 준비안은 LLM 없이 규칙으로 조립한다. LLM 문장 생성은 화면(TASK-017) 뒤 별도 Task.
- 제안 2: TASK-015의 준비안 기록은 Tool이 아니라 별도 쓰기 endpoint로 두고 Core가 저장 전 사용 가능 여부를 다시 확인한다(ADR-011 "쓰기 Tool 없음" 유지).
- 제안 3: 골든셋 통과 기준 수치(기준선 Recall@5 0.8/MRR 0.7, 최종 0.9/0.8). 근거가 약하므로 TASK-014에서 질의 수와 함께 확정.
- 제안 4: 임베딩 모델은 로컬 실행 가능한 것으로 제한(외부 API에 합성 공문 전송 금지). TASK-018 시작 전 판단.

**판단** - 각 Task 시작 전 사용자 검토. 이 초안 자체의 승인 여부와 제안 1~4 판단 대기.
