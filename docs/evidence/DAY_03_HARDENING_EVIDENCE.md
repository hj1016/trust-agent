# 셋째 날 pipeline hardening 완료 증거

## 검증 시점과 기준선

- 최종 검증일: 2026-09-24
- 브랜치: `feat/day-03-pipeline-hardening`
- 시작 commit: `fe1c200` (`origin/main`과 일치)
- 실행 환경: Python 3.11.8
- 설계 기준: 승인된 `docs/adr/ADR-006-public-observation-and-product-terms.md`

## 서비스에서 담당하는 역할

공개 KB pipeline은 공개 페이지의 raw byte, 관측 사건, 안정적인 상품 조건, 시점별
광고 금리와 처리 이력을 분리합니다. 공개 페이지는 내부 공문을 대신하는 법적 증거가
아니며, 상담·심사 준비에서 공개 정보의 교차 검증과 변경 감지에 쓰는 감사 evidence입니다.

Core service가 이후 사용할 입력은 다음과 같습니다.

- Snapshot: raw byte의 content identity
- Observation: 상품 URL을 실제로 확인한 사건
- ProductTermsVersion: 가입 대상, 한도, 상환 방법, 판매 상태의 의미 identity
- VersionEvidence: Observation과 terms version의 원문 근거 연결
- ObservedRateQuote: 관측 시점의 광고 금리 문구와 명시된 기준일
- CollectionAttempt 및 ExtractionAttempt: 성공과 실패를 포함한 처리 이력
- ChangeDetectionResult: terms 변경과 quote 변경을 구분한 판정

## 필요한 이유

- 페이지가 매일 표시하는 금리 기준일이 가짜 상품 version을 만들지 않아야 합니다.
- 같은 HTML을 다시 확인한 사실과 A-B-A 복귀를 snapshot 중복 제거와 별개로 남겨야
  합니다.
- 판매종료와 parser 실패가 이전 `SELLING` 상태를 확인된 최신 정보처럼 노출시키지
  않아야 합니다.
- 수집·추출 실패와 관측 공백을 clone 이후에도 검증할 수 있는 정제 audit record가
  필요합니다.
- Spring DB와 조회 API를 만들기 전에 entity 소유권, freshness 우선순위와 차단 책임을
  고정해야 합니다.

## 구현 결과

- Snapshot manifest 5건을 hash, byte size, content type, object key와 canonical URL만
  소유하는 신규 schema로 같은 경로에서 재작성했습니다.
- 기존 product version 5건을 active dataset에서 제거하고 ProductTermsVersion 3건을
  생성했습니다.
- Observation, VersionEvidence, ObservedRateQuote, CollectionAttempt,
  ExtractionAttempt와 ChangeDetectionResult를 각각 5건 생성했습니다.
- `datasets/derived/public-kb/`의 세 정제 audit 하위 경로만 Git allowlist에 포함했습니다.
- 수집과 추출은 상품 단위 실패를 격리하고 구조화된 실행 summary를 반환합니다.
- 판매 상태는 확인된 `SELLING`과 `DISCONTINUED`만 정규화하고 미지 문구는 실패로
  남깁니다.
- freshness 대표 상태는
  `UNAVAILABLE > UNCONFIRMED_AFTER_FAILURE > PENDING_EXTRACTION > STALE > CONFIRMED`
  순서이며, 동시에 성립한 사유는 복수 `blocking_reasons`로 보존합니다.
- Freshness의 extraction 상태 입력은 최신 Observation에 속한 최신 attempt로 한정하며,
  그 Observation에 attempt가 없으면 이전 성공 상태 대신 `null`을 전달합니다.
- Evidence나 quote가 일부만 저장된 불완전 Observation은 변경 판정 시간축에서 건너뛰고
  다른 완전한 Observation과 상품 처리를 계속합니다. 저장 실패는 FAILED
  ExtractionAttempt로 남깁니다.

## Migration 재현 정보

Migration은 쓰기 전에 private artifact 5개의 존재, SHA-256, byte size와 parser
호환성을 모두 preflight합니다. 일부 manifest를 먼저 바꾼 뒤 뒤쪽 입력 오류로 중단하지
않습니다. 다음 고정 run ID를 사용하므로 event ID가 재실행마다 바뀌지 않습니다.

- Collection run ID: `run:c85c3aa2b2525055bb330533a7285ba8`
- Extraction run ID: `run:cfab60d1673952ee8378160fd3005a3b`

기존 version ID와 신규 terms version ID의 mapping은 다음과 같습니다.

이 legacy mapping은 삭제 전 product version 파일과 Git history에서 읽어 evidence에
고정한 값입니다. Active migration 입력에서 legacy 디렉터리를 제거했으므로 migration
report만 재실행해서 이 mapping을 다시 생성하지는 않습니다.

| product_key | 기존 product version hash | 신규 terms hash |
| --- | --- | --- |
| `boss-plus-overdraft` | `143c681502cd9fba94d5f4c7b9f839bc8f409daedc0ef8ce31c72a53ef15a2ec` | `eb46f7bfb5f9c1cd63dfdb3f4028c2162c3c754d004fe303bffafd18865c7111` |
| `boss-plus-overdraft` | `ce55b861f57dee172c19648131615e8b7915efdce1f0e439bc369563b92ccaca` | `eb46f7bfb5f9c1cd63dfdb3f4028c2162c3c754d004fe303bffafd18865c7111` |
| `kb-seller-loan` | `27d45dcd93aca48a5227049e313cd4160e6f3cd4892837df123d0a1c16250cd2` | `647d5d3588b9d16dce6e4bd91044018a5ab0287c174ac7a945b85bc14abd031d` |
| `small-business-credit` | `50f520a7578c039c665d4962decd41bdea89eae9bab19e42c6d3bb8a46329c27` | `f90c200926ffab9a5eaf291acc3dc8576dbe09256f476f45924454408e0381e3` |
| `small-business-credit` | `e00b68694fb92277b2c1c19f7357744653aa50dd6d971e53a4d5209870b3b680` | `f90c200926ffab9a5eaf291acc3dc8576dbe09256f476f45924454408e0381e3` |

Observation 5건은 확인 가능한 하한선입니다. 기존 collector가 동일 snapshot 재관측을
기록하지 않았으므로 이 수를 과거 실제 수집 횟수로 해석하지 않습니다. 기존 manifest에
없던 final URL도 추정하지 않고 migration Observation에서 `null`로 유지했습니다.

Migration이 만든 ExtractionAttempt 5건의 `attempted_at`은 실제 추출 시각을 측정한
값이 아닙니다. 결정적인 과거 baseline을 만들기 위해 각 Observation의 `observed_at`을
복사했으며 `attempted_at_source=BACKFILLED_FROM_OBSERVATION`으로 표시합니다. 실제
재추출은 2026-09-23부터 2026-09-24 사이의 migration 실행 중에 수행됐습니다. 따라서
이 5건의 `attempted_at`을 실제 처리 시각으로 해석하지 않습니다.

## 변경 판정 evidence

| product_key | 관측 | 분류 |
| --- | --- | --- |
| `boss-plus-overdraft` | 2026-09-21 최초 관측 | `BASELINE_ESTABLISHED` |
| `boss-plus-overdraft` | 2026-09-22 기준일 갱신 | `QUOTE_REFRESHED` |
| `kb-seller-loan` | 2026-09-21 최초 관측 | `BASELINE_ESTABLISHED` |
| `small-business-credit` | 2026-09-21 최초 관측 | `BASELINE_ESTABLISHED` |
| `small-business-credit` | 2026-09-22 기준일 갱신 | `QUOTE_REFRESHED` |

두 후속 관측은 광고 금리 문구와 terms가 같고 기준일만 달라 새 terms version이나
규정담당자 변경 알림 대상이 되지 않습니다.

## 검토한 대안

- 기준일만 hash에서 제외: 광고 금리 문구 변경이 여전히 상품 조건 변경으로 오인됩니다.
- Quote를 terms 파일에 남기고 identity에서만 제외: 최초 quote가 공유 version에 고정돼
  이후 관측 evidence가 사라집니다.
- Observation에 추출 결과를 사후 갱신: append-only 관측 사건을 훼손하므로 별도
  ExtractionAttempt를 사용했습니다.
- raw HTML 또는 live KB 수집을 Public CI에 포함: 비공개 artifact 경계와 결정적 CI를
  깨므로 private golden test와 공개 fixture를 분리했습니다.
- 이전 `SELLING` terms를 실패 후 계속 정상 제공: 소비 계층에서 fail-open이 되므로
  freshness projection과 Core 승인 차단 계약을 선택했습니다.

## 위험과 제한사항

- 공개 HTML label이나 DOM 변경 시 parser가 실패할 수 있습니다. 실패 attempt와
  `UNCONFIRMED_AFTER_FAILURE`로 노출하고 임의 추출하지 않습니다.
- 이 Python freshness 함수는 Day 4 Core service가 구현할 조회 계약의 기준입니다.
  운영 policy version, threshold 설정과 승인 API 차단은 Spring 구현에서 추가 검증해야
  합니다.
- 프로세스 강제 종료로 attempt record도 쓰지 못한 경우는 아직 실행 누락과 구분할 수
  없습니다. 후속 Batch run audit와 reconciliation 범위입니다.
- Parser profile의 catalog 데이터화, robots.txt, rate limiting과 429/5xx backoff는 별도
  확장 과제입니다.
- Parser version은 ProductTermsVersion identity가 아니라 VersionEvidence와
  ExtractionAttempt에 보존하므로 parser 교체가 같은 terms의 immutable 충돌을 만들지
  않습니다.
- 공개 페이지는 내부 공문상의 실제 effective date를 제공하지 않으므로
  `effective_from`과 `effective_to`를 추정하지 않습니다.

## 테스트와 검증 evidence

Private artifact 필수 검증:

```bash
TRUSTAGENT_REQUIRE_PRIVATE_SNAPSHOTS=1 python3 -m unittest discover -s tests -v
```

결과:

```text
Ran 49 tests

OK
```

이 실행은 artifact hash와 byte size, 5개 artifact golden 재추출, 고정 run ID migration
재실행의 canonical JSON 동일성까지 포함합니다.

Public Git 조건 검증:

```bash
TRUSTAGENT_PRIVATE_ARTIFACT_ROOT=/private/tmp/trust-agent-no-private-artifacts \
python3 -m unittest discover -s tests -v
```

결과:

```text
Ran 49 tests

OK (skipped=3)
```

Skip 대상은 private artifact hash 검사, 실제 HTML golden 재추출과 deterministic
migration 재현입니다. 나머지 46개는 통과했습니다.

추가 검증 범위:

- 모든 contract와 dataset JSON 문법 및 schema
- Python source compile
- `git diff --check`와 CI forbidden-file 검사
- Dataset boundary 검사
- 동일 content 재관측, attempt replay와 A-B-A 관측
- composite claim locator와 `20억원` 단위 회귀
- 판매종료와 미지 판매 상태 fail-closed
- RFC 3339 검증, UTC 정규화와 소수점 초 instant 정렬
- freshness 우선순위와 복수 blocking reason
- baseline의 `QUOTE_REFRESHED` 및 terms version 재사용

## 다음 작업과의 연결

Day 4 Spring Boot는 신규 schema만 적재하고 ProductTermsVersion, 최신 VersionEvidence,
attempt와 freshness projection을 조회 모델로 사용합니다. DB migration과 API를 구현할 때
Core 승인 API가 stale, pending, failed와 unavailable 상태를 차단하고 수기 checklist는
계속 제공하는지 통합 테스트로 증명해야 합니다.

## Day 4 설계 검토 후 보강

2026-09-24 Day 4 설계 검토에서 migration ExtractionAttempt의 시각 출처가 명시되지
않은 문제를 확인했습니다. Contract와 baseline에 `attempted_at_source`를 추가하고,
일반 추출은 `MEASURED`, 기존 5건은 `BACKFILLED_FROM_OBSERVATION`으로 구분했습니다.

보강 후 private artifact 필수 모드는 50개 테스트가 모두 통과했고, Public Git 모드는
50개 중 private 전용 3개가 skip되고 나머지 47개가 통과했습니다. 기존 49개 실행 기록은
당시 결과로 유지하며, 추가된 1개 contract test가 migration attempt 5건의 출처와
Observation 시각 복사 관계를 검증합니다.
