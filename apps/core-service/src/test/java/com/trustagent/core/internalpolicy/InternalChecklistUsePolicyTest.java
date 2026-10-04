package com.trustagent.core.internalpolicy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.trustagent.core.internalpolicy.InternalChecklistUsePolicy.ChecklistStatus;
import com.trustagent.core.internalpolicy.InternalChecklistUsePolicy.NoticeStatus;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class InternalChecklistUsePolicyTest {

    @Test
    void sharedPolicyCasesFixPriorityAndEveryUseGate() throws IOException {
        Path root = Path.of(System.getProperty("trustAgent.repositoryRoot"));
        JsonNode cases = new ObjectMapper().readTree(
                root.resolve("contracts/fixtures/internal-checklist-availability-policy-cases.json")
                        .toFile());
        InternalChecklistUsePolicy policy = new InternalChecklistUsePolicy();
        for (JsonNode item : cases) {
            String name = item.get("name").stringValue();
            if (item.has("caseType") && "VALIDATION_AGE".equals(item.get("caseType").stringValue())) {
                assertEquals(
                        item.get("expectedFresh").asBoolean(),
                        policy.validationFresh(
                                Instant.parse(item.get("validatedAt").stringValue()),
                                Instant.parse(item.get("evaluatedAt").stringValue()),
                                Duration.ofSeconds(item.get("maxAgeSeconds").asLong())),
                        name);
                continue;
            }
            var result = policy.evaluate(new InternalChecklistUsePolicy.Input(
                    enumSet(item.get("noticeConditions"), NoticeStatus.class),
                    enumSet(item.get("checklistConditions"), ChecklistStatus.class),
                    item.get("historical").asBoolean(), item.get("futureBusinessDate").asBoolean(),
                    item.get("validationFresh").asBoolean(), item.get("publicEvidenceConfirmed").asBoolean(),
                    item.get("semanticMatch").asBoolean()));
            assertEquals(item.get("expectedNoticeStatus").stringValue(), result.noticeStatus().name(), name);
            assertEquals(item.get("expectedChecklistStatus").stringValue(), result.checklistStatus().name(), name);
            assertEquals(item.get("expectedAllowed").asBoolean(), result.internalChecklistUseAllowed(), name);
        }
    }

    private static <T extends Enum<T>> EnumSet<T> enumSet(JsonNode values, Class<T> type) {
        EnumSet<T> result = EnumSet.noneOf(type);
        values.forEach(value -> result.add(Enum.valueOf(type, value.stringValue())));
        return result;
    }
}
