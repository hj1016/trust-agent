# Docker

로컬 개발과 단일 EC2 배포용 Docker Compose 구성을 관리합니다. 운영용 전체 compose(Core, PostgreSQL, AI 서비스, 프록시)는 TASK-023에서 추가합니다.

## 근거 검색용 Elasticsearch (`compose.search.yml`, TASK-016)

```bash
export TRUST_AGENT_ES_ELASTIC_PASSWORD='<관리자 비밀번호>'
export TRUST_AGENT_ES_REINDEX_PASSWORD='<재색인 사용자 비밀번호>'
export TRUST_AGENT_ES_SEARCH_PASSWORD='<검색 사용자 비밀번호>'
docker compose -f infra/docker/compose.search.yml up -d
infra/docker/search/init-users.sh        # 역할·사용자 생성(재색인 쓰기, 검색 읽기 전용). 재실행 안전
```

이미지는 digest로 고정했고(8.19.6), 포트는 `127.0.0.1:9200`에만 열립니다. 비밀번호는 환경변수로만 제공하며 파일·로그에 남기지 않습니다. 색인은 업무 원장이 아니며 Core의 재색인 runner가 다시 만들 수 있으므로 백업하지 않습니다.

### 한국어 분석기(nori) 이미지 (`search/Dockerfile.nori`, 검증용)

TASK-016 평가에서 standard 분석기는 한국어 질의를 처리하지 못해 nori를 우선 후보로 정했다. `Dockerfile.nori`는 compose와 같은 digest 위에 공식 `analysis-nori` 8.19.6을 버전 고정 URL로 받아 빌드 안에서 sha512를 대조한 뒤 설치한다. 체크섬은 공식 `.sha512`와 일치했고 `.asc` 서명은 Elastic 서명키 `46095ACC8548582C1A2699A9D27D666CD88E42B4`로 검증했으며, 플러그인 설명 파일의 `version`과 `elasticsearch.version`은 모두 8.19.6이다(2026-10-10 관측). 평가 하네스(`-PsearchAnalyzer=nori`)가 이 파일로 테스트 이미지를 만들고 기동 뒤 ES·플러그인 버전을 확인한다.

**`compose.search.yml`은 아직 standard 이미지 그대로다.** 배포 이미지 전환(빌드 위치, 이미지 digest 고정, AWS 반영)은 검색 수용 기준 판정 뒤 별도 승인으로 한다. ES 버전을 올리면 플러그인 버전·URL·sha512를 함께 바꿔야 하며, 버전이 다르면 플러그인 설치가 실패한다.

