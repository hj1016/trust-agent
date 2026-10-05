package com.trustagent.core.internalpolicy.proposal;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * checklist 항목 또는 공문 rule의 비교 가능한 내용. 승인 checklist 항목과 공문 rule을 같은 형태로 맞춰
 * rule_key 단위로 비교한다. structured_change는 JSON null을 포함할 수 있다.
 */
public record ChecklistItemContent(
        String ruleKey,
        String instruction,
        boolean evidenceRequired,
        JsonNode structuredChange) {

    public ChecklistItemContent {
        if (ruleKey == null || ruleKey.isBlank()) {
            throw new IllegalArgumentException("rule_key가 필요합니다.");
        }
        if (instruction == null || instruction.isBlank()) {
            throw new IllegalArgumentException("instruction이 필요합니다.");
        }
    }

    /** canonical hash와 저장에 쓰는 JSON 표현. 키 집합은 계약 schema와 정답 fixture와 같아야 한다. */
    public ObjectNode toJson(ObjectMapper mapper) {
        ObjectNode node = mapper.createObjectNode();
        node.put("rule_key", ruleKey);
        node.put("instruction", instruction);
        node.put("evidence_required", evidenceRequired);
        if (structuredChange == null || structuredChange.isNull()) {
            node.putNull("structured_change");
        } else {
            node.set("structured_change", structuredChange.deepCopy());
        }
        return node;
    }
}
