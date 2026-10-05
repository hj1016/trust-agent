package com.trustagent.core.internalpolicy.proposal;

import com.trustagent.core.json.CanonicalJsonHasher;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 직전 승인 checklist 항목과 새 공문 rule을 rule_key 단위로 비교해 결정적인 변경안을 만든다.
 * 같은 입력과 같은 generator version은 같은 proposal_id를 만든다. LLM을 쓰지 않는다. SQL이 없다.
 */
public final class ChecklistChangeProposalGenerator {

    public static final String ID_PREFIX = "checklist-proposal:";

    public enum ChangeType { ADD, MODIFY, REMOVE }

    public record Item(
            int order,
            String ruleKey,
            ChangeType changeType,
            JsonNode before,
            JsonNode after,
            String beforeHash,
            String afterHash) {}

    public record Proposal(
            String proposalId,
            String baseChecklistVersionId,
            String targetNoticeId,
            String generatorVersion,
            String beforeHash,
            String afterHash,
            List<Item> items) {}

    private final ObjectMapper mapper;
    private final CanonicalJsonHasher hasher;

    public ChecklistChangeProposalGenerator(ObjectMapper mapper) {
        this.mapper = mapper;
        this.hasher = new CanonicalJsonHasher(mapper);
    }

    public Proposal generate(
            String baseChecklistVersionId,
            List<ChecklistItemContent> baseItems,
            String targetNoticeId,
            List<ChecklistItemContent> targetRules,
            String generatorVersion) {
        Map<String, ChecklistItemContent> base = index(baseItems, "base checklist");
        Map<String, ChecklistItemContent> target = index(targetRules, "target rules");

        TreeSet<String> keys = new TreeSet<>(base.keySet());
        keys.addAll(target.keySet());

        List<Item> items = new ArrayList<>();
        ArrayNode identityItems = mapper.createArrayNode();
        int order = 0;
        for (String key : keys) {
            ChecklistItemContent before = base.get(key);
            ChecklistItemContent after = target.get(key);
            ChangeType type;
            if (before == null) {
                type = ChangeType.ADD;
            } else if (after == null) {
                type = ChangeType.REMOVE;
            } else if (!canonical(before).equals(canonical(after))) {
                type = ChangeType.MODIFY;
            } else {
                continue;
            }
            JsonNode beforeJson = before == null ? null : before.toJson(mapper);
            JsonNode afterJson = after == null ? null : after.toJson(mapper);
            items.add(new Item(
                    order++,
                    key,
                    type,
                    beforeJson,
                    afterJson,
                    beforeJson == null ? null : hasher.canonicalize(beforeJson).sha256(),
                    afterJson == null ? null : hasher.canonicalize(afterJson).sha256()));
            ObjectNode identityItem = mapper.createObjectNode();
            identityItem.put("rule_key", key);
            identityItem.put("change_type", type.name());
            setOrNull(identityItem, "before", beforeJson);
            setOrNull(identityItem, "after", afterJson);
            identityItems.add(identityItem);
        }

        ObjectNode identity = mapper.createObjectNode();
        identity.put("base_checklist_version_id", baseChecklistVersionId);
        identity.put("target_notice_id", targetNoticeId);
        identity.put("generator_version", generatorVersion);
        identity.set("items", identityItems);

        return new Proposal(
                ID_PREFIX + hasher.canonicalize(identity).sha256(),
                baseChecklistVersionId,
                targetNoticeId,
                generatorVersion,
                contentSetHash(base),
                contentSetHash(target),
                List.copyOf(items));
    }

    private Map<String, ChecklistItemContent> index(List<ChecklistItemContent> contents, String description) {
        Map<String, ChecklistItemContent> byKey = new LinkedHashMap<>();
        for (ChecklistItemContent content : contents) {
            if (byKey.putIfAbsent(content.ruleKey(), content) != null) {
                throw new IllegalArgumentException(description + "에 rule_key가 중복됩니다: " + content.ruleKey());
            }
        }
        return byKey;
    }

    private String canonical(ChecklistItemContent content) {
        return hasher.canonicalize(content.toJson(mapper)).json();
    }

    private String contentSetHash(Map<String, ChecklistItemContent> contents) {
        ArrayNode array = mapper.createArrayNode();
        new TreeMap<>(contents).values().forEach(content -> array.add(content.toJson(mapper)));
        return hasher.canonicalize(array).sha256();
    }

    private static void setOrNull(ObjectNode node, String field, JsonNode value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.set(field, value);
        }
    }
}
