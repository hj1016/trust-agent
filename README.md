# TrustAgent

## AI-native SDLC 기준

현재 개발 기준과 Source of Truth는 [CLAUDE.md](CLAUDE.md) 및 [개발 규칙](docs/development/DEVELOPMENT_RULES.md)을 따릅니다. [Task 템플릿](docs/development/TASK_TEMPLATE.md)과 [인간 검수/설명 가능성 체크리스트](docs/development/REVIEW_CHECKLIST.md)를 사용하며 [TASK-000 Baseline Audit](docs/tasks/TASK-000_초기-수집-자산-기준선-점검.md)에서 기존 자산을 점검합니다.
최신 명시적 결정은 Core 업무 DB PostgreSQL 유지(ADR-009), Elasticsearch(BM25 + dense vector k-NN + metadata filter + reranker) 검색, Redis 단기 상태/중복 방지/캐시, FastAPI AI의 Core Tool API 조회입니다. AI의 업무 DB 직접 접근은 금지하며 LangGraph는 상태/분기/재시도가 필요한 workflow에만 사용합니다. 아래 PostgreSQL 설명과 테스트 결과는 실제 구현 상태입니다. Core Tool API는 AI 서비스용 읽기 전용 Tool 2개(`applicable_checklist`, `rule_evidence`)와 test/demo 수준의 서비스 토큰 인증, 감사 기록 범위로 구현됐습니다(TASK-008, ADR-011). FastAPI AI 서비스는 TASK-015 범위(LLM 없는 규칙 조립 상담 준비안·보류와 Core 기록 경로, ADR-012)까지 구현됐고 검색·LLM 생성·화면·사용자별 인증·권한은 아직 구현되지 않았습니다. 검색 골든셋과 평가 기준은 TASK-014로 작성됐으며 검색 품질 검증은 아직 없습니다.
도입 이전 자산은 Pre-SDLC Asset으로 현재 기준에서 KEEP / MODIFY / DROP / NEW를 판단합니다. Audit 초안은 인간 검수 전 제안이며 과거 작업을 소급해 AI-native로 기록하지 않습니다.

행원이 확인한 공문 변경사항을 검수된 업무 기준과 원문 근거로 연결하는 은행 기업여신 상담 업무지원 플랫폼

## 현재 상태

공개 상품 snapshot 수집과 정규화 경로를 hardening했습니다. Catalog의 공식 KB URL을
검증하고 raw HTML을 비공개 content-addressed artifact로 저장하며, 같은 내용의 재관측도
별도 Observation으로 남깁니다. 안정적인 상품 조건은 ProductTermsVersion으로, 시점별
광고 금리는 ObservedRateQuote로 분리하고 각 관측의 근거와 처리 이력을 append-only
record로 보존합니다. Core service는 Java 21과 Spring Boot 기반 골격,
PostgreSQL Flyway schema, append-only 권한·감사 trigger와 분리된 readiness를
갖췄습니다. baseline importer는 커밋된 공개·정제 JSON 39개를 검증한 뒤 PostgreSQL에
하나의 transaction으로 넣습니다. 같은 baseline은 전체
record hash와 하위 행을 다시 확인한 뒤 중복 없이 처리하고, 같은 ID의 다른 내용이나
다른 baseline fingerprint는 덮어쓰지 않고 거부합니다. 공개 상품 관측 상태 조회
endpoint는 `asOf`와 `evaluatedAt` 분리, evidence visibility, freshness와
confirmation policy를 구현했습니다. 실제 승인 API와 runtime ingestion은 아직
구현하지 않았습니다. 합성 내부 공문은 v1/v2 계약, 별도 bootstrap importer,
Flyway V4 append-only 저장 구조와 승인 checklist schedule 제약, Asia/Seoul 업무일 변환과
내부 checklist 사용 차단 정책 기반을 갖췄습니다. 적용 공문 조회 endpoint는 `businessDate`와
`knownAt` 두 시간축으로 적용 공문과 승인 checklist schedule을 선택합니다. 수신 전 공문과 이후 schedule revision은 과거 조회에 노출하지 않고,
관계없는 중첩이나 끊긴 supersedes chain은 임의 선택하지 않고 `AMBIGUOUS`로 차단합니다.
checklist 변경안(proposal) 생성을 구현했습니다. 새 공문의 구조화 규칙과 직전 승인 checklist를 규칙 단위로 비교해 추가, 수정, 삭제와 전후 값을 결정적으로 만들고 파생 데이터로 저장합니다. 변경안은 승인이 아니며, 테스트용 승인 checklist 예시 데이터(출처 FIXTURE)는 검증과 사람 결정과 사용 허용 조건을 우회하지 않습니다.
변경안 자동 검증을 구현했습니다. 공문 원문, 기준 checklist, 공개 상품 근거와 대조해 PASS/WARN/FAIL과 세부 오류를 파생 데이터로 저장하고, 적용 공문 조회는 보이는 최신 결과로 검증 대기, 검증 실패, 오래됨, 사람 검토 대기를 구분합니다. 검증 통과는 사용 허용이 아닙니다.
사람 검토 결정(승인, 수정, 반려)과 승인 checklist 발행을 구현했습니다. 승인은 사람 결정 기록, HUMAN_REVIEW 출처 checklist, 적용 일정 revision을 한 트랜잭션으로 남기고, 적용 공문 조회는 사람 결정이 있는 checklist만 사용 허용 후보로 보며 과거·미래 조회, 공문 철회, 선택 변경·모호, 필수 공개 근거 미확인은 계속 차단합니다. 검수자 ID는 합성 값이며 인증·화면·승인 철회·정기 재검증은 구현하지 않았습니다.
검수자가 변경안의 설명 문구만 고친 경우 자동 검증은 FAIL이 아니라 WARN(`INSTRUCTION_EDITED`)으로 알리고 승인 사유를 요구합니다. 규칙 키, 변경 전후 값, 단위, 시행일, 대상 상품, 조건, 예외, 근거 필요 여부는 공문 값과 달라지면 FAIL입니다. 자동 검증은 설명 문구의 의미를 보장하지 않으므로 검수자가 원문과 고친 문구를 대조해야 합니다.
AI 서비스용 Core Tool API(읽기 전용)를 구현했습니다. AI는 업무 DB 대신 이 Tool로 "지금 적용되는 승인 checklist"와 "항목 근거"만 읽습니다. 사용 허용 여부는 Core가 결정하며 사용 불가 상태에서는 사유만 주고 항목과 근거 ID를 주지 않습니다. 근거 Tool은 서버가 소속과 사용 가능 여부를 다시 확인합니다. 서비스 인증은 test/demo 수준의 토큰 1개(환경변수)이며 사용자별 인증·권한과 FastAPI AI 서비스 자체는 구현하지 않았습니다.
변경안 생성 → 자동 검증 → 사람 결정 → 승인 checklist 조회 → AI용 Tool 조회의 Core 전체 흐름을 demo 명령 진입점부터 HTTP까지 한 번에 재현하는 연결 검증을 추가했습니다(화면 전). 전체 MVP 완료가 아니며 AI 서비스와 화면 연결 뒤 별도 전체 흐름 검증이 남아 있습니다.
AI 서비스의 첫 연결 단계로 **규칙 조립 상담 준비안과 보류**를 구현했습니다(TASK-015, 검수·완료 판정 대기). AI 서비스는 Core Tool로 승인되고 지금 사용 가능한 checklist 항목과 근거만 읽어 공문군별로 READY 또는 HOLD 섹션을 만들고, 필수 공문군 기준으로 READY/PARTIAL/HOLD 전체 상태를 정한 뒤 Tool이 아닌 별도 기록 경로로 Core에 남깁니다. Core는 저장 직전에 매핑과 승인·근거 상태를 직접 다시 확인해 사용 불가 준비안의 저장을 거부하고, 기록은 사용 허가가 아닙니다. READY는 "기준 자료 준비 완료"이며 고객별 적용 조건·제출서류 확인이나 상담·대출 결정이 끝났다는 뜻이 아닙니다. 고객 니즈 확인과 상품 선택은 이 기능의 앞 단계이며 대신하지 않습니다. 준비안의 모든 문장은 승인 checklist 항목·공문 원문·구조화 값·고정 안내 문구에서만 오며 LLM 생성은 없습니다. "AI 생성 완료"나 "LLM 안전성 검증 완료"가 아니며 LLM 생성과 그 검증은 TASK-019입니다. Core(Java)와 AI 서비스(Python CLI)를 함께 실행하는 연결 검증이 필수 CI에서 실제로 실행됩니다.
최종 기획서와의 대표 시나리오 차이를 해소하기 위해 중도상환수수료율 1.2퍼센트에서
0.8퍼센트로의 변경, 시행일, 적용 조건과 예외를 구조화한 합성 공문 v1/v2를 추가했습니다.
Flyway V5와 synthetic importer, 적용 공문 조회 API가 이 구조화 변경을 보존하고 반환합니다.
기존 셀러론 법인 한도 20억원 교차 검증 자료는 보조 검증 사례로 유지합니다.

검증 명령은 다음과 같습니다.

```bash
python3 -m unittest discover -s tests -v
```

비공개 원문을 포함한 전체 검증은 다음과 같이 실행합니다.

```bash
TRUSTAGENT_REQUIRE_PRIVATE_SNAPSHOTS=1 python3 -m unittest discover -s tests -v
```

검증 결과 Python 60개 테스트가 모두 통과했습니다. 상세 증거는
`docs/evidence/PUBLIC_KB_OBSERVATION_HARDENING_EVIDENCE.md`와
`docs/evidence/PUBLIC_PRODUCT_OBSERVED_STATE_EVIDENCE.md`, `docs/evidence/SYNTHETIC_NOTICE_SCHEMA_EVIDENCE.md`,
`docs/evidence/INTERNAL_POLICY_APPLICABLE_QUERY_EVIDENCE.md`, `docs/evidence/CHECKLIST_PROPOSAL_GENERATION_EVIDENCE.md`에 기록합니다.

Core service의 clean·offline 검증은 다음과 같습니다. Docker daemon이 필요하며 고정된
PostgreSQL 18.6 image digest를 사용합니다.

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew clean test bootJar --offline --no-daemon
```

Java 테스트 106개와 executable jar 생성이 통과했습니다. 상세
증거는 `docs/evidence/CORE_SERVICE_SCHEMA_AUDIT_EVIDENCE.md`와
`docs/evidence/PUBLIC_PRODUCT_BASELINE_IMPORTER_EVIDENCE.md`, `docs/evidence/PUBLIC_PRODUCT_OBSERVED_STATE_EVIDENCE.md`,
`docs/evidence/SYNTHETIC_NOTICE_SCHEMA_EVIDENCE.md`, `docs/evidence/INTERNAL_POLICY_APPLICABLE_QUERY_EVIDENCE.md`에 기록합니다.

공개 상품 3개를 수집하는 명령은 다음과 같습니다.

```bash
python3 scripts/collect_public_kb_snapshots.py --all
```

검증된 Observation에서 상품 조건 version, quote와 evidence를 생성하는 명령은 다음과
같습니다.

```bash
python3 scripts/extract_public_kb_product_facts.py --all
```

## 후속 운영 방향

향후 공개 상품 변경 감지용 Spring Batch Job과 담당자 검토 흐름을 추가합니다. 실제 업무에서는 내부 공문을 공식 변경 기준으로 사용하고, 공개 상품 정보는 공문 내용의 교차 검증과 고객 공개 정보 변경 감지에 활용합니다. Batch가 감지한 변경은 자동 적용하지 않고 검토 대기 상태로 저장하는 방향으로 구현합니다.

## 2주 MVP 목표

다음 흐름을 처음부터 끝까지 재현하면 MVP가 완료된 것으로 판단합니다.

1. KB 공개 상품 3개의 snapshot과 정규화된 fact 조회
2. 요청 기준일에 유효한 KB 셀러론 fact 선택
3. 합성 내부 공문과 공개 fact 연결
4. 중도상환수수료율 1.2퍼센트에서 0.8퍼센트로의 변경 전후, 시행일, 조건과 예외 확인
5. 체크리스트 변경 후보 생성과 숫자 및 시행일 오류 차단
6. 자동 검증과 별도로 공문 담당 부서 검수자의 수정 및 제공 승인 기록
7. 합성 기업과 신청 건의 상담 준비안 생성
8. 근거와 감사 이력을 포함한 행원의 최종 확인 기록
9. AI 서비스 중단 시 수기 체크리스트 제공과 AI 확정 경로 차단

## 제외 범위

- 실제 KB 내부 문서, API, 코드, 조직 정보
- 실제 고객, 기업, 여신, 신청, 상담, 승인 데이터
- 대출 승인과 거절 결정
- 신용평가
- 최종 한도와 금리 산정
- Kubernetes와 고가용성 운영 구성

## 프로젝트 기록

- 개발 기준: [CLAUDE.md](CLAUDE.md), [개발 규칙](docs/development/DEVELOPMENT_RULES.md)
- Task/검수: [Task 템플릿](docs/development/TASK_TEMPLATE.md), [검수 체크리스트](docs/development/REVIEW_CHECKLIST.md)
- 첫 기준선 점검: [TASK-000](docs/tasks/TASK-000_초기-수집-자산-기준선-점검.md)
- 폐기된 Pre-SDLC 계획 문서는 TASK-000의 DROP 기록과 Git history에서 확인합니다.

- 기술 결정: `docs/adr/`
- 데이터 계약: `contracts/`
- 공개 KB 스냅샷과 합성 데이터 계약 검증 기록: `docs/evidence/PUBLIC_KB_SNAPSHOT_CONTRACTS_EVIDENCE.md`
- 공개 KB 스냅샷 수집기 검증 기록: `docs/evidence/PUBLIC_KB_SNAPSHOT_COLLECTOR_EVIDENCE.md`
- 공개 KB 상품 fact 정규화와 버전 관리 검증 기록: `docs/evidence/PUBLIC_KB_PRODUCT_FACTS_EVIDENCE.md`
- 공개 KB 관측 파이프라인 hardening 검증 기록: `docs/evidence/PUBLIC_KB_OBSERVATION_HARDENING_EVIDENCE.md`
- Core service와 공개 상품 조회 종합 검증 기록: `docs/evidence/CORE_SERVICE_PUBLIC_PRODUCT_SUMMARY_EVIDENCE.md`
- Core service schema와 append-only 감사 검증 기록: `docs/evidence/CORE_SERVICE_SCHEMA_AUDIT_EVIDENCE.md`
- 공개 상품 baseline importer 검증 기록: `docs/evidence/PUBLIC_PRODUCT_BASELINE_IMPORTER_EVIDENCE.md`
- 공개 상품 관측 상태 조회 검증 기록: `docs/evidence/PUBLIC_PRODUCT_OBSERVED_STATE_EVIDENCE.md`
- 합성 내부 공문 계약과 저장 구조 검증 기록: `docs/evidence/SYNTHETIC_NOTICE_SCHEMA_EVIDENCE.md`
- 적용 공문 기준일 조회 검증 기록: `docs/evidence/INTERNAL_POLICY_APPLICABLE_QUERY_EVIDENCE.md`
- checklist 변경안 생성 검증 기록: `docs/evidence/CHECKLIST_PROPOSAL_GENERATION_EVIDENCE.md`
- checklist 변경안 자동 검증 검증 기록: `docs/evidence/CHECKLIST_PROPOSAL_AUTOMATED_VALIDATION_EVIDENCE.md`
- 사람 검토 결정과 승인 checklist 발행 검증 기록: `docs/evidence/HUMAN_REVIEW_DECISION_EVIDENCE.md`
- 변경안 검사 기준 조정(설명 문구 수정 허용) 검증 기록: `docs/evidence/VALIDATION_INSTRUCTION_EDIT_EVIDENCE.md`
- AI 서비스용 Core Tool API 검증 기록: `docs/evidence/CORE_TOOL_API_EVIDENCE.md`
- Core 전체 흐름 연결 검증 기록(화면 전, demo 명령 진입점 → 조회·Tool): `docs/evidence/CORE_END_TO_END_FLOW_EVIDENCE.md`
- AI 서비스 규칙 조립 상담 준비안·보류와 Core 기록 경로 검증 기록: `docs/evidence/CONSULTATION_PREPARATION_EVIDENCE.md`
- 최종 기획서 정합화 검증 기록: `docs/evidence/FINAL_PROPOSAL_ALIGNMENT_EVIDENCE.md`
- Oracle 전환 위험 검증 spike 기록(중단): `docs/evidence/TASK-003_ORACLE_SPIKE_EVIDENCE.md`

## 구현 상태 기록 원칙

최종 기획서는 제품 기준선으로 사용합니다. 실제 구현 상태는 이 README에 기록하며 완료 조건과 검증 증거가 없는 기능은 완료로 표시하지 않습니다.
