# TASK-000 초기 수집 자산 기준선 점검

- 성격: **AI-native SDLC 도입 이전 자산을 현재 기준으로 재평가하는 Baseline Audit**. AI-native SDLC의 첫 baseline audit 문서다.
- 작성/갱신일: 2026-10-04
- 상태: 문서 기준 정리 및 승인된 문서 DROP 실행 완료. 데이터/코드/설정 등 전체 자산 audit는 미완료.
- 요구사항/결정 출처: 2026-10-04 사용자의 저장소 문서 정리 요청(삭제 대상, 기준 문서 내용, 커밋 메시지 명시).
- 범위: 개발 기준 문서 정리, 아래 10개 문서 DROP 기록/삭제, Git history 보존. 애플리케이션 코드/데이터/DB 전환은 제외.

## Goal과 baseline 원칙

기존 문서는 `Pre-SDLC Asset`으로 표시한다. 과거 작업을 소급해서 AI-native였다고 기록하지 않는다. 과거 ADR/evidence는 당시 판단과 실행 근거로 보존하며 최신 기술 기준의 승인이나 현재 테스트 통과로 간주하지 않는다.
기존 저장소와 Git history를 유지한다. force push/history rewrite/새 repo 생성을 하지 않는다. 현재 기준은 [CLAUDE.md](../../CLAUDE.md)와 [개발 규칙](../development/DEVELOPMENT_RULES.md)이다.

## 사전 Acceptance Criteria

이번 문서 작업의 기준은 사용자가 구현 전에 지정한 요청이다. 결정자: 사용자 / 일자: 2026-10-04.

| ID | 기준 | 검증 방법 | 결과 |
|---|---|---|---|
| AC-01 | 지정 문서 10개를 Pre-SDLC Asset / DROP으로 기록하고 실제 삭제 | 아래 표와 파일 존재 확인 | 충족 |
| AC-02 | CLAUDE 및 개발 규칙/Task 템플릿/검수 체크리스트에 요청 기준 반영 | 요구사항 대조와 diff | 충족 |
| AC-03 | RAG 대안 비교, 포맷 독립, native parsing 우선/OCR 최소화 반영 | 기준 문서 대조 | 충족 |
| AC-04 | 이번 문서 변경만 커밋하고 기존 코드/데이터 변경과 history 보존 | 선택 staging, Git status/diff, 이전 HEAD ancestor 확인 | 커밋 전/후 확인 |
| AC-05 | 후속 audit 대상과 미완료 범위 구분 | 후속 표와 검수 기록 확인 | 충족 |

Acceptance Criteria 변경 이력: 변경 없음. 이후 변경 시 변경 이유 / 일자 / 결정자 / 전후 / 재검증 영향을 기록한다.

## Implementation Plan

Git 상태/문서 구조 확인 → 기존 수정 문서와 기준 초안 외부 백업 → DROP 기록 → 기준 문서 갱신 → 승인된 문서 삭제 → 요구사항/참조/diff 검증 → 선택 staging → 지정 메시지 커밋.
인간 승인 범위: 사용자가 위 작업과 삭제, 커밋을 명시적으로 요청했다. 코드/데이터/인프라 변경은 포함하지 않는다.

## 문서 자산 판정과 DROP 실행

공통 DROP 이유 D1:

> AI-native SDLC 도입 이전에 작성된 계획·설계 문서이며 현재 기획 및 아키텍처의 Source of Truth로 사용하지 않는다. 필요한 설계는 현재 기준으로 해당 Task에서 새로 작성한다.

| 자산 | 도입 구분 | 판정 | 이유 | 실행 |
|---|---|---|---|---|
| `docs/CI_AND_AI_REVIEW.md` | Pre-SDLC Asset | DROP | D1 | 삭제 실행 |
| `docs/DAY_01_PLAN.md` | Pre-SDLC Asset | DROP | D1 | 삭제 실행 |
| `docs/DAY_02_PLAN.md` | Pre-SDLC Asset | DROP | D1 | 삭제 실행 |
| `docs/DAY_03_PLAN.md` | Pre-SDLC Asset | DROP | D1 | 삭제 실행 |
| `docs/DAY_03_HARDENING_PLAN.md` | Pre-SDLC Asset | DROP | D1 | 삭제 실행 |
| `docs/DAY_04_PLAN.md` | Pre-SDLC Asset | DROP | D1 | 삭제 실행 |
| `docs/DAY_05_PLAN.md` | Pre-SDLC Asset | DROP | D1 | 삭제 실행 |
| `docs/FINAL_PROPOSAL_ALIGNMENT.md` | Pre-SDLC Asset | DROP | D1 | 삭제 실행 |
| `docs/PROJECT_CONTEXT.md` | Pre-SDLC Asset | DROP | D1 | 삭제 실행 |
| `docs/PUBLIC_PRODUCT_ERD.md` | Pre-SDLC Asset | DROP | D1 | 삭제 실행 |

위 10개 문서는 현재 작업 트리에서 삭제한다. 추적 문서는 Git history로 확인할 수 있다. 미커밋 수정 및 미추적 문서도 삭제 요청에 포함되어 외부 임시 백업에 보존했다. 기존 TASK-000 초안은 요청한 파일명으로 정리하고 Task 기록은 유지한다.

| 현재 기준 자산 | 구분 | 판정 | 역할 |
|---|---|---|---|
| CLAUDE.md | 도입 기준 초안 | MODIFY | 목적/Source of Truth/기술 및 AI 권한 경계 |
| docs/development/DEVELOPMENT_RULES.md | 도입 기준 초안 | MODIFY | 표준 사이클/개발/검증/언어 규칙 |
| docs/development/TASK_TEMPLATE.md | 도입 기준 초안 | MODIFY | 업무 가치/사전 AC/AI 제안과 인간 판단 기록 |
| docs/development/REVIEW_CHECKLIST.md | 도입 기준 초안 | MODIFY | 검수 및 Explainability Gate |
| 본 TASK-000 | 도입 audit 초안 | MODIFY | 첫 baseline audit와 후속 자산 점검 기록 |
| docs/adr/, docs/evidence/ | Pre-SDLC Asset 포함 | KEEP (역사 보존) | 당시 결정/검증의 근거, 최신 설계로 자동 승격하지 않음 |

## 다음 자산 audit 범위

이 Task 이후 점검할 대상은 **데이터 / 수집 스크립트 / 기존 코드 / DB 및 검색 설정 / Docker·환경 설정 / 테스트 / CI** 등이며 `KEEP / MODIFY / DROP / NEW`로 판정한다. KEEP은 유지, MODIFY는 수정, DROP은 폐기, NEW는 신규 필요다. 기존 자산은 Pre-SDLC Asset으로 표시하고 NEW는 기존 자산이 아니다. 아래는 점검 목록이지 확정 판정이 아니다.

| 대상 | 실제 경로/항목 | 점검 기준/필요한 evidence |
|---|---|---|
| 데이터 | datasets/public/kb, datasets/derived, datasets/synthetic, contracts, 비공개 원문 | 출처/hash/분류/schema/참조/시행일/조건/예외 및 공개 범위 |
| 수집 스크립트 | scripts/collect_public_kb_snapshots.py, extract_public_kb_product_facts.py, public_product_freshness.py, migrate_public_kb_pipeline_v2.py | native 수집/정규화/추적/중복/재관측/실패 및 회귀 |
| 기존 코드 | apps/core-service, apps/ai-service, apps/frontend | 구현/placeholder 구분, 업무 규칙/권한/상태/감사, Core Tool API 경계 |
| DB 및 검색 설정 | Core application.yml, db/migration, 검색 관련 구성 | PostgreSQL 현 구현과 Oracle 목표 차이, 이전/rollback, ES 인덱스/정합성/평가 |
| Docker·환경 | infra/docker, 로컬 실행/환경변수/의존성 구성 | Oracle/ES/Redis 필요 범위, 재현성/비밀/기동/장애 |
| 테스트 | tests, Core 단위/통합 테스트 | 각 테스트 보장 범위, 현 환경 재실행, 숫자/시행일/권한/동시성/실패 |
| CI | .github/workflows, Gradle wrapper/lock/verification | 실제 checks/실행 환경/skip, 목표 기술 전환 시 필요한 검증 |

각 항목의 판정 이유, 대안, 위험, 검증 결과와 인간 판단을 추가 기록한다. 수정/삭제/신규 구현은 별도 승인된 Task 범위에서 수행한다. 이번 작업으로 Oracle 전환, ES 구축, 전체 데이터 품질 검증이 완료된 것은 아니다.

## AI 제안 및 인간 판단 기록

### 제안 1: 과거 계획 보존안 수정

- AI 제안: 기존 초안은 Day 계획/PROJECT_CONTEXT/정합화 문서를 유지 또는 수정하는 방안이었다.
- 판단: 수정 (사용자의 이번 명시적 요청).
- 판단 기준 체크: 기획 일치성, 아키텍처, 복잡도, 설명 가능성. 나머지 데이터/트랜잭션·동시성/보안·권한/실패·운영/테스트 가능성은 이번 문서 변경 영향 검토에 적용한다.
- 결정 이유: 사용자는 위 공통 DROP 이유를 지정하고 최신 기준에서 필요한 설계를 해당 Task에 새로 작성하도록 결정했다.
- AI에게 전달한 피드백: 지정 10개 문서를 DROP 기록 후 실제 삭제하고 기존 repo/history 및 새 기준 문서를 유지한다.
- AI 수정 결과: DROP 표/삭제 및 최신 규칙을 반영. 과거 ADR/evidence와 기존 코드/데이터는 보존.
- 최종 판단: 문서 폐기와 정리 실행은 사용자 요청으로 승인됨. 결과물의 인간 최종 검수는 아직 미기록.

그 외 중요한 AI 제안 수정/거절: 없음.

## Implementation Result와 AI self-review

기준 문서 4개 및 본 Task를 정리하고 문서 10개를 삭제한다. 삭제된 문서를 요구하는 AGENTS 시작 안내와 README 기록 안내만 이번 변경으로 정리한다. 기존 수정 중이던 README/AGENTS의 다른 변경은 커밋에서 분리한다.
검증: 파일 목록, 필수 요구사항 대조, 새 문서 상대 링크, 삭제 경로 존재 여부, diff 공백/범위, 기존 변경 보존, 커밋 전후 history 확인. 문서 변경이므로 애플리케이션 테스트/수집/DB 실행은 하지 않는다. 과거 evidence를 현재 검증으로 기록하지 않는다.
기존 evidence의 과거 계획 참조는 역사적 맥락으로 남기며 Git history에서 확인한다.

## 인간 검수와 Explainability Gate

[검수 체크리스트](../development/REVIEW_CHECKLIST.md)를 사용한다. 결과물에 대한 인간 검수/설명 Gate: 미기록. 사용자의 실행 지시를 결과물 검수 통과로 기록하지 않는다.
코드가 동작하더라도 핵심 흐름과 기술 선택 이유를 자신의 말로 설명할 수 없으면 완료가 아니다.
문서 정리 실행과 전체 baseline audit 완료를 구분한다. 다음 단계는 위 자산별 inventory/실제 검증/인간 판정이며 사전 AC와 범위를 정한 뒤 진행한다.
