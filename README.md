# TrustAgent

공개 상품 정보와 합성 내부업무 데이터를 구분하고 버전 관리, 자동 검증, 사람 승인을 적용한 은행 기업여신 상담과 심사 준비용 Agentic AI 플랫폼

## 현재 상태

공개 상품 snapshot 수집과 정규화 경로를 완성했습니다. Catalog의 공식 KB URL을 검증하고 raw HTML을 비공개 content-addressed artifact로 저장하며, SHA-256 manifest를 자동 생성합니다. 검증된 snapshot에서 정규화된 product fact와 의미 단위 product version을 생성하고 각 fact에 원문 locator와 evidence hash를 연결합니다. Spring Boot, DB 적재와 요청 기준일 조회 API는 아직 구현하지 않았습니다.

검증 명령은 다음과 같습니다.

```bash
python3 -m unittest discover -s tests -v
```

비공개 원문을 포함한 전체 검증은 다음과 같이 실행합니다.

```bash
TRUSTAGENT_REQUIRE_PRIVATE_SNAPSHOTS=1 python3 -m unittest discover -s tests -v
```

2026-09-23 실행 결과 비공개 원문을 포함한 36개 테스트가 모두 통과했습니다. 상세 증거는 `docs/evidence/DAY_03_EVIDENCE.md`에 기록합니다.

공개 상품 3개를 수집하는 명령은 다음과 같습니다.

```bash
python3 scripts/collect_public_kb_snapshots.py --all
```

검증된 snapshot에서 product version과 fact를 생성하는 명령은 다음과 같습니다.

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
4. 체크리스트 변경 후보 생성
5. 원문에는 법인 한도가 20억원인데 2억원으로 작성된 오류 차단
6. 자동 검증과 별도로 규정담당자의 수정 및 승인 기록
7. 합성 기업과 신청 건의 상담 준비안 생성
8. 근거와 감사 이력을 포함한 행원의 최종 승인 기록
9. AI 서비스 중단 시 수기 체크리스트 제공과 AI 확정 경로 차단

## 제외 범위

- 실제 KB 내부 문서, API, 코드, 조직 정보
- 실제 고객, 기업, 여신, 신청, 상담, 승인 데이터
- 대출 승인과 거절 결정
- 신용평가
- 최종 한도와 금리 산정
- Kubernetes와 고가용성 운영 구성

## 프로젝트 기록

- 프로젝트 기준: `docs/PROJECT_CONTEXT.md`
- CI와 리뷰 기준: `docs/CI_AND_AI_REVIEW.md`
- 첫날 계획: `docs/DAY_01_PLAN.md`
- 둘째 날 계획: `docs/DAY_02_PLAN.md`
- 셋째 날 계획: `docs/DAY_03_PLAN.md`
- 기술 결정: `docs/adr/`
- 데이터 계약: `contracts/`
- 첫날 검증 증거: `docs/evidence/DAY_01_EVIDENCE.md`
- 둘째 날 검증 증거: `docs/evidence/DAY_02_EVIDENCE.md`
- 셋째 날 검증 증거: `docs/evidence/DAY_03_EVIDENCE.md`

## 구현 상태 기록 원칙

최종 기획서는 제품 기준선으로 사용합니다. 실제 구현 상태는 이 README에 기록하며 완료 조건과 검증 증거가 없는 기능은 완료로 표시하지 않습니다.
