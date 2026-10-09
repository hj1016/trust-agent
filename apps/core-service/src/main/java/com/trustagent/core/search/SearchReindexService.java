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
 * 내용 해시와 설정 해시(분석기·mapping)가 같은 색인이 이미 alias를 가리키면 아무것도 만들지 않는다(멱등). 분석기를 바꾸면 새 색인이다.
 * alias가 가리키는 현재 색인은 새 색인의 준비·검증·전환 전에 지우지 않는다. 실패하면 alias를 바꾸지 않아 옛 색인이 남는다.
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
        String settingsHash = documents.settingsHash(properties.analyzer());
        String alias = properties.alias();
        String targetBase = properties.indexNamePrefix() + documents.indexSuffix(built.contentHash(), settingsHash);
        Optional<String> current = elasticsearch.aliasTarget(alias);

        // 생략 판단은 이름이 아니라 현재 색인의 메타(내용·설정 해시, reindex 버전)와 문서 수로 한다. 복구 접미사(-r…) 색인도 유효하면 그대로 쓴다.
        if (current.isPresent() && currentIsValid(current.get(), built, settingsHash)) {
            LOGGER.info("재색인 생략: alias={} index={} documents={} (내용·설정 해시 동일)", alias, current.get(), built.documents().size());
            return new Result(Outcome.ALREADY_CURRENT, alias, current.get(), built.documents().size(), built.contentHash(), List.of());
        }
        // 원칙: alias가 가리키는 현재 색인은 새 색인의 준비·검증·전환이 끝나기 전에는 절대 지우지 않는다.
        // 현재 색인 이름이 목표 이름과 같은데 내용이 어긋나면(문서 수·메타 불일치) 다른 이름으로 새로 만든다.
        String candidate = current.isPresent() && current.get().equals(targetBase) ? targetBase + "-r" + now.getEpochSecond() : targetBase;
        if (elasticsearch.indexExists(candidate)) {
            // 현재 색인이 아닌 잔여 색인(이전 실행이 alias 교체 전에 끊긴 경우)만 지운다.
            elasticsearch.deleteIndex(candidate);
        }
        try {
            elasticsearch.createIndex(candidate, documents.indexDefinition(properties.analyzer(), properties.workspaceId(), built.contentHash(),
                    settingsHash, built.documents().size(), now));
            elasticsearch.bulkIndex(candidate, built.documents());
            elasticsearch.refresh(candidate);
            long indexed = elasticsearch.count(candidate);
            if (indexed != built.documents().size()) {
                throw new SearchIndexException("SEARCH_COUNT_MISMATCH", "색인 문서 수 " + indexed + " ≠ 대상 " + built.documents().size());
            }
        } catch (SearchIndexException failure) {
            LOGGER.warn("재색인 실패로 새 색인 {}을 지우고 기존 alias {}를 유지합니다: {}", candidate, alias, failure.code());
            try {
                elasticsearch.deleteIndex(candidate);
            } catch (SearchIndexException cleanupFailure) {
                LOGGER.warn("부분 색인 삭제 실패(다음 재색인이 다시 만든다): {}", cleanupFailure.code());
            }
            throw failure;
        }
        elasticsearch.swapAlias(alias, candidate, current);
        List<String> removed = new ArrayList<>();
        for (String index : elasticsearch.indicesWithPrefix(properties.indexNamePrefix())) {
            if (!index.equals(candidate)) {
                elasticsearch.deleteIndex(index);
                removed.add(index);
            }
        }
        LOGGER.info("재색인 완료: alias={} index={} documents={} removed={}", alias, candidate, built.documents().size(), removed);
        return new Result(Outcome.CREATED, alias, candidate, built.documents().size(), built.contentHash(), removed);
    }

    /** 생략 조건: 현재 색인의 메타(내용 해시, 설정 해시, reindex 버전)와 실제 문서 수가 예상과 모두 같을 때만. */
    private boolean currentIsValid(String index, SearchDocuments.Built built, String settingsHash) {
        if (!elasticsearch.indexExists(index)) return false;
        var meta = elasticsearch.mappingMeta(index);
        return built.contentHash().equals(meta.path("content_hash").stringValue())
                && settingsHash.equals(meta.path("settings_hash").stringValue())
                && SearchDocuments.REINDEX_VERSION.equals(meta.path("reindex_version").stringValue())
                && elasticsearch.count(index) == built.documents().size();
    }
}
