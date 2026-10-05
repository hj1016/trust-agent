# TASK-002 일회성 공개 상품 pipeline v2 migrate 스크립트 DROP 실행

- 상태: **승인됨, 실행 대기** (의존 조건: TASK-001 병합)
- 담당자 / 인간 결정자: AI(참조 조사, 실행, 검증) / 사용자(실행 승인, 검수)
- 요구사항 출처: [자산 audit](TASK-000_자산-audit-초안.md) 4.2 및 7절 제안 3 채택(결정자 사용자). 조건: 실제 삭제 전 사용처, 문서 참조, 복구용 사본, 대상 목록 확인 후 정확한 삭제와 참조 정리 범위를 Task에 명시.
- 관련: [TASK-001](TASK-001_미커밋-Day-5-자산-보존.md) 이후 실행

## Goal / 관련 요구사항

- Goal: 목적을 다한 일회성 마이그레이션 도구와 전용 테스트를 제거해 유지 대상을 줄인다. KEEP 테스트의 동작은 바뀌지 않는다.
- 사용자: 저장소 관리자.
- 사전조건: TASK-001 병합(작업 트리가 깨끗한 상태에서 삭제 PR을 분리하기 위해).
- 입력: 참조 조사 결과(아래).
- 업무규칙: DROP 판정과 삭제 실행 승인은 별개. 삭제 전 사본과 목록 확인. 참조 정리 외 로직 변경 없음.
- Out of Scope: 다른 공개 pipeline 스크립트, 데이터, 문서 삭제.

## 참조 조사 결과 (사실)

`git grep -n "migrate_public_kb"` 와 미커밋 문서 grep 결과:

| 위치 | 내용 | 처리 |
|---|---|---|
| `scripts/migrate_public_kb_pipeline_v2.py` (272 LOC) | 삭제 대상 본체. 레거시 입력 경로 `datasets/public/kb/product-versions`는 존재하지 않음 | **삭제** |
| `tests/contract/test_public_product_versions.py:12` | `from scripts import migrate_public_kb_pipeline_v2 as migration` | **import 제거** |
| `tests/contract/test_public_product_versions.py:256~` | `test_private_migration_is_deterministic_with_fixed_run_ids` (전용 테스트, 비공개 artifact 없으면 skip) | **테스트 메서드 삭제** |
| `tests/contract/test_public_product_versions.py:227` | KEEP 테스트 `test_private_artifacts_reextract_to_committed_golden_files`가 `migration.EXTRACTION_RUN_ID` 사용. 커밋된 golden file이 이 run ID로 생성됨 | **상수를 테스트 파일 모듈 상수로 이동.** 값은 스크립트 23행과 동일: `"run:" + uuid5(NAMESPACE_URL, "trustagent:day3-hardening:extraction:v1").hex`. 값이 바뀌면 golden 비교가 깨지므로 이동 전후 값 동일성을 단언 |
| `scripts/migrate_public_kb_pipeline_v2.py:22` `COLLECTION_RUN_ID` | 테스트에서 직접 사용 없음(스크립트 내부용) | 삭제와 함께 소멸. 커밋된 collection attempt의 run_id 값은 데이터에 이미 들어 있어 영향 없음 |
| `docs/tasks/TASK-000_초기-수집-자산-기준선-점검.md:69` | audit 점검 목록에 파일명 언급 | **유지** (역사 기록) |
| `docs/tasks/TASK-000_자산-audit-초안.md` | DROP 판정 기록 | **유지** |
| `docs/evidence/DAY_03_HARDENING_EVIDENCE.md` 등 커밋된 evidence | 마이그레이션 실행 기록이 있을 수 있음 | **유지** (과거 evidence 덮어쓰기 금지). 실행 전 grep으로 확인해 목록에 추가 |
| README, AGENTS, CI | 참조 없음 | 변경 없음 |

복구용 사본: 삭제 직전 `scripts/migrate_public_kb_pipeline_v2.py`와 `tests/contract/test_public_product_versions.py` 원본을 `/Users/faker/Dev/trust-agent-backups/`에 Task ID로 복사한다. Git history에도 남는다.

## Acceptance Criteria (구현 전 고정)

결정자 / 판단 근거 / 검토 대상 revision 또는 PR: 사용자 / audit 제안 3 / 이 Task 초안. **AC 확정, 삭제 실행 승인됨.** 승인 범위: 스크립트 삭제, 전용 테스트 삭제, KEEP 테스트용 상수 이동, README 테스트 수 갱신. 역사적 evidence는 수정하지 않는다.

| ID | 입력/상황 | 기대 결과 | 검증 방법 | 결과/evidence |
|---|---|---|---|---|
| AC-01 | 삭제 직전 | 위 표의 참조 목록을 `git grep`으로 재확인. 새 참조가 있으면 중단 | 명령 출력 | 미검증 |
| AC-02 | 삭제 직전 | 복구용 사본 2개 파일 존재 | 디렉터리 확인 | 미검증 |
| AC-03 | 삭제 후 Python (비공개 artifact 있음) | 57개 통과, 실패 0, skip 0 (58에서 1 감소) | `python3 -m unittest discover -s tests` | 미검증 |
| AC-04 | 삭제 후 Python (Public Git 조건) | 57개 중 skip 2, 실패 0 (skip 3에서 2로 감소) | 같은 명령, 빈 artifact 경로 | 미검증 |
| AC-05 | `test_private_artifacts_reextract_to_committed_golden_files` | 비공개 artifact 있는 환경에서 통과(이동한 상수로 golden 재생성 일치). 이동한 상수 값이 삭제 전 스크립트의 값과 문자열로 동일함을 삭제 직전에 출력해 기록 | AC-03 출력과 상수 값 비교 출력 | 미검증 |
| AC-06 | `python -m compileall scripts tests` | 성공 | 명령 출력 | 미검증 |
| AC-07 | 변경 파일 | 정확히 3파일: 삭제 `scripts/migrate_public_kb_pipeline_v2.py`, 수정 `tests/contract/test_public_product_versions.py`, 수정 `README.md`(테스트 수). 그 외 변경 없음. evidence 문서 변경 없음 | `git show --stat` | 미검증 |
| AC-08 | README 검증 설명 | "Python 58개" 표기를 57로 갱신(실제 구현 상태 반영) | diff | 미검증 |
| AC-09 | 커밋 | `chore: 일회성 공개 상품 pipeline v2 migrate 스크립트와 전용 테스트 제거` | 메시지 검토 | 미검증 |

### 완료 기준 변경 이력

변경 없음.

## Implementation Plan

1. TASK-001 병합 확인. 새 브랜치 `chore/drop-public-kb-migrate-script`.
2. AC-01, AC-02.
3. 테스트 파일: import 제거, 모듈 상수 `DAY3_HARDENING_EXTRACTION_RUN_ID` 추가(값 동일), 227행 참조 교체, 256행 이하 전용 테스트 메서드 삭제.
4. 스크립트 삭제. README 숫자 갱신.
5. AC-03~AC-07 실행 후 커밋. 사용자 승인 시 push와 PR.

인간의 계획 판단 / 승인 범위: **승인.** 1~5단계. commit, push, PR 생성까지. 병합은 사용자. 실행 시점은 TASK-001 병합 뒤.

## AI 제안 및 인간 판단 기록

없음(제안 3은 audit에서 판단됨).

## Implementation Result / AI self-review / 인간 검수 / 결정 기록

미실행 / 미기록.
