# 둘째 날 완료 증거

## 검증 시점

- 실행 시각: 2026-09-22T14:01:40Z
- 브랜치: `feat/public-snapshot-collector`
- 실행 환경: Python 3.11.8

## 구현 역할

Snapshot collector는 catalog의 공식 공개 URL을 비공개 raw HTML artifact와 공개 가능한 manifest로 변환합니다. 이후 product version과 fact 저장이 외부 페이지를 직접 호출하지 않고 검증된 snapshot을 입력으로 사용하게 합니다.

## 자동화 테스트

실행 명령:

```bash
TRUSTAGENT_REQUIRE_PRIVATE_SNAPSHOTS=1 python3 -m unittest discover -s tests -v
```

결과:

```text
Ran 23 tests in 0.052s

OK
```

검증 범위:

- catalog schema와 공식 KB URL allowlist
- redirect 목적지 allowlist
- HTML content type과 상품 식별 문자열
- 응답 크기 제한
- raw byte 기준 SHA-256과 content-addressed object key
- 동일 원문의 artifact와 manifest 중복 제거
- 변경된 원문의 새 artifact와 manifest 생성
- HTTP 실패 시 기존 데이터 보존
- manifest 저장 실패 시 새 artifact 정리
- 수동 취득 사유 필수 기록
- 전체 manifest schema와 비공개 artifact 무결성
- 기존 데이터 분류 contract 회귀 테스트

## Public clone 조건

비공개 artifact가 없는 환경을 다음 명령으로 모의했습니다.

```bash
TRUSTAGENT_PRIVATE_ARTIFACT_ROOT=/private/tmp/trust-agent-missing-artifacts \
python3 -m unittest discover -s tests -v
```

결과는 23개 중 공개 원문 무결성 테스트 1개만 의도대로 skip되고 나머지는 통과했습니다.

## 실제 수집 결과

첫 실행:

| product_key | status | byte size | SHA-256 |
| --- | --- | ---: | --- |
| `small-business-credit` | `CREATED` | 90,223 | `08d48f15e16d98ce8db9e6d399defd12cbb47bc673243666f34bc386cb701c90` |
| `boss-plus-overdraft` | `CREATED` | 98,953 | `fa7560e52dda5788bce3f3eb73e06b2dbf7fbefbd19d96b757c9c6fb513dac73` |
| `kb-seller-loan` | `DEDUPLICATED` | 124,137 | `def6b5b670fc0adf36909ffd1ce47bd416a67e213f7127b5058c44681fe7e44b` |

같은 명령을 즉시 다시 실행했을 때 세 상품이 모두 `DEDUPLICATED`로 반환됐습니다.

새 hash가 생성된 두 상품의 전날 원문과 비교한 결과 금리 기준일 표시가 `2026.09.21`에서 `2026.09.22`로 변경됐습니다. KB 셀러론은 원문 byte가 동일해 기존 artifact와 manifest를 재사용했습니다.

## 구현 중 확인한 위험과 대응

### TLS 신뢰 저장소

로컬 Python의 기본 CA 경로가 비어 있어 최초 실제 수집에서 인증서 검증이 실패했습니다. TLS 검증을 비활성화하지 않고 `certifi` CA bundle을 runtime dependency로 추가했습니다.

### Raw snapshot의 잦은 변경

금리 기준일처럼 표시 날짜만 변경돼도 raw hash가 달라집니다. Collector는 원문 변경을 그대로 보존하며, 실제 product fact 변경 여부는 다음 product version 단계에서 별도로 판단합니다.

### 외부 사이트 장애

수집 실패 시 기존 artifact와 manifest를 변경하지 않습니다. 자동 retry와 scheduling은 이번 PR 범위에 포함하지 않았습니다.

### 비공개 artifact 가용성

Public Git에는 raw HTML이 없으므로 전체 무결성 검증에는 `.private-artifacts`가 필요합니다. 원격 private object storage와 backup 정책은 후속 운영 범위입니다.

## 다음 작업

수집된 snapshot에서 product version과 정규화된 fact를 생성하고, 각 fact에 snapshot hash, source locator와 parser version을 연결합니다.
