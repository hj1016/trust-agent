# TASK-007 사람 검토 결정과 승인 checklist 발행

- 상태: **계획 검토 대기** (계획 작성 승인. 구현 착수 승인은 아님)
- 담당자 / 인간 결정자: AI 조사·초안·구현·검증 / 사용자 범위·판정·검수
- 요구사항 출처: PLAN-001 TASK-007 절(S5 사람 검수, S6 조회), ADR-009 §2.2 R-11(한 공문군의 적용 일정은 revision chain), R-12(기간 중첩 금지), R-05(역할별 최소 권한), CLAUDE.md "AI는 승인 주체가 아니다"
- 관련 Issue / PR / ADR / 이전 Task: TASK-005(변경안 생성, 완료), TASK-006(자동 검증, PR #21 검수 대기), ADR-008(공개 근거 확인 정책)

## Goal / 관련 요구사항

- Goal: 검수자가 자동 검증을 마친 변경안에 대해 **승인(APPROVE), 수정(MODIFY), 반려(REJECT)** 를 기록하고, 승인 시 새 승인 checklist version과 적용 일정 revision이 발행되어 적용 공문 조회가 `AVAILABLE`과 `internalChecklistUseAllowed=true`를 돌려준다. 여기까지가 "Core 승인과 조회 흐름 완료"다.
- 사용자: 검수자(사람). 이번 Task에서는 합성 검수자 ID를 쓰는 test/demo 명령이며 실제 인증과 화면은 범위 밖이다.
- 사전조건: 변경안이 있고(TASK-005), 그 변경안의 최신 자동 검증 결과가 있다(TASK-006).
- 입력: 변경안 ID, 검토한 검증 결과 ID, 결정 종류, 검수자 ID, 사유, (수정 시) 바꾼 항목 내용.
- 업무규칙: 아래 "결정별로 저장되는 것"과 "사용 허용 조건".
- 상태전이(변경안 기준): `검증 완료(PASS/WARN)` → 승인 → `승인됨(checklist 발행)`. `검증 완료` → 수정 → `대체됨(새 revision 생성, 새 revision은 검증 대기)`. `검증 완료` → 반려 → `반려됨`. 금지 전이: FAIL 결과의 승인, 검증 없는 변경안의 승인, 이미 대체되거나 반려된 변경안의 승인, 같은 변경안의 두 번째 결정.
- 데이터 영향: 쓰기는 새 테이블 `human_review_decision`과 기존 `approved_checklist_version`(origin `HUMAN_REVIEW`), `approved_checklist_item`, `approved_checklist_schedule_revision`, `approved_checklist_schedule_entry`, `checklist_change_proposal`(수정 revision). 전부 append-only. 읽기는 변경안, 검증 결과, 공문, 일정.
- API: HTTP 없음. ApplicationRunner 명령(test/demo profile). 적용 공문 조회 응답은 기존 필드로 승인 상태를 표현하고, 추가 필드는 제안 3에서 판단.
- 트랜잭션: 결정 기록 + checklist version + 항목 + 일정 revision + 항목을 **한 트랜잭션**. 중간 실패 시 아무것도 남지 않고 실패 실행 기록만 남는다. 외부 호출 없음(공개 근거는 DB에 적재된 관측만 사용).
- 권한: runtime 역할에 신규 테이블 INSERT/SELECT. importer 계열 역할은 승인을 기록할 수 없다(R-05). production profile에서 명령 설정이 켜지면 기동 거부.
- 실패 시나리오: 같은 변경안에 동시에 두 결정 → 하나만 성공(변경안당 결정 1건 UNIQUE + 생성 lock). 같은 일정 leaf를 동시에 대체 → 하나만 성공(`supersedes_schedule_revision_id` UNIQUE). 결정 직전에 새 검증 FAIL이 추가됨 → 승인 거부. 변경안 내용 해시 불일치 → 승인 거부. 재실행은 run ID로 멱등.
- Out of Scope: 인증과 권한 체계, 화면, 알림, 승인 철회(revoke), 정기 재검증, AI 기능, HTTP 노출.

## 결정별로 저장되는 것

공통: `human_review_decision` 1행(결정 ID, 변경안 ID, 검토한 검증 결과 ID, 결정 시점의 변경안 해시(`after_hash`), 결정 종류, 검수자 ID(합성), 사유, 결정 시각, 결과 참조). append-only. 변경안당 결정은 1건만(UNIQUE). 사유는 반려, 수정, WARN 승인에 필수.

| 결정 | 추가로 저장되는 것 | 저장되지 않는 것 |
|---|---|---|
| **APPROVE** | 새 `approved_checklist_version`(origin `HUMAN_REVIEW`, 대상 공문 ID, 결정 ID 참조), 항목 = 기준 checklist에 변경안의 추가/수정/삭제를 적용한 결과(`approved_checklist_item`), 새 일정 revision(현재 leaf를 supersede)과 항목 = 기존 항목 복사 + 직전 구간의 종료일을 새 시행일로 제한 + 새 구간 `[공문 시행일, 없음)` | 변경안과 검증 결과는 그대로(불변) |
| **MODIFY** | 새 변경안 revision(`checklist_change_proposal`, `supersedes_proposal_id` = 검토한 변경안, `revision_reason` = 사유, 항목 = 검수자가 고친 내용). 생성기 version은 `human-revision-v1` | 승인 checklist, 일정 없음. 새 revision은 **다시 자동 검증(TASK-006)** 을 받아야 승인 가능. 이전 변경안의 검증 결과는 새 revision에 쓰이지 않는다(TASK-006에서 이미 보장) |
| **REJECT** | 결정 1행만 | 승인 checklist, 일정, 새 revision 없음. 변경안은 대체되지 않고 "반려됨"으로 남는다. 새 변경안은 공문 재추출이나 수정 결정으로만 생긴다 |

승인 전 검사(모두 통과해야 승인):
1. 검토한 검증 결과가 그 변경안의 **최신** 결과이고 상태가 PASS 또는 WARN(WARN은 사유 필수). FAIL이면 거부.
2. 검증 결과의 `validated_at`이 결정 시각 기준 `max-validation-age` 안. 넘으면 `VALIDATION_STALE` 거부(재검증 뒤 다시 결정).
3. 검증 결과의 `proposal_hash` = 변경안 `after_hash`(검증 뒤 내용이 바뀌지 않음).
4. 변경안이 대체되지 않았고(leaf), 반려·승인 결정이 없음.
5. 대상 공문이 철회되지 않았고, 변경안의 기준 checklist가 지금 일정 leaf에서 시행일 전날을 덮는 version과 같음(기준 최신성).
6. 같은 대상 공문에 `HUMAN_REVIEW` 승인 checklist가 아직 없음.
7. 결정은 현재 시각 기준으로만 한다. 과거 `knownAt`을 입력할 수 없다.

## 사용 허용 조건 (행원이 checklist를 쓸 수 있게 되는 때)

적용 공문 조회의 사용 허용(`internalChecklistUseAllowed`)은 기존 정책(`InternalChecklistUsePolicy`)의 조건을 전부 만족할 때만 true다. TASK-007 뒤 각 조건의 실제 값은 다음과 같다.

| 조건 | 뜻 | 누가 정하나 |
|---|---|---|
| 과거 조회 아님 | `knownAt` = 평가 시각 | 요청 |
| 미래 업무일 아님 | `businessDate` ≤ 평가 업무일 | 요청 |
| 공문 선택 `SELECTED` | 그 업무일에 적용되는 공문이 하나로 정해짐 | 기존 조회 |
| checklist `AVAILABLE` | `knownAt`까지 보이는 최신 일정 revision에서 업무일을 덮는 승인 checklist가 있고 origin이 `HUMAN_REVIEW` | **TASK-007 승인으로 생김.** FIXTURE 출처는 사용 허용에 쓰지 않는다(제안 1) |
| 검증 유효 | 승인이 근거로 삼은 검증 결과가 **승인 시점**에 유효했음 | 승인 전 검사 2 (제안 2) |
| 공개 근거 확인 | 공문 참조(`internal_notice_reference`)가 있으면 조회 시점의 공개 근거가 확인됨(기존 freshness 정책). 참조가 없으면 해당 없음(true) | 조회 시점 |
| 의미 일치 | 승인 checklist의 공문 ID = 선택된 공문 ID | 조회 시점 |

따라서 "승인 전에는 어떤 경우에도 false"이고, "승인 뒤에는 조회 시점의 공개 근거가 확인되고 과거·미래 조회가 아닐 때만 true"다. 승인은 사람의 결정 기록이 있어야만 생긴다.

## Acceptance Criteria (초안, 구현 전 고정 대상)

| AC | 사례 | 기대 결과 | 검증 방법 |
|---|---|---|---|
| AC-01 | PASS 변경안 승인(중도상환수수료 v1 → v2) | 결정 1행, `HUMAN_REVIEW` version 1개와 항목 3개(기준 2 + 추가 1, 수정 반영 0.8), 새 일정 revision이 fixture 일정을 supersede하고 구간 `[2026-09-15, 2026-10-01)` + `[2026-10-01, 없음)` | 통합 테스트 |
| AC-02 | 승인 뒤 조회 `businessDate=2026-10-01`, `knownAt` 생략 | `AVAILABLE`, `internalChecklistUseAllowed=true`, 차단 사유 없음, 승인 checklist의 항목이 응답에 포함 | 조회 테스트 |
| AC-03 | 승인 뒤 조회 `businessDate=2026-09-30` | v1 checklist(fixture) `AVAILABLE`, 사용 허용 false(FIXTURE) | 조회 테스트 |
| AC-04 | 승인 뒤 과거 조회(`knownAt` < 일정 revision 생성 시각) | 승인 전 상태(`PENDING_REVIEW`)가 보이고 사용 허용 false | 조회 테스트 |
| AC-05 | FAIL 결과 변경안 승인, 검증 없는 변경안 승인, 오래된 검증(24h+1초) 승인, 해시 불일치 승인 | 각각 거부 코드(`VALIDATION_FAILED`, `VALIDATION_MISSING`, `VALIDATION_STALE`, `PROPOSAL_HASH_MISMATCH`), 아무것도 저장되지 않고 실패 실행 기록만 | 통합 테스트 |
| AC-06 | WARN 결과 승인, 사유 없음 / 있음 | 없음 거부(`REASON_REQUIRED`), 있음 승인 | 통합 테스트 |
| AC-07 | MODIFY(수수료율 after를 검수자가 고침) | 새 revision 생성, 승인 checklist 없음, 조회는 `PENDING_VALIDATION`. 새 revision을 검증 없이 승인하면 `VALIDATION_MISSING` 거부. 검증 뒤 승인 가능 | 통합 테스트 |
| AC-08 | REJECT | 결정 1행, 그 변경안의 승인·수정 시도 거부(`PROPOSAL_REJECTED`), 조회 상태는 제안 3에 따름 | 통합 테스트 |
| AC-09 | 같은 변경안 결정 2번, 같은 일정 leaf 동시 대체 | 두 번째 거부(UNIQUE 23505), 둘 중 하나만 성공 | 통합 테스트(동시 실행 2건) |
| AC-10 | 승인 트랜잭션 중간 실패 유도 | 결정, version, 항목, 일정 어느 것도 없음. 실패 실행 기록만 | 통합 테스트 |
| AC-11 | 과거 `knownAt` 입력으로 결정 시도 | 거부(`HISTORICAL_DECISION_NOT_ALLOWED`) | 통합 테스트 |
| AC-12 | production | 결정 명령 설정이 켜지면 기동 거부(`DEMO_FEATURE_ENABLED_IN_PROD`). importer 역할은 신규 테이블 INSERT 불가, runtime은 가능 | 컨텍스트 테스트 + 권한 테스트 |
| AC-13 | 공개 근거 | 셀러론 공문군(참조 있음)을 승인한 뒤 조회: 공개 근거가 24h 정책에서 오래됐으면 `AVAILABLE`이지만 사용 허용 false(차단 사유 `PUBLIC_EVIDENCE_UNCONFIRMED`), 30일 정책이면 true | 조회 테스트 |
| AC-14 | 보호와 회귀 | 신규 테이블 append-only와 보호 목록(33 → 34, 66 → 68, migration 8). 기존 Java 130, Python 62 유지, 환경별 실행·통과·실패·건너뜀 기록 | schema 테스트 + 로컬/CI |
| AC-15 | 문서 | README "사람 검토와 승인 구현, 인증·화면 미구현", core README 명령, evidence, PLAN-001 갱신 | diff 검토 |

## Implementation Plan (초안)

1. V8: `human_review_decision`(결정 ID `review-decision:[a-f0-9]{32}`, 변경안 FK, 검증 결과 FK, `proposal_hash`, `decision` APPROVE/MODIFY/REJECT, `reviewer_id`(합성, `SYNTHETIC_WORK` 표시), `reason`, `decided_at`, `approved_checklist_version_id`/`schedule_revision_id`(APPROVE만 NOT NULL CHECK), `revision_proposal_id`(MODIFY만), 변경안당 UNIQUE), `human_review_run`. `approved_checklist_version`에 `decision_id` 컬럼(HUMAN_REVIEW면 NOT NULL CHECK). 보호 목록 등록.
2. `HumanReviewService`: 검사 1~7, 세 결정의 저장, 한 트랜잭션, 실패 기록.
3. 승인 checklist 발행기: 기준 항목 + 변경안 항목 → 새 항목 목록(결정적, 해시).
4. 일정 revision 발행기: 기존 leaf 복사, 직전 구간 종료일 제한, 새 구간 추가. R-12 중첩 금지는 기존 EXCLUDE 제약이 강제.
5. 적용 공문 조회: `AVAILABLE` 경로에서 origin `HUMAN_REVIEW`와 공개 근거 확인을 정책 입력으로 연결(`validationFresh`는 승인 기록 존재로, `publicEvidenceConfirmed`는 조회 시점 공개 근거로, `semanticMatch`는 공문 ID 일치로).
6. demo 명령(`trust-agent.human-review.enabled`, demo 전용 설정 등록), 테스트, evidence, README.

예상 크기: TASK-006과 비슷(코드 ~700행, 테스트 ~25건). PLAN-001 제안 C(분할)는 AC 15개가 한 트랜잭션 원자성을 공유하므로 **분할하지 않음**을 제안한다.

## AI 제안 및 인간 판단 기록

### 제안 1: FIXTURE 출처 checklist는 사용 허용에 쓰지 않는다

- 내용: `AVAILABLE`이어도 origin이 `FIXTURE`면 `internalChecklistUseAllowed=false`이고 차단 사유 `FIXTURE_CHECKLIST_NOT_APPROVED`. v1 기간 조회는 AC-03처럼 계속 false.
- 근거: TASK-005 판단 "origin은 출처 구분이지 승인 증거가 아니다".
- 대안: v1도 실제 승인 경로로 승인하는 테스트를 추가해 v1 기간 사용 허용을 보인다(PLAN-001에 언급). 데이터 준비가 더 필요.

**판단**
- [ ] 채택 [ ] 수정 [ ] 거절
- 판단자 / 검토 대상 revision 또는 PR: 사용자 / 판단 대기

### 제안 2: 검증 유효 기간은 승인 시점에만 본다

- 내용: 승인 뒤에는 검증 결과가 24시간을 넘겨도 사용 허용이 유지된다. 사람이 승인한 사실이 근거이고, 검증은 승인 전 보조 수단이기 때문이다. 정기 재검증과 승인 철회는 별도 Task.
- 대안: 사용 시점마다 검증 유효 기간을 다시 요구. 승인 다음 날부터 모든 조회가 차단되므로 운영상 매일 재검증·재승인이 필요하다.

**판단**
- [ ] 채택 [ ] 수정 [ ] 거절
- 판단자 / 검토 대상 revision 또는 PR: 사용자 / 판단 대기

### 제안 3: 반려된 변경안의 조회 상태

- 내용: 반려 뒤 그 변경안이 최신 revision이면 조회 상태를 `UNAVAILABLE`, 차단 사유 `PROPOSAL_REJECTED`로 표시하고 응답의 `validatedProposalId`/`validationResultId`는 null로 둔다(검증 결과를 더 쓰지 않음).
- 대안: `PENDING_VALIDATION`으로 되돌린다(반려 사실이 응답에 드러나지 않음).

**판단**
- [ ] 채택 [ ] 수정 [ ] 거절
- 판단자 / 검토 대상 revision 또는 PR: 사용자 / 판단 대기

### 제안 4: 응답에 결정 ID 추가 여부

- 내용: 승인 checklist가 응답에 포함될 때 `approvedChecklist.decisionId`와 `approvedChecklist.origin`을 함께 돌려준다. 추가 최상위 필드는 없음.
- 대안: 필드 추가 없이 version ID로만 추적.

**판단**
- [ ] 채택 [ ] 수정 [ ] 거절
- 판단자 / 검토 대상 revision 또는 PR: 사용자 / 판단 대기

## Implementation Result / AI self-review / 인간 검수 / 결정 기록

미실행 / 미기록. 구현은 사용자 착수 승인 뒤.
