# 최종 기획서 정합화 검증 기록

> 이 문서는 PostgreSQL 18.6 Testcontainers 기준의 Pre-SDLC 증거이며 Oracle 기준에서는 재검증되지 않았다. Core 업무 DB는 Oracle로 확정됐다(ADR-009). 본문의 Java/Gradle 결과와 PostgreSQL 메커니즘 서술은 역사적 기록이다.

## 변경 범위

- 합성 중도상환수수료 공문 v1과 v2 추가
- 수수료율 1.2퍼센트에서 0.8퍼센트로의 변경, 시행일, 대상 상품, 조건과 예외 구조화
- `structured_change` JSON 계약과 Flyway V5 저장 필드 추가
- synthetic importer의 저장, 재검증과 조회 API 응답 연결
- 최종 기획서 대비 구현 현황과 다음 작업 순서 문서화

금융 비율은 canonical JSON hash가 부동소수점 오차를 허용하지 않는 원칙을 지키기 위해 문자열 십진수 `"1.2"`, `"0.8"`과 단위 `PERCENT`로 저장합니다.

## 테스트 evidence

검증 대상 revision: 커밋 `a6149bb` 이후의 미커밋 작업 트리(Flyway V5 포함). 환경: 로컬 macOS, Python 3.11, Java 21, PostgreSQL 18.6 Testcontainers(Docker). 로컬 실행 결과입니다.

```text
python3 -m unittest discover -s tests -v
Ran 58 tests
OK
```

```text
./gradlew test bootJar --offline --no-daemon
82 tests completed
BUILD SUCCESSFUL
Spring Boot executable jar 생성 성공
```

추가된 회귀 검증은 다음을 확인합니다.

- 중도상환수수료 v1/v2 schema와 supersession 관계
- 변경 전 1.2퍼센트, 변경 후 0.8퍼센트, 시행일의 구조화 계약
- 네 공문과 네 receipt 및 extraction의 전체 baseline 적재
- Flyway V5 적용과 schema version 일치
- 구조화 변경 JSON의 importer 저장과 재조회 일치
- 기준일 조회 API가 v2 공문과 구조화 변경을 함께 반환

## 남은 제한

이 변경은 기획서 대표 시나리오의 원천 계약과 조회 기반을 맞춘 것입니다. 변경 proposal, 자동 검증 결과, 사람의 검수와 제공 승인, 행원 화면과 상담 지원이 완료된 것은 아닙니다.
