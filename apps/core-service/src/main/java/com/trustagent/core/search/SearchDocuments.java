package com.trustagent.core.search;

import com.trustagent.core.json.CanonicalJsonHasher;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 색인 문서 생성(ADR-013 결정 3). 필드는 Tool 1·2가 내줄 수 있는 범위를 넘지 않는다.
 * 같은 규칙 version이 여러 승인 checklist에 있으면 시행일이 늦은 쪽 하나만 남긴다(문서 ID = 규칙 version ID).
 * 내용 해시는 indexed_at을 뺀 문서 목록의 canonical sha256이라 같은 승인 상태면 같다.
 */
final class SearchDocuments {

    static final String REINDEX_VERSION = "search-reindex-v1";
    static final List<String> FIELDS = List.of("rule_version_id", "family_id", "notice_id", "rule_key", "evidence_text", "structured_text",
            "json_pointer", "evidence_hash", "approved_checklist_version_id", "decision_id", "effective_from", "effective_to",
            "dataset_class", "synthetic", "source_hash", "indexed_at");

    record Built(List<ObjectNode> documents, String contentHash) {}

    private final ObjectMapper mapper;
    private final CanonicalJsonHasher hasher;

    SearchDocuments(ObjectMapper mapper) {
        this.mapper = mapper;
        this.hasher = new CanonicalJsonHasher(mapper);
    }

    Built build(List<IndexedRule> rules, Instant indexedAt) {
        Map<String, IndexedRule> byRule = new LinkedHashMap<>();
        for (IndexedRule rule : rules) {
            IndexedRule existing = byRule.get(rule.ruleVersionId());
            if (existing == null || rule.effectiveFrom().isAfter(existing.effectiveFrom())) {
                byRule.put(rule.ruleVersionId(), rule);
            }
        }
        List<IndexedRule> ordered = new ArrayList<>(byRule.values());
        ordered.sort(Comparator.comparing(IndexedRule::ruleVersionId));
        ArrayNode hashSubject = mapper.createArrayNode();
        List<ObjectNode> documents = new ArrayList<>();
        for (IndexedRule rule : ordered) {
            ObjectNode doc = mapper.createObjectNode();
            doc.put("rule_version_id", rule.ruleVersionId());
            doc.put("family_id", rule.familyId());
            doc.put("notice_id", rule.noticeId());
            doc.put("rule_key", rule.ruleKey());
            doc.put("evidence_text", rule.evidenceText());
            doc.put("structured_text", structuredText(rule.structuredChangeJson()));
            doc.put("json_pointer", rule.jsonPointer());
            doc.put("evidence_hash", rule.evidenceHash());
            doc.put("approved_checklist_version_id", rule.approvedChecklistVersionId());
            doc.put("decision_id", rule.decisionId());
            doc.put("effective_from", rule.effectiveFrom().toString());
            if (rule.effectiveTo() == null) doc.putNull("effective_to"); else doc.put("effective_to", rule.effectiveTo().toString());
            doc.put("dataset_class", "SYNTHETIC_INTERNAL");
            doc.put("synthetic", true);
            doc.put("source_hash", hasher.canonicalize(doc).sha256());
            hashSubject.add(doc.deepCopy());
            doc.put("indexed_at", indexedAt.toString());
            documents.add(doc);
        }
        return new Built(documents, hasher.canonicalize(hashSubject).sha256());
    }

    /** 구조화 값을 검색용 문장으로 편다. 키를 정렬해 결정적이며 값이 없으면 빈 문자열이다. */
    String structuredText(String structuredChangeJson) {
        if (structuredChangeJson == null || structuredChangeJson.isBlank() || "null".equals(structuredChangeJson.trim())) {
            return "";
        }
        JsonNode node = mapper.readTree(structuredChangeJson);
        if (!node.isObject()) return "";
        List<String> keys = new ArrayList<>();
        node.propertyNames().forEach(keys::add);
        keys.sort(String::compareTo);
        StringBuilder text = new StringBuilder();
        for (String key : keys) {
            JsonNode value = node.get(key);
            if (value == null || value.isNull()) continue;
            String rendered;
            if (value.isArray()) {
                List<String> parts = new ArrayList<>();
                value.forEach(item -> parts.add(item.isValueNode() ? item.asString() : item.toString()));
                if (parts.isEmpty()) continue;
                rendered = String.join(", ", parts);
            } else if (value.isValueNode()) {
                rendered = value.asString();
            } else {
                rendered = value.toString();
            }
            if (!text.isEmpty()) text.append(' ');
            text.append(key).append(' ').append(rendered);
        }
        return text.toString();
    }

    /** 색인 설정(분석기·mapping·reindex 버전)의 해시. 문서 내용이 같아도 설정이 다르면 새 색인을 만든다. */
    String settingsHash(String analyzer) {
        ObjectNode definition = (ObjectNode) indexDefinition(analyzer, "", "", 0, Instant.EPOCH);
        ((ObjectNode) definition.get("mappings")).remove("_meta");
        return hasher.canonicalize(definition).sha256();
    }

    /** 색인 이름 접미사: 내용 해시와 설정 해시를 합친 해시의 앞 12자. */
    String indexSuffix(String contentHash, String settingsHash) {
        ObjectNode subject = mapper.createObjectNode();
        subject.put("content_hash", contentHash);
        subject.put("settings_hash", settingsHash);
        String digest = hasher.canonicalize(subject).sha256();
        return digest.substring("sha256:".length(), "sha256:".length() + 12);
    }

    JsonNode indexDefinition(String analyzer, String workspaceId, String contentHash, int documentCount, Instant indexedAt) {
        return indexDefinition(analyzer, workspaceId, contentHash, null, documentCount, indexedAt);
    }

    JsonNode indexDefinition(String analyzer, String workspaceId, String contentHash, String settingsHash, int documentCount, Instant indexedAt) {
        ObjectNode definition = mapper.createObjectNode();
        ObjectNode settings = definition.putObject("settings");
        settings.putObject("index").put("number_of_shards", 1).put("number_of_replicas", 0);
        ObjectNode text = settings.putObject("analysis").putObject("analyzer").putObject("trustagent_text");
        text.put("type", "custom");
        text.put("tokenizer", SearchProperties.ANALYZER_NORI.equals(analyzer) ? "nori_tokenizer" : "standard");
        text.putArray("filter").add("lowercase");
        ObjectNode mappings = definition.putObject("mappings");
        mappings.put("dynamic", "strict");
        ObjectNode meta = mappings.putObject("_meta");
        meta.put("reindex_version", REINDEX_VERSION);
        meta.put("workspace_id", workspaceId);
        meta.put("content_hash", contentHash);
        if (settingsHash != null) meta.put("settings_hash", settingsHash);
        meta.put("document_count", documentCount);
        meta.put("analyzer", analyzer);
        meta.put("indexed_at", indexedAt.toString());
        ObjectNode properties = mappings.putObject("properties");
        for (String keyword : List.of("rule_version_id", "family_id", "notice_id", "rule_key", "json_pointer", "evidence_hash",
                "approved_checklist_version_id", "decision_id", "dataset_class", "source_hash")) {
            properties.putObject(keyword).put("type", "keyword");
        }
        for (String fullText : List.of("evidence_text", "structured_text")) {
            properties.putObject(fullText).put("type", "text").put("analyzer", "trustagent_text");
        }
        properties.putObject("effective_from").put("type", "date").put("format", "strict_date");
        properties.putObject("effective_to").put("type", "date").put("format", "strict_date");
        properties.putObject("synthetic").put("type", "boolean");
        properties.putObject("indexed_at").put("type", "date");
        return definition;
    }
}
