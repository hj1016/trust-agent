# TASK-007 사람 검토 결정과 승인 checklist 발행

- 상태: **검증·검수 대기** (완료 확인 조건 15개와 승인·수정·반려 흐름 승인, 구현 착수 승인. 구현 PR 검수 대기. 완료와 인간 검수는 미기록)
- 담당자 / 인간 결정자: AI 조사·초안·구현·검증 / 사용자 범위·판정·검수
- 요구사항 출처: PLAN-001 TASK-007 절(S5 사람 검수, S6 조회), ADR-009 §2.2 R-11(한 공문군의 적용 일정은 revision chain), R-12(기간 중첩 금지), R-05(역할별 최소 권한), CLAUDE.md "AI는 승인 주체가 아니다"
- 관련 Issue / PR / ADR / 이전 Task: TASK-005(변경안 생성, 완료), TASK-006(자동 검증, 완료 PR #21/#23), ADR-008(공개 근거 확인 정책). 계획 문서 PR #22는 구현 PR #24가 같은 문서를 포함·갱신하여 대체(사용자 지시로 #22 닫음)

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
| checklist `AVAILABLE` | `knownAt`까지 보이는 최신 일정 revision에서 업무일을 덮는 승인 checklist가 있고 origin이 `HUMAN_REVIEW` | **TASK-007 승인으로 생김.** FIXTURE 출처는 실제 업무 사용 허용에 쓰지 않는다(제안 1, 채택) |
| 검증 유효 | 승인이 근거로 삼은 검증 결과가 **승인 시점**에 유효했음. 승인 뒤 기간 경과만으로 자동 만료하지 않음 | 승인 전 검사 2 (제안 2, 수정 채택) |
| 공개 근거 확인 | 공문 참조(`internal_notice_reference`)가 있으면 조회 시점의 공개 근거가 확인됨(기존 freshness 정책). 참조가 없으면 해당 없음(true) | 조회 시점 |
| 의미 일치 | 승인 checklist의 공문 ID = 선택된 공문 ID | 조회 시점 |

따라서 "승인 전에는 어떤 경우에도 false"이고, "승인 뒤에는 조회 시점의 공개 근거가 확인되고 과거·미래 조회가 아닐 때만 true"다. 승인은 사람의 결정 기록이 있어야만 생긴다.

승인 뒤에도 유지되는 차단 조건(기존 조회 규칙 그대로):
- 대상 공문이 철회되면 그 공문은 선택되지 않으므로 승인 checklist도 선택되지 않는다.
- 새 공문(v3)이 수신되어 선택되면 승인 checklist의 공문 ID와 달라 의미 일치가 깨지고, v3의 변경안·검증·승인이 없으므로 `PENDING_VALIDATION`이다.
- 필수 공개 근거 참조가 있는 공문군은 조회 시점에 공개 근거가 확인되지 않으면 `PUBLIC_EVIDENCE_UNCONFIRMED`로 차단된다.
- 검증 결과의 유효 기간 경과만으로는 승인 checklist를 자동 만료시키지 않는다. 승인 철회와 정기 재검증은 별도 Task.

## 승인·수정·반려 예시 (중도상환수수료 공문군, TASK-006 PR #21 데이터 기준)

공통 출발점: 변경안 P(`checklist-proposal:sha256:59ad46b8…`, 기준 checklist = v1 fixture `approved-checklist:2eaa5be6…`, 대상 공문 v2, 항목 2개: `CHECK_CUSTOMER_CONTRACT_DATE` 추가, `CHECK_PREPAYMENT_FEE_RATE` 1.2 → 0.8). 최신 검증 결과 R = PASS, `validated_at` 2026-10-05T03:00:00Z. 결정 시각 2026-10-05T06:00:00Z(검증 3시간 뒤, 24h 안).

**승인.** 입력: P, R, APPROVE, 검수자 `SYN-REVIEWER-01`, 사유 생략 가능(PASS). 저장:
1. `human_review_decision` 1행: 결정 ID, P, R, P의 `after_hash`, APPROVE, 검수자, 2026-10-05T06:00:00Z, 발행한 version ID와 일정 revision ID.
2. `approved_checklist_version` 1행(origin `HUMAN_REVIEW`, 공문 v2, 결정 ID)과 항목 3개: `CHECK_NOTICE_SOURCE`(기준 그대로), `CHECK_PREPAYMENT_FEE_RATE`(0.8로 수정), `CHECK_CUSTOMER_CONTRACT_DATE`(추가).
3. 새 일정 revision(fixture 일정 `checklist-schedule:40343b3e…`를 supersede)과 항목 2개: v1 version `[2026-09-15, 2026-10-01)`, 새 version `[2026-10-01, 없음)`.
조회: `businessDate=2026-10-01` → `AVAILABLE`, 사용 허용 true, `approvedChecklist.origin=HUMAN_REVIEW`, `decisionId`. `businessDate=2026-09-30` → v1 fixture `AVAILABLE`이지만 사용 허용 false. `knownAt=2026-10-05T05:59:59Z`(승인 1초 전) → `PENDING_REVIEW`, 사용 허용 false.

**수정.** 입력: P, R, MODIFY, 검수자, 사유 "예외 문구 보완", 고친 항목(`CHECK_PREPAYMENT_FEE_RATE`의 exceptions에 1건 추가). 저장: 결정 1행(MODIFY, 새 revision ID)과 새 변경안 P2(`supersedes_proposal_id`=P, `revision_reason`=사유, 생성기 `human-revision-v1`, 항목은 고친 내용). 승인 checklist와 일정은 없음. 조회: P2가 최신이므로 `PENDING_VALIDATION`, 두 ID null(TASK-006 보장). 이후 P2를 자동 검증 → PASS면 P2에 대해 승인 가능. P를 다시 승인하려 하면 `PROPOSAL_SUPERSEDED` 거부.

**반려.** 입력: P, R, REJECT, 검수자, 사유 "공문 원문 재확인 필요"(필수). 저장: 결정 1행만. 조회: `UNAVAILABLE`, 차단 사유 `PROPOSAL_REJECTED`, 두 ID null, 사용 허용 false. R이 PASS여도 `PENDING_REVIEW`로 되돌아가지 않는다. P를 승인·수정하려 하면 `PROPOSAL_REJECTED` 거부. 새 변경안은 공문 재추출로 새 변경안이 생기거나, 사람이 수정 결정을 쓸 수 있도록 하려면 반려가 아니라 수정을 선택해야 한다.

## Acceptance Criteria (판단 반영, 구현 전 고정 대상)

| AC | 사례 | 기대 결과 | 검증 방법 |
|---|---|---|---|
| AC-01 | PASS 변경안 승인(중도상환수수료 v1 → v2) | 결정 1행, `HUMAN_REVIEW` version 1개와 항목 3개(기준 2 + 추가 1, 수정 반영 0.8), 새 일정 revision이 fixture 일정을 supersede하고 구간 `[2026-09-15, 2026-10-01)` + `[2026-10-01, 없음)` | 통합 테스트 |
| AC-02 | 승인 뒤 조회 `businessDate=2026-10-01`, `knownAt` 생략 | `AVAILABLE`, `internalChecklistUseAllowed=true`, 차단 사유 없음, 응답 `approvedChecklist`에 항목 3개, `origin=HUMAN_REVIEW`, `decisionId` 포함 | 조회 테스트 |
| AC-03 | 승인 뒤 조회 `businessDate=2026-09-30` | v1 checklist(fixture) `AVAILABLE`, `origin=FIXTURE`, `decisionId=null`, 사용 허용 false, 차단 사유 `FIXTURE_CHECKLIST_NOT_APPROVED` | 조회 테스트 |
| AC-04 | 승인 뒤 과거 조회(`knownAt` < 일정 revision 생성 시각) | 승인 전 상태(`PENDING_REVIEW`)가 보이고 사용 허용 false | 조회 테스트 |
| AC-05 | FAIL 결과 변경안 승인, 검증 없는 변경안 승인, 오래된 검증(24h+1초) 승인, 해시 불일치 승인 | 각각 거부 코드(`VALIDATION_FAILED`, `VALIDATION_MISSING`, `VALIDATION_STALE`, `PROPOSAL_HASH_MISMATCH`), 아무것도 저장되지 않고 실패 실행 기록만 | 통합 테스트 |
| AC-06 | WARN 결과 승인, 사유 없음 / 있음 | 없음 거부(`REASON_REQUIRED`), 있음 승인 | 통합 테스트 |
| AC-07 | MODIFY(수수료율 after를 검수자가 고침) | 새 revision 생성, 승인 checklist 없음, 조회는 `PENDING_VALIDATION`. 새 revision을 검증 없이 승인하면 `VALIDATION_MISSING` 거부. 검증 뒤 승인 가능 | 통합 테스트 |
| AC-08 | REJECT | 결정 1행, 그 변경안의 승인·수정 시도 거부(`PROPOSAL_REJECTED`). 조회는 반려 상태(`UNAVAILABLE` + 차단 사유 `PROPOSAL_REJECTED`)이며 `validatedProposalId`/`validationResultId`는 null. 반려 전 PASS 결과가 있어도 `PENDING_REVIEW`로 되돌아가지 않음 | 통합 테스트 + 조회 테스트 |
| AC-09 | 같은 변경안 결정 2번, 같은 일정 leaf 동시 대체 | 두 번째 거부(UNIQUE 23505), 둘 중 하나만 성공 | 통합 테스트(동시 실행 2건) |
| AC-10 | 승인 트랜잭션 중간 실패 유도 | 결정, version, 항목, 일정 어느 것도 없음. 실패 실행 기록만 | 통합 테스트 |
| AC-11 | 과거 `knownAt` 입력으로 결정 시도 | 거부(`HISTORICAL_DECISION_NOT_ALLOWED`) | 통합 테스트 |
| AC-12 | production | 결정 명령 설정이 켜지면 기동 거부(`DEMO_FEATURE_ENABLED_IN_PROD`). importer 역할은 신규 테이블 INSERT 불가, runtime은 가능 | 컨텍스트 테스트 + 권한 테스트 |
| AC-13 | 승인 뒤 차단 조건 유지(기존 시간 정책 기준) | (a) 셀러론 공문군(필수 참조 있음)을 승인한 뒤 조회: 공개 근거가 유효 기간(30일) 안이면 사용 허용 true, 넘기면 `AVAILABLE`이지만 false(`PUBLIC_EVIDENCE_UNCONFIRMED`). (b) 승인 뒤 평가 시각을 25시간 뒤로 옮겨도 true 유지(검증 기간 경과로 자동 만료 없음). (c) 대상 공문 철회를 알게 된 뒤에는 그 공문의 모든 업무일이 `WITHDRAWN`으로 차단. 철회 전 기준 시각 조회는 선택됨(과거 조회라 사용 불가). (d1) 미래 시행 공문 v3 수신만으로는 현재 업무일의 선택이 바뀌지 않으므로 계속 사용 가능. (d2) 조회 업무일이 v3 시행 구간이면 v3가 선택되고, 일정 구간이 그날을 덮어도 checklist가 v2용이라 `APPROVED_CHECKLIST_NOTICE_MISMATCH`로 차단. (d3) 끊긴 chain으로 선택이 모호해지면 `AMBIGUOUS_EFFECTIVE_NOTICE`로 차단 | 조회 테스트 |
| AC-14 | 보호와 회귀 | 신규 테이블 2개(결정, 실행 기록) append-only와 보호 목록(코드 기준 33 → 35, 66 → 70, migration 8). 기존 Java 130, Python 62 유지, 환경별 실행·통과·실패·건너뜀 기록 | schema 테스트 + 로컬/CI |
| AC-15 | 문서 | README "사람 검토와 승인 구현, 인증·화면 미구현, 승인 철회와 정기 재검증 미구현", core README 명령, evidence, PLAN-001 갱신 | diff 검토 |

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
- [x] 채택
- 판단자 / 검토 대상 revision 또는 PR: 사용자 / PR #22
- 이유 / 승인 범위: 테스트용 checklist는 실제 업무 사용 허용에 쓰지 않는다.

### 제안 2: 검증 유효 기간은 승인 시점에만 본다

- 내용: 승인 뒤에는 검증 결과가 24시간을 넘겨도 사용 허용이 유지된다. 사람이 승인한 사실이 근거이고, 검증은 승인 전 보조 수단이기 때문이다. 정기 재검증과 승인 철회는 별도 Task.
- 대안: 사용 시점마다 검증 유효 기간을 다시 요구. 승인 다음 날부터 모든 조회가 차단되므로 운영상 매일 재검증·재승인이 필요하다.

**판단**
- [x] 수정 채택
- 판단자 / 검토 대상 revision 또는 PR: 사용자 / PR #22
- 이유 / 승인 범위: 검증 유효 기간은 승인 시점에 확인한다. 승인 후 검사 결과의 기간 경과만으로 checklist를 자동 만료시키지 않되, 공문 철회·대체와 필수 공개 근거 확인 등 기존 사용 차단 조건은 유지한다(AC-13).

### 제안 3: 반려된 변경안의 조회 상태

- 내용: 반려 뒤 그 변경안이 최신 revision이면 조회 상태를 `UNAVAILABLE`, 차단 사유 `PROPOSAL_REJECTED`로 표시하고 응답의 `validatedProposalId`/`validationResultId`는 null로 둔다(검증 결과를 더 쓰지 않음).
- 대안: `PENDING_VALIDATION`으로 되돌린다(반려 사실이 응답에 드러나지 않음).

**판단**
- [x] 채택
- 판단자 / 검토 대상 revision 또는 PR: 사용자 / PR #22
- 이유 / 승인 범위: 반려된 변경안은 반려 상태로 표시하고, 이전 검사 통과 결과로 검토 대기 상태에 되돌아가지 않는다. 표시 방식(`UNAVAILABLE` + `PROPOSAL_REJECTED`, 새 상태값 추가 없음)은 AI 제안이며 구현 착수 승인 시 확정.

### 제안 4: 응답에 결정 ID 추가 여부

- 내용: 승인 checklist가 응답에 포함될 때 `approvedChecklist.decisionId`와 `approvedChecklist.origin`을 함께 돌려준다. 추가 최상위 필드는 없음.
- 대안: 필드 추가 없이 version ID로만 추적.

**판단**
- [x] 채택
- 판단자 / 검토 대상 revision 또는 PR: 사용자 / PR #22
- 이유 / 승인 범위: 승인 checklist 응답에 사람 결정 ID와 출처를 포함한다(AC-02, AC-03).

### 제안 5: 한 Task로 구현

- 내용: PLAN-001 제안 C의 분할 없이 결정 기록, checklist 발행, 일정 revision 발행을 한 Task와 한 트랜잭션으로 구현한다.

**판단**
- [x] 채택 (방향 동의)
- 판단자 / 검토 대상 revision 또는 PR: 사용자 / PR #22
- 이유 / 승인 범위: 한 Task 구현 방향에 동의. 최종 완료 조건과 승인·수정·반려 예시 확인 뒤 구현 착수를 별도 판단.

### 완료 기준 변경 이력

- AC-13(d): "새 공문 수신은 차단"을 기존 시간 정책에 맞춰 (d1) 선택 불변 → 계속 사용 가능, (d2) 선택 변경 + checklist 불일치 → 차단, (d3) 선택 모호 → 차단으로 나눴다. (c)도 철회는 공문 단위 사건이라는 기존 정책대로 적었다. 결정자 사용자(구현 착수 승인 시 지시), 반영 PR: 구현 PR.
- AC-14: 보호 수치를 실제 코드 기준(테이블 35, trigger 70)으로 고쳤다. 계획 단계의 34/68은 실행 기록 테이블을 빼고 센 것이었다.

## 기존 정책과의 충돌 점검 (구현 전 조사, 사실)

- **별도 차단 규칙 없음.** 사용 허용 정책(`InternalChecklistUsePolicy`)은 과거 조회, 미래 업무일, 공문 선택 `SELECTED`, checklist `AVAILABLE`, 검증 유효, 공개 근거 확인, 의미 일치 7개 입력의 AND다. 공문 철회와 선택 변경·모호는 공문 선택 단계(기존 `selectNotice`)가 이미 처리하므로 TASK-007은 규칙을 추가하지 않고 입력값만 실제 값으로 연결했다.
- **대체한 placeholder 사유 2개.** 기존 조회는 checklist가 있으면 `CURRENT_VALIDATION_NOT_EVALUATED`, `CURRENT_PUBLIC_EVIDENCE_NOT_EVALUATED`를 넣고 정책 입력을 false로 고정했다(Day 5c 보류분). 이를 `FIXTURE_CHECKLIST_NOT_APPROVED`(테스트용 출처), `HUMAN_DECISION_MISSING`(HUMAN_REVIEW 출처지만 결정 기록 없음), `PUBLIC_EVIDENCE_UNCONFIRMED`(필수 공개 근거 미확인), 참고용은 경고 `INFORMATIONAL_PUBLIC_EVIDENCE_UNCONFIRMED`로 바꿨다. 기존 테스트의 수동 셀러론 HUMAN_REVIEW 자료(결정 없음)는 `HUMAN_DECISION_MISSING`으로 기대값을 바꿨다.
- **TASK-006 V-01과 수정(MODIFY)의 긴장(명확한 한계).** V-01은 변경안 항목 전체(설명 문구 포함)를 공문 규칙과 비교하므로 검수자가 설명 문구만 고친 revision도 `VALUE_MISMATCH` FAIL이 되어 승인할 수 없다. 공문 규칙과 같은 내용으로 다시 수정해야 PASS가 난다(AC-07 테스트가 두 경로를 모두 보인다). 이번 Task에서는 검사 기준을 완화하지 않았다. 완화는 별도 Task 계획([TASK-013](TASK-013_변경안-검사-기준-문구-수정-허용.md))으로 제안한다.
- 철회는 공문 단위 사건이다. 철회를 알게 된 뒤에는 이전 업무일 조회도 `WITHDRAWN`이며, 철회 전 기준 시각으로만 선택됐던 사실이 보인다. 첫 테스트 초안은 이를 잘못 가정했고 기대값을 기존 정책에 맞게 고쳤다(업무 로직 변경 없음).

## Implementation Result / AI self-review / 인간 검수 / 결정 기록

### 구현 결과 (AI 작성, 사실)

- 검증 대상: 브랜치 `feat/human-review-decision`(PR 본문에 commit 기재). 상세: [검증 기록](../evidence/HUMAN_REVIEW_DECISION_EVIDENCE.md).
- Java `./gradlew clean test bootJar --offline --no-daemon`: 142건 실행, 통과 142, 실패 0, 건너뜀 0 (기존 130 + 신규 12).
- Python 로컬(비공개 artifact 제공): 62건 실행, 통과 62, 실패 0, 건너뜀 0. 공개 CI 조건: 62건 실행, 통과 60, 건너뜀 2(비공개 snapshot artifact 없음). 공개 CI 실제 결과는 PR checks.
- 보호 수치(코드 기준): migration 8, 애플리케이션 테이블 35, 보호 trigger 70.
- AC-01~15 자동 검증 통과. AC-02는 첫 구현에서 DB 저장으로만 확인했으나 사용자 지적(동일한 검증이 아님)에 따라 응답 `approvedChecklist.items`를 추가하고 실제 반환 결과의 항목 내용·순서·근거 규칙 version·출처·결정 ID를 검증했다(보완 commit). AC-11은 결정 입력에 `knownAt`이 없어 과거 시각 입력 자체가 불가능하며 결정 시각이 서비스 시계와 같음을 확인했다. 인간 검수와 완료 판정은 미실시.

### AI self-review

- 승인 없이 사용 허용이 true가 되는 경로 없음: FIXTURE 출처, 결정 없는 HUMAN_REVIEW, 과거·미래 조회, 선택 변경·모호·철회, 필수 공개 근거 미확인은 모두 테스트로 차단을 확인했다.
- 승인 트랜잭션: 결정, checklist, 항목, 일정 revision, 항목이 한 트랜잭션이고 중간 실패 시 전부 취소(AC-10 테스트). 변경안당 결정 1건과 일정 leaf당 후속 revision 1건은 DB UNIQUE가 강제한다.
- 범위 밖 변경 없음: 인증, 화면, 승인 철회, 정기 재검증, HTTP 노출 미구현.
- 기존 테스트 변경은 placeholder 사유 교체, schema 수치, 반려 자료 추가와 그에 따른 기준 시각 이동(19:59:59 → 19:29:59, 의미 동일)에 한정했고 evidence에 적었다.

### 인간 검수 / 결정

미기록. 사용자 검수 뒤 기록.
