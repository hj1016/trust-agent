package com.trustagent.core.search;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

/**
 * 재색인(ADR-013 결정 1·6). 업무 DB의 승인 근거로 새 색인을 만들고 alias를 바꾼 뒤 옛 색인을 지운다.
 * 내용 해시가 같은 색인이 이미 alias를 가리키면 아무것도 만들지 않는다(멱등). 실패하면 alias를 바꾸지 않아 옛 색인이 남는다.
 * ES 호출은 DB 트랜잭션 밖에서 일어난다(읽기만 하고 쓰지 않는다).
 */
public class SearchReindexService {

    public enum Outcome { CREATED, ALREADY_CURRENT }

    public record Result(Outcome outcome, String alias, String indexName, int documentCount, String contentHash, List<String> removedIndices) {}

    private static final Logger LOGGER = LoggerFactory.getLogger(SearchReindexService.class);

    private final RuleEvidenceIndexRepository repository;
    private final ElasticsearchClient elasticsearch;
    private final SearchDocuments documents;
    private final SearchProperties properties;
    private final Clock clock;

    public SearchReindexService(JdbcClient jdbc, ElasticsearchClient elasticsearch, SearchProperties properties, ObjectMapper mapper, Clock clock) {
        this.repository = new RuleEvidenceIndexRepository(jdbc);
        this.elasticsearch = elasticsearch;
        this.documents = new SearchDocuments(mapper);
        this.properties = properties;
        this.clock = clock;
    }

    public Result reindex() {
        Instant now = clock.instant();
        List<IndexedRule> rules = repository.loadApprovedRules();
        SearchDocuments.Built built = documents.build(rules, now);
        String alias = properties.alias();
        String target = properties.indexNamePrefix() + built.contentHash().substring("sha256:".length(), "sha256:".length() + 12);
        Optional<String> current = elasticsearch.aliasTarget(alias);

        if (current.isPresent() && current.get().equals(target) && elasticsearch.indexExists(target)
                && elasticsearch.count(target) == built.documents().size()) {
            LOGGER.info("재색인 생략: alias={} index={} documents={} (내용 해시 동일)", alias, target, built.documents().size());
            return new Result(Outcome.ALREADY_CURRENT, alias, target, built.documents().size(), built.contentHash(), List.of());
        }
        if (elasticsearch.indexExists(target)) {
            // 이전 실행이 alias 교체 전에 끊긴 경우: 불완전할 수 있으므로 다시 만든다.
            elasticsearch.deleteIndex(target);
        }
        // alias 교체 전에 실패하면 새 색인을 지우고 예외를 던진다. 기존 alias와 색인은 그대로 남는다(fail-closed).
        try {
            elasticsearch.createIndex(target, documents.indexDefinition(properties.analyzer(), properties.workspaceId(), built.contentHash(),
                    built.documents().size(), now));
            elasticsearch.bulkIndex(target, built.documents());
            elasticsearch.refresh(target);
            long indexed = elasticsearch.count(target);
            if (indexed != built.documents().size()) {
                throw new SearchIndexException("SEARCH_COUNT_MISMATCH", "색인 문서 수 " + indexed + " ≠ 대상 " + built.documents().size());
            }
        } catch (SearchIndexException failure) {
            LOGGER.warn("재색인 실패로 새 색인 {}을 지우고 기존 alias {}를 유지합니다: {}", target, alias, failure.code());
            try {
                elasticsearch.deleteIndex(target);
            } catch (SearchIndexException cleanupFailure) {
                LOGGER.warn("부분 색인 삭제 실패(다음 재색인이 다시 만든다): {}", cleanupFailure.code());
            }
            throw failure;
        }
        elasticsearch.swapAlias(alias, target, current);
        List<String> removed = new ArrayList<>();
        for (String index : elasticsearch.indicesWithPrefix(properties.indexNamePrefix())) {
            if (!index.equals(target)) {
                elasticsearch.deleteIndex(index);
                removed.add(index);
            }
        }
        LOGGER.info("재색인 완료: alias={} index={} documents={} removed={}", alias, target, built.documents().size(), removed);
        return new Result(Outcome.CREATED, alias, target, built.documents().size(), built.contentHash(), removed);
    }
}
