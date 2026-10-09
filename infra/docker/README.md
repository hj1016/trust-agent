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
