package com.trustagent.core.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 수동 재색인 명령(TASK-016 제안 4). --trust-agent.search-reindex.enabled=true로 기동하면 한 번 실행한다.
 * 사람 결정·철회 적재 뒤 실행해야 하며, 실행 전까지 새로 승인된 규정은 검색 후보에 없다(한계는 README에 명시).
 */
@Component
@ConditionalOnProperty(name = "trust-agent.search-reindex.enabled", havingValue = "true")
final class SearchReindexRunner implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(SearchReindexRunner.class);

    private final SearchReindexService service;
    private final SearchProperties properties;

    SearchReindexRunner(SearchReindexService service, SearchProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.credentialsConfigured()) {
            throw new IllegalStateException("SEARCH_CREDENTIALS_MISSING: 재색인 사용자와 비밀번호가 필요합니다(환경변수).");
        }
        SearchReindexService.Result result = service.reindex();
        LOGGER.info("SEARCH_REINDEX {} alias={} index={} documents={} contentHash={} removed={}", result.outcome(), result.alias(),
                result.indexName(), result.documentCount(), result.contentHash(), result.removedIndices());
    }
}
