package com.trustagent.core.internalpolicy.proposal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.trustagent.core.internalpolicy.proposal.ChecklistChangeProposalGenerator.ChangeType;
import com.trustagent.core.internalpolicy.proposal.ChecklistChangeProposalGenerator.Proposal;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class ChecklistChangeProposalGeneratorTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final ChecklistChangeProposalGenerator generator = new ChecklistChangeProposalGenerator(mapper);

    @Test
    void classifiesAddModifyRemoveAndSkipsUnchangedInRuleKeyOrder() {
        List<ChecklistItemContent> base = List.of(
                item("CHECK_B", "같음", true, null),
                item("CHECK_A", "변경 전", true, "{\"after_value\":\"1.2\"}"),
                item("CHECK_Z", "삭제됨", false, null));
        List<ChecklistItemContent> target = List.of(
                item("CHECK_A", "변경 후", true, "{\"after_value\":\"0.8\"}"),
                item("CHECK_B", "같음", true, null),
                item("CHECK_C", "추가", true, null));

        Proposal proposal = generator.generate("approved-checklist:" + "1".repeat(32), base, "SIN-X-V2", target, "proposal-generator-v1");

        assertEquals(3, proposal.items().size());
        assertEquals(List.of("CHECK_A", "CHECK_C", "CHECK_Z"),
                proposal.items().stream().map(ChecklistChangeProposalGenerator.Item::ruleKey).toList());
        assertEquals(List.of(ChangeType.MODIFY, ChangeType.ADD, ChangeType.REMOVE),
                proposal.items().stream().map(ChecklistChangeProposalGenerator.Item::changeType).toList());
        assertEquals(List.of(0, 1, 2), proposal.items().stream().map(ChecklistChangeProposalGenerator.Item::order).toList());
        var add = proposal.items().get(1);
        assertNull(add.before());
        assertNull(add.beforeHash());
        assertEquals("추가", add.after().get("instruction").stringValue());
        var remove = proposal.items().get(2);
        assertNull(remove.after());
        assertNull(remove.afterHash());
        var modify = proposal.items().get(0);
        assertEquals("1.2", modify.before().get("structured_change").get("after_value").stringValue());
        assertEquals("0.8", modify.after().get("structured_change").get("after_value").stringValue());
        assertTrue(proposal.proposalId().startsWith("checklist-proposal:sha256:"));
    }

    @Test
    void sameInputProducesSameProposalIdRegardlessOfInputOrder() {
        List<ChecklistItemContent> base = List.of(item("CHECK_A", "a", true, null), item("CHECK_B", "b", true, null));
        List<ChecklistItemContent> target = List.of(item("CHECK_B", "b2", true, null), item("CHECK_A", "a", true, null));

        Proposal first = generator.generate("approved-checklist:" + "2".repeat(32), base, "SIN-X-V2", target, "proposal-generator-v1");
        Proposal second = generator.generate("approved-checklist:" + "2".repeat(32), base.reversed(), "SIN-X-V2", target.reversed(), "proposal-generator-v1");
        Proposal otherGenerator = generator.generate("approved-checklist:" + "2".repeat(32), base, "SIN-X-V2", target, "proposal-generator-v2");

        assertEquals(first.proposalId(), second.proposalId());
        assertEquals(first.beforeHash(), second.beforeHash());
        assertEquals(first.afterHash(), second.afterHash());
        assertTrue(!first.proposalId().equals(otherGenerator.proposalId()));
    }

    @Test
    void duplicateRuleKeysAndFloatingPointValuesAreRejected() {
        List<ChecklistItemContent> duplicated = List.of(item("CHECK_A", "a", true, null), item("CHECK_A", "b", true, null));
        assertThrows(IllegalArgumentException.class,
                () -> generator.generate("approved-checklist:" + "3".repeat(32), duplicated, "SIN-X-V2", List.of(), "proposal-generator-v1"));

        List<ChecklistItemContent> floating = List.of(item("CHECK_A", "a", true, "{\"after_value\":1.2}"));
        assertThrows(IllegalArgumentException.class,
                () -> generator.generate("approved-checklist:" + "3".repeat(32), List.of(), "SIN-X-V2", floating, "proposal-generator-v1"));
    }

    private ChecklistItemContent item(String key, String instruction, boolean evidenceRequired, String structuredChange) {
        JsonNode change = structuredChange == null ? null : mapper.readTree(structuredChange);
        return new ChecklistItemContent(key, instruction, evidenceRequired, change);
    }
}
