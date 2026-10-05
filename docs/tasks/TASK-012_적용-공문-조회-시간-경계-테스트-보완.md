# TASK-012 적용 공문 조회의 시간 경계 테스트 보완

- 상태: **검증/검수 대기** (구현과 자동 검증 완료, PR 병합과 인간 검수 대기) (사용자 승인: 조회 통합 테스트 3건 추가, 업무 로직 변경 없음, 별도 PR)
- 담당자 / 인간 결정자: AI(테스트 작성, 실행, 기록) / 사용자(범위, 검수)
- 요구사항 출처: TASK-001 인간 검수 자료의 "경계 검증 현황"(같은 업무일, 같은 기준 시각, 서울 자정 전후 단언 없음). 사용자 결정: 시계 고정 수정 Task는 전제 오류로 취소, 대신 테스트 3건 추가 승인.
- 관련: [TASK-001](TASK-001_합성-공문-조회-자산-보존.md), [ADR-008](../adr/ADR-008-internal-notice-effective-policy-and-review.md) 시간 모델

## Goal / 관련 요구사항

- Goal: 적용 공문 조회가 "업무일이 평가 업무일과 같은 날", "인지 시각이 평가 시각과 정확히 같음", "서울 자정 전후로 평가 업무일이 바뀜"을 의도대로 처리함을 통합 테스트로 고정한다. 이 세 경우는 코드상 올바르게 동작하지만 단언이 없어 회귀를 조용히 놓칠 수 있었다.
- 업무규칙(변경 없음): 미래 업무일 판정은 `businessDate > evaluatedBusinessDate`, 과거 인지 시각 판정은 `knownAt < evaluatedAt`, 평가 업무일은 평가 시각을 Asia/Seoul로 변환한 날짜. 시간 차단 사유가 없어도 검증과 사람 승인이 없으면 사용은 차단된다.
- 데이터 영향: 없음(테스트 전용). API: 변경 없음.
- Out of Scope: 업무 로직, 응답 계약, 데이터 변경. 다른 테스트의 시각 처리.

## Acceptance Criteria (구현 전 고정)

결정자 / 판단 근거 / 검토 대상 revision 또는 PR: 사용자 / TASK-001 검수 자료 경계 현황 / 이 Task PR.

| ID | 입력/상황 | 기대 결과 | 검증 방법 | 결과/evidence |
|---|---|---|---|---|
| AC-01 | 평가 시각 2026-10-05T03:00:00Z(서울 12:00), `businessDate=2026-10-05`, knownAt 생략 | `evaluatedBusinessDate=2026-10-05`, `businessDateInFuture=false`, `historicalKnownAt=false`, 차단 사유에 `FUTURE_BUSINESS_DATE`와 `HISTORICAL_KNOWN_AT` 없음. V2 선택, `PENDING_VALIDATION`, `CHECKLIST_VALIDATION_PENDING` 있음, `internalChecklistUseAllowed=false` | 통합 테스트 | **통과** |
| AC-02 | `knownAt=2026-10-05T03:00:00Z`(평가 시각과 동일) | 200, `historicalKnownAt=false`, `HISTORICAL_KNOWN_AT` 없음, 사용 차단 유지. 1초 앞(`02:59:59Z`)은 `historicalKnownAt=true`와 `HISTORICAL_KNOWN_AT`. 1초 뒤 400은 기존 테스트 | 통합 테스트 | **통과** |
| AC-03 | 평가 시각 2026-10-05T15:30:00Z(서울 10-06 00:30) | `evaluatedBusinessDate=2026-10-06`, `businessDate=2026-10-06`은 미래 아님, `2026-10-07`은 미래. 평가 시각 2026-10-05T14:59:59Z(서울 10-05 23:59:59)에서는 `2026-10-06`이 미래 | 통합 테스트(테스트 전용 조정 시계) | **통과** |
| AC-04 | 변경 범위 | `src/main` 변경 없음. 테스트 파일 1개, Task 문서, evidence, README 테스트 수만 변경 | diff | **통과.** `src/main` 변경 없음 |
| AC-05 | 회귀 | Java 전체 85개 통과(82 + 3), skip 0, jar 생성. Python 57개 통과. CI 통과 | 로컬 + CI | **통과.** Java 85/0/0, jar 생성, Python 57. CI는 PR에 기록 |

### 완료 기준 변경 이력

변경 없음.

## Implementation Plan

1. 테스트 클래스의 고정 시계 bean을 "기본값 고정, 테스트 안에서만 조정 가능한 시계"로 바꾼다. 서비스는 `Clock.instant()`만 읽으므로 동작 차이가 없다. 서울 자정 테스트만 시각을 바꾸고 `finally`에서 되돌린다.
2. 테스트 3건 추가(AC-01~03).
3. 전체 Gradle 테스트와 Python 테스트 실행, evidence 기록, README 테스트 수 갱신.

인간의 계획 판단 / 승인 범위: **승인.** 테스트 3건 추가, 업무 로직 변경 금지, 별도 PR. 병합은 사용자.

## AI 제안 및 인간 판단 기록

없음.

## Implementation Result

변경 파일: 테스트 1개(`InternalPolicyApplicableIntegrationTest`, 테스트 3건과 조정 가능한 테스트 시계), 이 Task 문서, `docs/evidence/APPLICABLE_QUERY_TIME_BOUNDARY_TESTS_EVIDENCE.md`, README의 Java 테스트 수(82 → 85). `src/main` 변경 없음. 결과는 evidence 문서.

## AI self-review

- 첫 실행에서 새 테스트 2건이 실패했다. 원인은 기대값 오류(승인 checklist 예시 데이터가 있는 family를 `PENDING_VALIDATION`으로 가정)였고 업무 로직 결함이 아니다. 기대값을 실제 데이터에 맞게 고쳤다. 이 과정에서 "승인 checklist가 있어도 검증 전에는 사용 차단"이 두 family 모두에서 확인됐다.
- 결함 보고와 테스트 보완 제안의 구분: 이 Task에서 확인된 업무 로직 결함은 없다. 세 경계는 모두 의도대로 동작했고, 보완된 것은 테스트 단언이다.

## 인간 검수와 Explainability Gate

미기록.

## 결정 기록과 완료

완료 판정 대기(사용자).
