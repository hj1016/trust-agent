package com.trustagent.core.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class SearchDocumentsTest {

    private final SearchDocuments documents = new SearchDocuments(new ObjectMapper());

    private static IndexedRule rule(String id, LocalDate from, String structured) {
        return new IndexedRule("policy-rule:sha256:" + id.repeat(64).substring(0, 64), "SIN-PREPAYMENT-FEE", "SIN-PREPAYMENT-FEE-V2",
                "CHECK_PREPAYMENT_FEE_RATE", "중도상환수수료율은 0.8퍼센트다.", "/rules/0", "sha256:" + "1".repeat(64), structured,
                "approved-checklist:" + "2".repeat(32), "review-decision:" + "3".repeat(32), from, null);
    }

    @Test
    void structuredTextIsDeterministicAndSkipsNulls() {
        String text = documents.structuredText("{\"after_value\":\"0.8\",\"unit\":\"PERCENT\",\"exceptions\":[\"별도 약정\"],\"conditions\":[],\"before_value\":null,\"effective_on\":\"2026-10-01\"}");
        assertEquals("after_value 0.8 effective_on 2026-10-01 exceptions 별도 약정 unit PERCENT", text);
        assertEquals("", documents.structuredText(null));
        assertEquals("", documents.structuredText("null"));
    }

    @Test
    void contentHashIgnoresIndexedAtAndChangesWithContent() {
        List<IndexedRule> rules = List.of(rule("a", LocalDate.of(2026, 10, 1), null));
        SearchDocuments.Built first = documents.build(rules, Instant.parse("2026-10-06T03:00:00Z"));
        SearchDocuments.Built later = documents.build(rules, Instant.parse("2026-10-07T03:00:00Z"));
        assertEquals(first.contentHash(), later.contentHash());
        assertNotEquals(first.documents().get(0).get("indexed_at").stringValue(), later.documents().get(0).get("indexed_at").stringValue());
        SearchDocuments.Built changed = documents.build(List.of(rule("a", LocalDate.of(2026, 10, 1), "{\"after_value\":\"0.5\"}")), Instant.parse("2026-10-06T03:00:00Z"));
        assertNotEquals(first.contentHash(), changed.contentHash());
        assertTrue(first.documents().get(0).get("source_hash").stringValue().startsWith("sha256:"));
    }

    @Test
    void duplicateRuleVersionKeepsLatestEffectiveFrom() {
        List<IndexedRule> rules = List.of(rule("a", LocalDate.of(2026, 9, 15), null), rule("a", LocalDate.of(2026, 10, 1), null));
        SearchDocuments.Built built = documents.build(rules, Instant.parse("2026-10-06T03:00:00Z"));
        assertEquals(1, built.documents().size());
        assertEquals("2026-10-01", built.documents().get(0).get("effective_from").stringValue());
    }

    @Test
    void indexDefinitionUsesStrictMappingAndRequestedAnalyzer() {
        var definition = documents.indexDefinition("nori", "main", "sha256:" + "0".repeat(64), 3, Instant.parse("2026-10-06T03:00:00Z"));
        assertEquals("strict", definition.path("mappings").path("dynamic").stringValue());
        assertEquals("nori_tokenizer", definition.path("settings").path("analysis").path("analyzer").path("trustagent_text").path("tokenizer").stringValue());
        assertEquals(3, definition.path("mappings").path("_meta").path("document_count").intValue());
        for (String field : SearchDocuments.FIELDS) {
            assertTrue(definition.path("mappings").path("properties").has(field), field);
        }
    }
}
