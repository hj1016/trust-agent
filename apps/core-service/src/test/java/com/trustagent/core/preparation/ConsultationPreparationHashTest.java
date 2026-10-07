package com.trustagent.core.preparation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 준비안 ID 해시 대상(제안 8). 실행마다 달라지는 값(run_id, consultation_id, sections[].evaluated_at, sections[].tool_response_hash)은
 * ID에 들어가지 않고, 업무 내용(checklist version·결정·항목·근거 해시·선택 공문·매핑·상태·업무일·신청·문구 표·조립기 버전)이 바뀌면 ID가 바뀐다.
 * DB 없이 해시 규칙만 검증한다.
 */
class ConsultationPreparationHashTest {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final ConsultationPreparationService service = new ConsultationPreparationService(
            JdbcClient.create(new DriverManagerDataSource()), mapper,
            new DataSourceTransactionManager(new DriverManagerDataSource()), null, Clock.systemUTC());

    @Test
    void runSpecificValuesDoNotChangeTheId() {
        String base = service.contentHash(body());
        assertEquals(base, service.contentHash(edited(b -> b.put("run_id", "consultation-preparation-run:" + "f".repeat(32)))));
        assertEquals(base, service.contentHash(edited(b -> b.put("consultation_id", "other-consultation"))));
        assertEquals(base, service.contentHash(edited(b -> b.remove("consultation_id"))));
        assertEquals(base, service.contentHash(edited(b -> section(b, 0).put("evaluated_at", "2026-10-06T09:00:00Z"))));
        assertEquals(base, service.contentHash(edited(b -> section(b, 0).put("tool_response_hash", "sha256:" + "9".repeat(64)))));
        assertEquals(base, service.contentHash(edited(b -> section(b, 1).putNull("evaluated_at"))));
        assertEquals(base, service.contentHash(edited(b -> b.put("preparation_id", "consultation-preparation:sha256:" + "0".repeat(64)))));
    }

    @Test
    void businessContentChangesTheId() {
        String base = service.contentHash(body());
        Map<String, Consumer<ObjectNode>> changes = Map.ofEntries(
                Map.entry("approved_checklist_version_id", b -> section(b, 0).put("approved_checklist_version_id", "approved-checklist:" + "8".repeat(32))),
                Map.entry("decision_id", b -> section(b, 0).put("decision_id", "review-decision:" + "8".repeat(32))),
                Map.entry("item order", b -> { ArrayNode ids = (ArrayNode) section(b, 0).get("item_rule_version_ids"); var first = ids.remove(0); ids.add(first);
                    ArrayNode hs = (ArrayNode) section(b, 0).get("item_evidence_hashes"); var h = hs.remove(0); hs.add(h); }),
                Map.entry("item removed", b -> { ((ArrayNode) section(b, 0).get("item_rule_version_ids")).remove(1); ((ArrayNode) section(b, 0).get("item_evidence_hashes")).remove(1); }),
                Map.entry("evidence hash", b -> ((ArrayNode) section(b, 0).get("item_evidence_hashes")).set(0, mapper.getNodeFactory().stringNode("sha256:" + "7".repeat(64)))),
                Map.entry("selected notice", b -> section(b, 0).put("selected_notice_id", "SIN-PREPAYMENT-FEE-V1")),
                Map.entry("section status", b -> { section(b, 1).put("status", "READY"); }),
                Map.entry("hold_kind", b -> section(b, 1).put("hold_kind", "UNVERIFIED")),
                Map.entry("blocking_reasons", b -> ((ArrayNode) section(b, 1).get("blocking_reasons")).add("VALIDATION_FAILED")),
                Map.entry("required", b -> section(b, 1).put("required", false)),
                Map.entry("family_mapping_hash", b -> b.put("family_mapping_hash", "sha256:" + "6".repeat(64))),
                Map.entry("status", b -> b.put("status", "HOLD")),
                Map.entry("preparation_complete", b -> b.put("preparation_complete", true)),
                Map.entry("business_date", b -> b.put("business_date", "2026-10-07")),
                Map.entry("application_id", b -> ((ObjectNode) b.get("application")).put("application_id", "SW-APPLICATION-002")),
                Map.entry("source_hash", b -> ((ObjectNode) b.get("application")).put("source_hash", "sha256:" + "5".repeat(64))),
                Map.entry("messages_hash", b -> b.put("messages_hash", "sha256:" + "4".repeat(64))),
                Map.entry("assembler_version", b -> b.put("assembler_version", "preparation-assembler-v2")));
        changes.forEach((name, change) -> assertNotEquals(base, service.contentHash(edited(change)), name));
    }

    private ObjectNode edited(Consumer<ObjectNode> change) {
        ObjectNode body = body();
        change.accept(body);
        return body;
    }

    private static ObjectNode section(ObjectNode body, int index) {
        return (ObjectNode) body.get("sections").get(index);
    }

    private ObjectNode body() {
        ObjectNode body = mapper.createObjectNode();
        body.put("preparation_id", "consultation-preparation:sha256:" + "a".repeat(64));
        body.put("run_id", "consultation-preparation-run:" + "a".repeat(32));
        body.put("assembler_version", "preparation-assembler-v1");
        body.put("messages_hash", "sha256:" + "e".repeat(64));
        body.put("family_mapping_hash", "sha256:" + "d".repeat(64));
        ObjectNode application = body.putObject("application");
        application.put("application_id", "SW-APPLICATION-001");
        application.put("company_id", "SW-COMPANY-001");
        application.put("product_key", "kb-seller-loan");
        application.put("source_hash", "sha256:" + "f".repeat(64));
        body.put("business_date", "2026-10-06");
        body.put("consultation_id", "demo-1");
        body.put("status", "PARTIAL");
        body.put("preparation_complete", false);
        ArrayNode sections = body.putArray("sections");
        ObjectNode ready = sections.addObject();
        ready.put("family_id", "SIN-PREPAYMENT-FEE").put("required", true).put("status", "READY");
        ready.putNull("hold_kind").putNull("hold_claim_basis");
        ready.put("evaluated_at", "2026-10-06T03:00:00Z").put("selected_notice_id", "SIN-PREPAYMENT-FEE-V2");
        ready.put("approved_checklist_version_id", "approved-checklist:" + "1".repeat(32)).put("decision_id", "review-decision:" + "3".repeat(32));
        ArrayNode ids = ready.putArray("item_rule_version_ids");
        ArrayNode hashes = ready.putArray("item_evidence_hashes");
        for (String n : List.of("1", "2", "3")) {
            ids.add("policy-rule:sha256:" + n.repeat(64));
            hashes.add("sha256:" + n.repeat(64));
        }
        ready.putArray("blocking_reasons");
        ready.put("tool_response_hash", "sha256:" + "b".repeat(64));
        ObjectNode hold = sections.addObject();
        hold.put("family_id", "SIN-SELLER-CHECKLIST").put("required", true).put("status", "HOLD");
        hold.put("hold_kind", "CORE_DECISION").put("hold_claim_basis", "CORE_REPORTED");
        hold.put("evaluated_at", "2026-10-06T03:00:00Z").put("selected_notice_id", "SIN-SELLER-CHECKLIST-V2");
        hold.putNull("approved_checklist_version_id").putNull("decision_id");
        hold.putArray("item_rule_version_ids");
        hold.putArray("item_evidence_hashes");
        hold.putArray("blocking_reasons").add("HUMAN_REVIEW_PENDING");
        hold.put("tool_response_hash", "sha256:" + "c".repeat(64));
        return body;
    }
}
