# Contract Test

첫날 데이터 계약은 다음을 검증합니다.

- Git 기준 커밋과 핵심 기준 파일의 존재
- 공개 상품 snapshot manifest 3개와 content-addressed object key
- manifest JSON Schema 검증
- 자동 또는 수동 취득 방식과 수동 취득 사유 기록
- 비공개 artifact가 제공된 환경에서 byte size, SHA-256, 상품 식별 문자열 일치
- 합성 공문, 기업, 신청 fixture schema와 참조 무결성
- 경로별 데이터 분류와 `synthetic` 표시
- 의도적으로 잘못 배치한 데이터 분류의 거부

저장소 루트에서 다음 명령으로 실행합니다.

```bash
python3 -m unittest discover -s tests/contract -v
```

비공개 snapshot의 존재까지 필수로 검증할 때는 다음과 같이 실행합니다.

```bash
TRUSTAGENT_REQUIRE_PRIVATE_SNAPSHOTS=1 python3 -m unittest discover -s tests/contract -v
```

기본 artifact 위치는 `.private-artifacts`이며 `TRUSTAGENT_PRIVATE_ARTIFACT_ROOT`로 변경할 수 있습니다.
