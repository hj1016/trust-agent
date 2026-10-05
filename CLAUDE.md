# TrustAgent AI 개발 기준

Claude를 포함한 AI 개발 도구는 작업 전에 이 문서와 개발 규칙을 읽는다.

## 목적과 책임

TrustAgent는 행원이 읽어야 할 공문의 변경사항을 상담 전/중 필요한 근거로 연결하는 AI 업무지원 플랫폼이다. 공문 열람 책임과 인간 검수를 대체하지 않는다. AI는 대출 승인/거절 또는 금리·한도 확정 주체가 아니다. 신용등급도 결정하지 않는다.
PUBLIC_KB, SYNTHETIC_INTERNAL, SYNTHETIC_WORK, DERIVED를 저장소, 계약, API, 화면, 테스트에서 구분하고 합성 자료를 실제 은행 내부자료처럼 표현하지 않는다.

## Source of Truth 우선순위

1. 인간이 승인한 현재 기준 문서: 이 문서와 [개발 규칙](docs/development/DEVELOPMENT_RULES.md), 최신 승인 요구사항.
2. 현재 기준과 일치하는 최신 승인 ADR (`docs/adr/`). 상충하는 과거 ADR은 역사적 결정이며 새 ADR로 대체 관계를 명시한다.
3. 승인된 Architecture Baseline. 아직 독립 문서가 없으므로 아래 기술 경계를 임시 기준으로 사용하고 후속 Task에서 확정한다.
4. 승인된 Task와 사전 Acceptance Criteria (`docs/tasks/`).
5. 코드, 계약, 테스트: 실제 구현 상태의 근거.
6. README: 실제 구현 현황과 사용 안내.

오래된 PPT/PDF/과거 계획 문서보다 최신 승인 문서와 ADR이 우선한다. 같은 수준의 충돌은 출처와 차이를 Task에 적고 인간 판단으로 해결한다. 목표 아키텍처와 현재 구현 상태를 구분한다. 문서 갱신만으로 기술 전환 완료를 선언하지 않는다.

## 최신 기술 경계

- Core: Java + Spring Boot. 업무 규칙/권한/상태/트랜잭션/감사를 소유한다.
- Core 업무 DB: Oracle. 기존 PostgreSQL 구현은 Pre-SDLC Asset이며 별도 전환 Task의 검증 대상이다.
- Redis: 단기 상태 / 중복 방지 / 캐시. 영구 업무 원장이나 DB 무결성 제약을 대체하지 않는다.
- AI Service: Python + FastAPI. Oracle 직접 접근 및 업무 DB 자격증명 보유를 금지한다. Core Tool API를 통해 필요한 업무 데이터만 조회한다.
- Search: Elasticsearch, BM25 + dense vector k-NN + metadata filter + reranker. pgvector/PostgreSQL 검색은 현재 baseline이 아니다. 검색 인덱스는 업무 원장이 아니다.
- LangGraph: 모든 AI 기능에 적용하지 않고 상태/분기/재시도가 필요한 workflow에만 사용한다.

## 계획과 인간 판단

구현 전에 요구사항, 사전 Acceptance Criteria, 대안, 영향, 검증을 포함한 계획을 제시하고 인간이 승인한 범위에서만 구현한다. 이미 승인한 범위에 재승인을 반복하지 않는다.
AI 제안은 정답이 아니며 채택 / 수정 / 거절 가능하다. 각 Task는 사전 정의된 Acceptance Criteria로 판정하고 결과에 맞춰 사후 완화하지 않는다. 중요한 판단은 Task의 `AI 제안 및 인간 판단 기록`에 남긴다. 인간의 승인과 설명 가능성 검수를 AI가 대신 기록하지 않는다.

## RAG와 원문 ingestion

단순 Hybrid Retrieval과 Ontology-aware Retrieval을 비교한다. Graph DB/GraphRAG는 기본값이 아니며 경량 도메인 온톨로지와 structured metadata를 먼저 검토한다. 상세 판단 기준은 개발 규칙을 따른다.
공문이 특정 형식(PDF)이라고 전제하지 않는다. `원문 문서` 기준으로 설계하고 포맷별 parser + 공통 normalized document 구조를 고려한다. 실제 입력 자료를 확인한 구현 Task에서 MVP 지원 포맷과 확장 범위를 결정한다.
Native parsing 우선. OCR은 가급적 사용하지 않으며 text layer가 없거나 extraction 품질이 기준 미달인 경우에만 fallback 후보로 검토한다. 품질 기준, 대안, 숫자/시행일 오류와 비용을 구현 Task에서 비교·판단하고 OCR 결과도 원문 근거와 검증한다.

## 기록과 작업 시작

도입 이전 자산은 Pre-SDLC Asset이다. 과거 작업을 소급해서 AI-native였다고 기록하지 않는다. TASK-000에서 KEEP / MODIFY / DROP / NEW로 재평가한다. 과거 ADR/evidence와 Git history는 보존한다.
작업 전 이 문서 → 개발 규칙 → 관련 최신 ADR/Task → 코드/README 순으로 확인한다. [Task 템플릿](docs/development/TASK_TEMPLATE.md)과 [검수 체크리스트](docs/development/REVIEW_CHECKLIST.md)를 사용한다.
Task/Issue/PR 제목은 한국어 중심이다. Commit은 feat/fix/test/refactor/docs/chore 영문 타입 + 한국어 변경 설명이며 코드에서 무엇이 바뀌었는지만 기록한다. Claude 사용 사실이나 인간 판단 과정은 넣지 않는다. AI 판단 근거는 Task, 큰 설계 결정은 ADR, 기능 단위 요약은 PR에 남긴다.
반복되는 업무별 실행 지침은 향후 project-local skill/runbook 형태로 분리할 수 있다. 지금은 Task/개발 규칙 문서가 우선이며 별도 skills 폴더를 만들지 않는다.
