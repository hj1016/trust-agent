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
 * 색인 문서 생성(ADR-013 결정 3, 개정: 문서 하나에 적용기간 여러 개).
 * 문서 ID = 규칙 version ID. 같은 규칙 version이 여러 승인 checklist·일정 구간에 쓰이면 문서 하나에 approvals[](승인 version·결정·구간)와
 * effective_ranges[](date_range)로 모두 담아 승인 이력과 적용기간의 대응을 잃지 않는다. 구간 시작은 포함, 종료는 제외([from, to))이며 종료가 없으면 무기한이다.
 * 같은 규칙 version인데 문장·위치·해시·구조화 값·공문군이 다르면 병합하지 않고 SEARCH_RULE_CONFLICT로 재색인을 중단한다(fail-closed).
 * 내용 해시는 indexed_at을 뺀 문서 목록의 canonical sha256이라 같은 승인 상태면 같다.
 */
final class SearchDocuments {

    static final String REINDEX_VERSION = "search-reindex-v1";
    static final List<String> FIELDS = List.of("rule_version_id", "family_id", "notice_id", "rule_key", "evidence_text", "structured_text",
            "json_pointer", "evidence_hash", "approvals", "effective_ranges", "dataset_class", "synthetic", "source_hash", "indexed_at");
    /** 같은 규칙 version의 줄들이 반드시 같아야 하는 필드. 다르면 병합하지 않는다. */
    static final List<String> MUST_MATCH = List.of("family_id", "notice_id", "rule_key", "evidence_text", "json_pointer", "evidence_hash", "structured_text");

    record Built(List<ObjectNode> documents, String contentHash) {}

    private final ObjectMapper mapper;
    private final CanonicalJsonHasher hasher;

    SearchDocuments(ObjectMapper mapper) {
        this.mapper = mapper;
        this.hasher = new CanonicalJsonHasher(mapper);
    }

    Built build(List<IndexedRule> rules, Instant indexedAt) {
        Map<String, List<IndexedRule>> byRule = new LinkedHashMap<>();
        for (IndexedRule rule : rules) {
            byRule.computeIfAbsent(rule.ruleVersionId(), key -> new ArrayList<>()).add(rule);
        }
        List<String> ids = new ArrayList<>(byRule.keySet());
        ids.sort(String::compareTo);
        ArrayNode hashSubject = mapper.createArrayNode();
        List<ObjectNode> documents = new ArrayList<>();
        for (String id : ids) {
            List<IndexedRule> group = new ArrayList<>(byRule.get(id));
            IndexedRule first = group.get(0);
            rejectConflicts(id, group);
            group.sort(Comparator.comparing(IndexedRule::effectiveFrom)
                    .thenComparing(IndexedRule::approvedChecklistVersionId)
                    .thenComparing(IndexedRule::decisionId));
            ObjectNode doc = mapper.createObjectNode();
            doc.put("rule_version_id", id);
            doc.put("family_id", first.familyId());
            doc.put("notice_id", first.noticeId());
            doc.put("rule_key", first.ruleKey());
            doc.put("evidence_text", first.evidenceText());
            doc.put("structured_text", structuredText(first.structuredChangeJson()));
            doc.put("json_pointer", first.jsonPointer());
            doc.put("evidence_hash", first.evidenceHash());
            ArrayNode approvals = doc.putArray("approvals");
            ArrayNode ranges = doc.putArray("effective_ranges");
            for (IndexedRule rule : group) {
                ObjectNode approval = approvals.addObject();
                approval.put("approved_checklist_version_id", rule.approvedChecklistVersionId());
                approval.put("decision_id", rule.decisionId());
                approval.put("effective_from", rule.effectiveFrom().toString());
                if (rule.effectiveTo() == null) approval.putNull("effective_to"); else approval.put("effective_to", rule.effectiveTo().toString());
                ObjectNode range = ranges.addObject();
                range.put("gte", rule.effectiveFrom().toString());
                if (rule.effectiveTo() != null) range.put("lt", rule.effectiveTo().toString());
            }
            doc.put("dataset_class", "SYNTHETIC_INTERNAL");
            doc.put("synthetic", true);
            doc.put("source_hash", hasher.canonicalize(doc).sha256());
            hashSubject.add(doc.deepCopy());
            doc.put("indexed_at", indexedAt.toString());
            documents.add(doc);
        }
        return new Built(documents, hasher.canonicalize(hashSubject).sha256());
    }

    /** 같은 규칙 version의 줄들이 문장·위치·해시·구조화 값·공문군에서 다르면 병합하지 않는다. 어느 필드가 다른지만 알리고 내용은 담지 않는다. */
    private void rejectConflicts(String ruleVersionId, List<IndexedRule> group) {
        IndexedRule first = group.get(0);
        List<String> differing = new ArrayList<>();
        for (IndexedRule other : group.subList(1, group.size())) {
            if (!first.familyId().equals(other.familyId()) && !differing.contains("family_id")) differing.add("family_id");
            if (!first.noticeId().equals(other.noticeId()) && !differing.contains("notice_id")) differing.add("notice_id");
            if (!first.ruleKey().equals(other.ruleKey()) && !differing.contains("rule_key")) differing.add("rule_key");
            if (!first.evidenceText().equals(other.evidenceText()) && !differing.contains("evidence_text")) differing.add("evidence_text");
            if (!first.jsonPointer().equals(other.jsonPointer()) && !differing.contains("json_pointer")) differing.add("json_pointer");
            if (!first.evidenceHash().equals(other.evidenceHash()) && !differing.contains("evidence_hash")) differing.add("evidence_hash");
            if (!structuredText(first.structuredChangeJson()).equals(structuredText(other.structuredChangeJson())) && !differing.contains("structured_text")) differing.add("structured_text");
        }
        if (!differing.isEmpty()) {
            throw new SearchIndexException("SEARCH_RULE_CONFLICT",
                    "같은 규칙 version이 승인 checklist마다 다른 내용을 가집니다: " + ruleVersionId + " 필드 " + differing);
        }
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
                "dataset_class", "source_hash")) {
            properties.putObject(keyword).put("type", "keyword");
        }
        for (String fullText : List.of("evidence_text", "structured_text")) {
            properties.putObject(fullText).put("type", "text").put("analyzer", "trustagent_text");
        }
        ObjectNode approvals = properties.putObject("approvals");
        approvals.put("type", "object");
        approvals.put("dynamic", "strict");
        ObjectNode approvalFields = approvals.putObject("properties");
        approvalFields.putObject("approved_checklist_version_id").put("type", "keyword");
        approvalFields.putObject("decision_id").put("type", "keyword");
        approvalFields.putObject("effective_from").put("type", "date").put("format", "strict_date");
        approvalFields.putObject("effective_to").put("type", "date").put("format", "strict_date");
        // 적용기간 배열. 시작 포함·종료 제외([gte, lt)), lt가 없으면 무기한. 업무일 필터는 range 질의(relation intersects)로 한다(검색 API PR).
        properties.putObject("effective_ranges").put("type", "date_range").put("format", "strict_date");
        properties.putObject("synthetic").put("type", "boolean");
        properties.putObject("indexed_at").put("type", "date");
        return definition;
    }
}
