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
    void sameRuleVersionAcrossScheduleRangesKeepsEveryApprovalAndRange() {
        IndexedRule first = rule("a", LocalDate.of(2026, 9, 15), null);
        IndexedRule closed = new IndexedRule(first.ruleVersionId(), first.familyId(), first.noticeId(), first.ruleKey(), first.evidenceText(), first.jsonPointer(),
                first.evidenceHash(), null, "approved-checklist:" + "5".repeat(32), "review-decision:" + "5".repeat(32), LocalDate.of(2026, 9, 15), LocalDate.of(2026, 9, 20));
        IndexedRule open = new IndexedRule(first.ruleVersionId(), first.familyId(), first.noticeId(), first.ruleKey(), first.evidenceText(), first.jsonPointer(),
                first.evidenceHash(), null, "approved-checklist:" + "2".repeat(32), "review-decision:" + "3".repeat(32), LocalDate.of(2026, 10, 1), null);
        SearchDocuments.Built built = documents.build(List.of(open, closed), Instant.parse("2026-10-06T03:00:00Z"));
        assertEquals(1, built.documents().size(), "문서는 규칙 version당 하나");
        var doc = built.documents().get(0);
        assertEquals(2, doc.get("approvals").size());
        assertEquals("approved-checklist:" + "5".repeat(32), doc.get("approvals").get(0).get("approved_checklist_version_id").stringValue(), "시행일 순");
        assertEquals("2026-09-20", doc.get("approvals").get(0).get("effective_to").stringValue());
        assertTrue(doc.get("approvals").get(1).get("effective_to").isNull());
        assertEquals("2026-09-15", doc.get("effective_ranges").get(0).get("gte").stringValue());
        assertEquals("2026-09-20", doc.get("effective_ranges").get(0).get("lt").stringValue());
        assertEquals("2026-10-01", doc.get("effective_ranges").get(1).get("gte").stringValue());
        assertTrue(!doc.get("effective_ranges").get(1).has("lt"), "종료일이 없으면 무기한(lt 없음)");
    }

    @Test
    void conflictingContentForTheSameRuleVersionIsRejectedNotMerged() {
        IndexedRule first = rule("a", LocalDate.of(2026, 9, 15), "{\"after_value\":\"0.8\"}");
        IndexedRule different = new IndexedRule(first.ruleVersionId(), first.familyId(), first.noticeId(), first.ruleKey(), first.evidenceText(), first.jsonPointer(),
                first.evidenceHash(), "{\"after_value\":\"0.5\"}", "approved-checklist:" + "5".repeat(32), "review-decision:" + "5".repeat(32), LocalDate.of(2026, 10, 1), null);
        SearchIndexException error = org.junit.jupiter.api.Assertions.assertThrows(SearchIndexException.class,
                () -> documents.build(List.of(first, different), Instant.parse("2026-10-06T03:00:00Z")));
        assertEquals("SEARCH_RULE_CONFLICT", error.code());
        assertTrue(error.getMessage().contains("structured_text"), error.getMessage());
        assertTrue(!error.getMessage().contains("0.5"), "내용은 메시지에 담지 않는다");
    }

    @Test
    void settingsHashAndIndexNameChangeWhenOnlyTheAnalyzerChanges() {
        String standard = documents.settingsHash("standard");
        String nori = documents.settingsHash("nori");
        assertNotEquals(standard, nori, "분석기만 달라도 설정 해시가 다르다");
        assertEquals(standard, documents.settingsHash("standard"), "같은 설정이면 같은 해시");
        String content = "sha256:" + "a".repeat(64);
        assertNotEquals(documents.indexSuffix(content, standard), documents.indexSuffix(content, nori), "같은 문서라도 분석기가 다르면 색인 이름이 다르다");
        assertEquals(12, documents.indexSuffix(content, standard).length());
        var definition = documents.indexDefinition("standard", "main", content, standard, 1, Instant.parse("2026-10-06T03:00:00Z"));
        assertEquals(standard, definition.path("mappings").path("_meta").path("settings_hash").stringValue());
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
