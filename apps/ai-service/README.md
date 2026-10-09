# AI Service

FastAPI 기반 AI 서비스. TASK-015 범위에서 **규칙 조립 상담 준비안과 보류**를 구현했습니다. Core Tool API로 승인되고 지금 사용 가능한 checklist와 항목 근거만 읽어 준비안을 조립하거나, 사용 불가 사유로 보류하고, 그 결과를 Core의 별도 기록 경로에 남깁니다.

이 서비스가 하지 않는 것:

- LLM 문장 생성. 준비안의 모든 문장은 승인 checklist 항목의 지시 문장, 근거 Tool이 돌려준 규칙 원문, 구조화 값, 고정 안내 문구(`ai_service/messages.py`)에서만 옵니다. LLM 생성과 그 검증은 TASK-019입니다.
- 업무 DB 접근. DB 접속 설정 항목 자체가 없고 DB 드라이버 의존성도 없습니다. Core에는 서비스 토큰으로만 접근합니다.
- 승인·거절·한도·금리·신용등급 결정. 준비안 계약(`contracts/consultation-preparation.schema.json`)에 그런 필드가 없습니다.
- 근거 검색(TASK-016), 화면과 행원 최종 확인(TASK-017), 사용자별 인증·권한, 행원 메모 입력(TASK-015 제안 4로 제외).

## 실행

```bash
cd apps/ai-service
python3 -m venv .venv && .venv/bin/pip install -r requirements-ai.txt

export TRUST_AGENT_CORE_BASE_URL=http://127.0.0.1:8080
export TRUST_AGENT_TOOL_SERVICE_TOKEN='<읽기 토큰>'            # Core의 TRUST_AGENT_TOOL_SERVICE_TOKEN과 같은 값
export TRUST_AGENT_PREPARATION_RECORD_TOKEN='<기록 토큰>'      # Core의 TRUST_AGENT_PREPARATION_RECORD_TOKEN과 같은 값(없으면 기록하지 않음)
export TRUST_AGENT_CORE_TIMEOUT_SECONDS=5                     # 선택, 기본 5
export TRUST_AGENT_REPOSITORY_ROOT=/path/to/trust-agent       # 선택, 기본은 패키지 위치 기준 저장소 루트

.venv/bin/python -m ai_service prepare --application SW-APPLICATION-001 --business-date 2026-10-06 --consultation-id demo-001
```

HTTP 진입점(로컬 전용, 인증 없음. 반드시 127.0.0.1에만 바인딩. 응답 헤더 `X-Preparation-Recorded`가 본문 `record.recorded`와 같은 값이고, 기록 실패는 본문은 그대로 둔 채 HTTP 상태로 원인을 구분합니다. 아래 "HTTP 상태" 표):

```bash
.venv/bin/uvicorn ai_service.app:app --host 127.0.0.1 --port 8090
curl -s -X POST http://127.0.0.1:8090/api/v1/ai/consultation-preparations \
  -H 'Content-Type: application/json' \
  -d '{"applicationId": "SW-APPLICATION-001", "businessDate": "2026-10-06"}'
```

토큰 값은 환경변수로만 제공하며 파일·로그·출력·오류 메시지에 남기지 않습니다.

## 종료 코드

| 코드 | 뜻 |
|---|---|
| 0 | Core에 기록됨(`record.recorded=true`, 상태 `RECORDED` 또는 `ALREADY_RECORDED`). 준비안 상태가 READY·PARTIAL·HOLD 어느 것이든 기록 대상입니다 |
| 2 | 입력 오류(신청 자료 없음, 매핑에 없는 상품, 날짜 형식 등). 준비안 없음 |
| 3 | 기록 거부·실패·미시도(`record.recorded=false`, 상태 `REJECTED`·`FAILED`·`NOT_ATTEMPTED`와 `error_code`). 준비안은 출력되지만 `usage_notice`가 사용 금지와 원인별 할 일을 안내하고 stderr에도 같은 경고가 나옵니다. HTTP 진입점의 422/409/503/500/502/504와 같은 상황입니다 |
| 4 | 설정 누락(Core 주소 또는 읽기 토큰 없음). 출력 없음 |

## HTTP 상태 (기록 결과와 CLI 종료 코드의 대응)

| 상황 | `record.status` / `error_code` | HTTP | CLI 종료 코드 | 뜻과 할 일 |
|---|---|---|---|---|
| 기록 성공(준비안 상태 READY·PARTIAL·HOLD 모두) | RECORDED / ALREADY_RECORDED, `recorded=true` | 200 | 0 | 기록됐을 뿐 사용 허가가 아닙니다. Tool 확인 실패를 HOLD로 기록한 경우도 200이지만 준비 완료가 아닙니다 |
| Core가 현재 업무 조건상 사용 불가로 거부 | REJECTED, Core 422(예: `PREPARATION_NOT_USABLE`), `core_http_status=422` | 422 | 3 | 사용 금지. 업무 조건(승인·시행일·공문 상태)이 바뀐 뒤 다시 실행. 단순 재실행으로 해결되지 않음 |
| Core가 현재 상태와의 충돌로 거부 | REJECTED, Core 409(예: `PREPARATION_STALE`, `MAPPING_MISMATCH`) | 409 | 3 | 사용 금지. 다시 실행하면 현재 상태로 다시 확인 |
| 운영 설정 문제 | REJECTED `UNAUTHENTICATED`(Core 401) 또는 NOT_ATTEMPTED `RECORD_TOKEN_MISSING` | 503 | 3 | 사용 금지. 기록 토큰 설정 점검 |
| AI 서비스가 만든 기록 요청의 계약 위반 | REJECTED, Core 400(예: `PREPARATION_ID_MISMATCH`) | 500 | 3 | 사용 금지. 재실행으로 해결되지 않으니 담당자에게 알림 |
| Core 연결 실패 또는 Core 자체 5xx | FAILED `CORE_UNAVAILABLE` / `CORE_ERROR_RESPONSE` | 502 | 3 | 사용 금지. 잠시 뒤 다시 실행 |
| Core 시간 초과 | FAILED `CORE_TIMEOUT` | 504 | 3 | 사용 금지. 잠시 뒤 다시 실행 |
| 입력 오류(신청 없음, 매핑 없는 상품, 날짜 형식) | (준비안 없음) | 400 | 2 | 입력을 고쳐 다시 실행 |
| 설정 누락(Core 주소·읽기 토큰) | (준비안 없음) | 503 | 4 | 설정 점검 |

실패 응답에도 `record.recorded=false`, 원인 코드(`error_code`), `core_http_status`, 사용 금지 안내(`usage_notice`)가 본문에 그대로 있습니다. 토큰 값과 Core 내부 오류 상세(5xx 본문, 인증 실패 문구)는 전달하지 않습니다.

## 흐름과 상태

1. 신청 자료(`datasets/synthetic/work/applications`)와 기업 자료를 읽고 신청 JSON의 canonical sha256을 `source_hash`로 남깁니다.
2. 승인된 공문군 매핑 `datasets/synthetic/work/consultation-family-mapping.json`(현재는 합성 시나리오의 사용자 승인 설정)에서 상품의 공문군과 필수 여부를 읽습니다. 매핑에 없는 상품은 입력 오류 `PRODUCT_NOT_MAPPED`입니다. 파일 전체의 canonical sha256이 `family_mapping_hash`이며 Core가 저장 시 같은 매핑인지 대조합니다.
3. 공문군마다 Tool 1(`applicable_checklist`)을 부릅니다. 업무일은 생략해도 서울 기준 오늘을 계산해 항상 보냅니다.
   - `usable=true`면 항목마다 Tool 2(`rule_evidence`)로 규칙 원문·위치·해시를 받아 섹션을 **READY**로 만듭니다. 한 항목이라도 근거를 받지 못하면 섹션 전체를 보류합니다(부분 제공 없음).
   - `usable=false`면 섹션을 **HOLD**(`hold_kind=CORE_DECISION`)로 두고 Core의 사유 코드를 그대로 전달합니다. 404 `POLICY_FAMILY_NOT_FOUND`도 Core 판정으로 봅니다.
   - 401·403(`TOOL_AUTH_FAILED`), 5xx·연결 실패(`CORE_UNAVAILABLE`), 시간 초과(`CORE_TIMEOUT`), 계약과 다른 응답이나 그 밖의 상태 코드(`TOOL_RESPONSE_INVALID`), 근거 실패(`EVIDENCE_UNAVAILABLE`)는 **HOLD**(`hold_kind=UNVERIFIED`)입니다. Core가 판정한 것이 아니라 확인하지 못한 것이며 추측 준비안을 만들지 않습니다.
4. 전체 상태는 **필수 공문군 섹션만으로** 정합니다. 모두 READY → `READY`, 섞임 → `PARTIAL`, 모두 HOLD → `HOLD`. `preparation_complete`는 READY일 때만 true이고, PARTIAL·HOLD의 머리 문구에는 "상담 준비가 끝나지 않았습니다"가 들어가며 `remaining_checks`에 남은 필수 공문군이 응답 머리에 나열됩니다. 선택 공문군의 보류는 상태를 바꾸지 않고 `optional_holds`에 따로 둡니다.
   - 매핑에 필수 공문군이 하나도 없으면 각 공문군 섹션은 정상적으로 만들되 전체 상태는 `HOLD`, `preparation_complete=false`, 머리 문구에 `NO_REQUIRED_FAMILY_CONFIGURED`를 적고, 보류 섹션은 `optional_holds`에 둡니다. 빈 설정이 준비 완료가 되는 일은 없습니다.
5. **READY는 "기준 자료 준비 완료", 즉 현재 승인된 매핑에 따른 필수 자료(승인 checklist 항목과 근거)가 준비됐다는 뜻입니다.** 고객별 적용 조건·제출서류 확인이 끝났다는 뜻도, 상담·대출 결정이 났다는 뜻도 아닙니다. READY 출력의 `notices.staff_check_notice`가 "고객별 적용 조건과 제출서류는 직원 확인이 필요합니다"를 안내하고, PARTIAL·HOLD에서는 같은 자리에 준비 미완료 안내가 옵니다. 고객 니즈 확인과 상품 선택은 신청 건 기반 준비안의 앞 단계이며 이 기능이 대신하지 않습니다.
6. 준비안 ID(`preparation_id`)는 기록 본문에서 `preparation_id`·`run_id`·`consultation_id`·`sections[].evaluated_at`·`sections[].tool_response_hash`(Tool 응답에 평가 시각이 있어 매번 달라짐)를 뺀 canonical sha256입니다. 같은 입력·같은 Core 상태면 같고, 상태가 바뀌면 바뀝니다. 실행 ID(`run_id`)는 실행마다 새 값입니다. 재실행은 기존 기록이 있어도 항상 Tool을 다시 호출합니다.
7. Core 기록 경로 `POST /api/v1/consultation-preparations`에 기록 본문(`contracts/consultation-preparation-record.schema.json`. 근거 원문·메모·토큰 없음)을 **기록 토큰**으로 보냅니다. 201 `RECORDED`, 200 `ALREADY_RECORDED`(이 둘만 `record.recorded=true`), 400·401·409·422 `REJECTED`(Core의 오류 코드와 `core_http_status` 전달), 연결 실패·Core 5xx·시간 초과 `FAILED`(`CORE_UNAVAILABLE`·`CORE_ERROR_RESPONSE`·`CORE_TIMEOUT`), 기록 토큰 미설정 `NOT_ATTEMPTED`(모두 `record.recorded=false`). 자동 재시도는 없습니다. 사용 안내는 원인별로 다르며 모든 거부가 재실행으로 풀리는 것은 아닙니다(422는 업무 조건이 바뀌어야 함). 재실행하면 준비안 ID는 같아도 실행 ID가 다르고, Core 실행 기록에 실행별 평가 시각·Tool 응답 해시가 남습니다. **기록은 사용 허가가 아닙니다.** 사용 전에는 Core 조회로 사용 가능 여부를 다시 확인해야 하며, 거부·실패·미시도면 준비안을 사용하지 말고 다시 실행합니다.

## 안전성 기준(규칙 조립 출력의 구조 검증)

- 출력·기록 계약은 `additionalProperties: false`이고 결정 필드가 없습니다(SAFE-A).
- 고정 문구 표에 결정 표현이 없음을 테스트가 금지 표현 목록으로 확인합니다(SAFE-B).
- 모든 준비안에 `human_decision_notice`가 있습니다(SAFE-D). 보류 섹션에는 항목·근거가 없습니다(SAFE-E). 자유 문장 입력 경로가 없습니다(SAFE-F).
- 질문 기반 안전성 사례(S10·E22·E23)와 LLM 출력 검증은 TASK-019입니다. 이 서비스의 검증 통과는 LLM 안전성 검증을 뜻하지 않습니다.

## 호출 계측 (TASK-021, 선택)

`--metrics-file <경로>`를 주면 Core 호출(Tool 1, Tool 2, 기록)마다 종류, 공문군, 결과, 소요 시간(정수 마이크로초)과 전체 소요를 별도 JSON 파일(`contracts/ai-call-metrics.schema.json`)에 씁니다. stdout의 준비안과 종료 코드, 기록 본문은 계측이 없을 때와 같습니다. 계측 파일에는 토큰, 근거 원문, 항목 문장이 없습니다.

```bash
.venv/bin/python -m ai_service prepare --application SW-APPLICATION-001 --business-date 2026-10-06 --metrics-file build/metrics/run-001.json
python3 scripts/summarize_ai_call_metrics.py --input-dir <디렉터리> --warmup 5   # <디렉터리>/<시나리오>/*.json → p50/p95 표
```

Core와 함께 N회 반복 측정하는 runner는 `apps/core-service`의 `AiServiceCallMetricsRunner`(`-PaiCallMetrics=true -PaiServiceIntegration=true`, CI 미포함)입니다. 측정 결과는 `docs/evidence/AI_CALL_METRICS_EVIDENCE.md`에 기록합니다.

## 테스트

저장소 루트에서 다른 Python 테스트와 함께 실행합니다. `app.py` 테스트는 fastapi·httpx가 없으면 건너뜁니다.

```bash
python3 -m unittest discover -s tests -t . -v
```

Core와 함께 실행하는 연결 검증은 `apps/core-service`의 Java 통합 테스트가 이 CLI를 실행합니다.
