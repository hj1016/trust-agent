# 첫날 완료 증거

## 검증 시점

- 실행 시각: 2026-09-21T05:13:09Z
- 실행 환경: Python 3.11.8, jsonschema 4.26.0

## Git 기준선

- commit ID: `582df9dd5b684c701a64dcbd79c5fdbd1da8cdb2`
- 검증: `HEAD^{commit}`가 존재하고 README, 첫날 계획, 공개 snapshot manifest schema가 해당 commit에 포함됨

## 공개 snapshot

| product_key | byte size | SHA-256 |
| --- | ---: | --- |
| `small-business-credit` | 90,223 | `25ae7dd8c18f06578ee0b26e6116f689754d175b1a9c8baed4574c9c7ee1cf2d` |
| `boss-plus-overdraft` | 98,953 | `74165910b9fc08ac639995c443895210b8845b0df8cb49ec4e00daee2f8af3c5` |
| `kb-seller-loan` | 124,137 | `def6b5b670fc0adf36909ffd1ce47bd416a67e213f7127b5058c44681fe7e44b` |

raw HTML은 공개 Git에 포함하지 않고 `.private-artifacts/public-kb/sha256/` 아래의 content-addressed 비공개 artifact로 보관합니다. 각 manifest는 JSON Schema를 통과했고 기록된 byte size와 SHA-256이 비공개 artifact에서 다시 계산한 값과 일치했습니다. 세 원문에는 catalog의 상품명 식별 문자열이 포함되어 있습니다.

## 합성 fixture와 경계

- `SYNTHETIC_INTERNAL`: 합성 공문 1건
- `SYNTHETIC_WORK`: 합성 기업 1건, 합성 상담 신청 1건
- 모든 fixture가 전용 JSON Schema를 통과함
- 합성 공문의 `PUBLIC_KB` snapshot hash 참조가 실제 manifest에 존재함
- 합성 신청의 `company_id`가 합성 기업 fixture에 존재함
- `PUBLIC_KB` record를 합성 내부 경로에 둔 위반 사례를 거부함
- `SYNTHETIC_WORK` record를 공개 경로에 둔 위반 사례를 거부함

## 반복 실행 결과

실행 명령:

```bash
TRUSTAGENT_REQUIRE_PRIVATE_SNAPSHOTS=1 python3 -m unittest discover -s tests/contract -v
```

결과:

```text
Ran 9 tests

OK
```
