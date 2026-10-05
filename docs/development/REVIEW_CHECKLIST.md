# 기능 완료 전 인간 검수 체크리스트

Task / 검수자 / 검수 대상 revision 또는 PR:
확인 후 체크하고 해당 없음은 Task에 이유를 적는다. AI는 인간 검수 결과를 대신 작성하지 않는다.

## Requirement / 요구사항
- [ ] 최신 승인 기준과 업무 가치, Task 범위가 일치한다.
- [ ] Acceptance Criteria가 구현 전에 정의되었고 변경 이유/결정자/승인 범위/검토 대상 revision이 기록되었다.

## Architecture / 아키텍처
- [ ] Java + Spring Boot Core가 PostgreSQL 업무 DB와 업무 규칙/권한/상태/트랜잭션/감사를 소유한다.
- [ ] Python + FastAPI AI는 Core Tool API로만 필요한 업무 데이터를 조회하며 업무 DB 직접 접근이 없다.
- [ ] Redis는 단기 상태/중복 방지/캐시이며 LangGraph는 상태/분기/재시도가 필요한 workflow에만 사용한다.
- [ ] 현재 구현과 목표 baseline을 구분하고 pgvector/PostgreSQL 검색을 현재 baseline으로 사용하지 않는다.

## Business Flow / 업무 흐름
- [ ] Vertical Slice의 입력부터 상담 전/중 업무 결과와 원문 근거까지 연결된다.
- [ ] AI가 대출 승인/거절 또는 금리·한도를 확정하지 않는다.

## State / 상태
- [ ] 허용/금지 전이, 승인/반려/철회와 시행일/버전 경계를 확인했다.
- [ ] AI 초안, 자동 검증, 인간 검수와 제공 승인이 구분된다.

## Data / 데이터
- [ ] 공개/합성/파생 경계, 원문 ID/위치/hash/버전/시각과 불변 조건을 보존한다.
- [ ] 원천 DB와 검색 인덱스 정합성 및 재처리 방식을 확인했다.

## Transaction / 트랜잭션
- [ ] 업무 트랜잭션 경계와 rollback, 감사 기록의 일관성을 검증했다.
- [ ] 장시간 AI/외부 호출을 업무 DB 트랜잭션에 유지하지 않는다.

## Concurrency / 동시성
- [ ] 동시 승인/중복 활성화/기간 중첩/중복 요청을 서버와 DB 제약으로 차단한다.
- [ ] 재시도/멱등성과 충돌 처리를 검증하고 Redis가 DB 제약을 대체하지 않는다.

## Security / 보안과 권한
- [ ] 사용자/서비스/담당 범위 권한, actor 위조, Tool allowlist와 최소 데이터 제공을 확인했다.
- [ ] 비밀/민감정보를 보호하고 문서 안의 지시를 데이터로 취급한다.

## AI-RAG / 검색과 ingestion
- [ ] ES BM25 + dense vector k-NN + metadata filter + reranker 기준을 지킨다.
- [ ] 단순 Hybrid vs Ontology-aware Retrieval을 경량 도메인 관계/structured metadata부터 비교했다. Graph DB/GraphRAG를 기본 도입하지 않는다.
- [ ] 정확도/관계 표현력/설명 가능성/유지보수 복잡도/1인 범위/평가 가능성으로 판단했다.
- [ ] 미승인/구버전/권한 밖 근거를 제외하고 사전 평가 지표와 골든셋으로 검증했다.
- [ ] PDF를 전제하지 않고 포맷별 parser + 공통 normalized document와 원문 위치를 검토했다.
- [ ] Native parsing 우선이며 무 text layer/품질 미달에서만 OCR fallback 후보를 비교하고 숫자/시행일 오류를 검증한다.

## Error Handling / 실패 처리
- [ ] 근거 부족/충돌/최신성 실패/장애 시 보류 또는 차단한다.
- [ ] 복구/재처리/관측과 AI 장애 시 수기 업무 경로를 확인했다.

## Test / 테스트
- [ ] AC와 정상/경계/권한/동시성/실패 테스트가 연결되고 각 테스트의 보장 범위를 설명한다.
- [ ] 검증 대상 revision/환경/명령/결과/evidence와 미실행/skip/실패를 기록했다.

## Code Review / 코드 리뷰
- [ ] AI self-review의 누락/오류/불필요한 복잡도/잔여 위험을 검토했다.
- [ ] 승인 범위 밖 변경이나 기존 미커밋 작업 혼입이 없다.

## Documentation / 문서화
- [ ] Task의 AI 제안 및 인간 판단 기록에 이유/피드백/수정 결과/최종 판단이 있다. 중요한 수정/거절이 없으면 `없음`으로 적었다.
- [ ] 큰 결정은 ADR, 기능 요약은 PR, 실제 구현 상태는 코드/README와 일치한다.
- [ ] 한국어 Task/Issue/PR 제목과 영문 Conventional Commit 타입 + 한국어 설명을 준수한다.
- [ ] Pre-SDLC Asset을 소급해 AI-native로 기록하지 않는다.

## Explainability Gate

**코드가 동작하더라도 핵심 흐름과 기술 선택 이유를 자신의 말로 설명할 수 없으면 완료가 아니다.**

인간 검수자는 아래를 자신의 말로 설명하고 Task에 내용/확인 근거를 남긴다.

- [ ] 기능 필요성
- [ ] 요청 처리 흐름
- [ ] 서비스 책임
- [ ] 읽고 쓰는 데이터
- [ ] 상태 전이
- [ ] 트랜잭션 경계
- [ ] 실패 처리
- [ ] 각 테스트가 보장하는 것
- [ ] AI 주요 제안을 왜 채택했는지
- [ ] 수정/거절한 제안이 있다면 어떤 기준 때문인지 (없으면 없음)

설명하지 못하면 검수 대기/수정 필요로 두고 보완 후 재검수한다. 자동 검증 통과만으로 Gate를 통과시키지 않는다.

## 최종 판정
- [ ] 완료 (AC/자동 검증/인간 검수/설명 Gate/판단 기록 충족)
- [ ] 수정 필요
- [ ] 보류

결정자 / 이유 / 승인 범위 / 검토 대상 revision 또는 PR / 후속 Task:
