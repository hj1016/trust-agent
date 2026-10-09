#!/usr/bin/env bash
# Elasticsearch 역할·사용자 초기화(ADR-013 결정 7). 재색인 사용자(쓰기)와 검색 사용자(읽기 전용)를 분리한다.
# 비밀번호는 환경변수로만 받는다. 값은 출력하지 않는다. 같은 값으로 다시 실행해도 안전하다(PUT).
set -euo pipefail
: "${TRUST_AGENT_ES_URL:=http://127.0.0.1:9200}"
: "${TRUST_AGENT_ES_ELASTIC_PASSWORD:?TRUST_AGENT_ES_ELASTIC_PASSWORD 환경변수가 필요합니다}"
: "${TRUST_AGENT_ES_REINDEX_PASSWORD:?TRUST_AGENT_ES_REINDEX_PASSWORD 환경변수가 필요합니다}"
: "${TRUST_AGENT_ES_SEARCH_PASSWORD:?TRUST_AGENT_ES_SEARCH_PASSWORD 환경변수가 필요합니다}"
indices='trustagent-rule-evidence-*'
auth="elastic:${TRUST_AGENT_ES_ELASTIC_PASSWORD}"
put() { curl -fsS -o /dev/null -u "${auth}" -H 'Content-Type: application/json' -X PUT "${TRUST_AGENT_ES_URL}$1" -d "$2"; echo "ok $1"; }
put /_security/role/trustagent_reindex_role "{\"indices\":[{\"names\":[\"${indices}\"],\"privileges\":[\"create_index\",\"delete_index\",\"manage\",\"write\",\"read\",\"view_index_metadata\"]}]}"
put /_security/role/trustagent_search_role "{\"indices\":[{\"names\":[\"${indices}\"],\"privileges\":[\"read\",\"view_index_metadata\"]}]}"
put /_security/user/trustagent_reindex "{\"password\":\"${TRUST_AGENT_ES_REINDEX_PASSWORD}\",\"roles\":[\"trustagent_reindex_role\"]}"
put /_security/user/trustagent_search "{\"password\":\"${TRUST_AGENT_ES_SEARCH_PASSWORD}\",\"roles\":[\"trustagent_search_role\"]}"
