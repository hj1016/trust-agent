package com.trustagent.core.publicproduct.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class PublicEvidenceConfirmationPolicyTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PublicEvidenceConfirmationPolicy policy = new PublicEvidenceConfirmationPolicy();

    @Test
    void javaMatchesEverySharedConfirmationPolicyCase() throws Exception {
        JsonNode fixture = objectMapper.readTree(Path.of(
                System.getProperty("trustAgent.repositoryRoot"),
                "contracts/fixtures/public-product-confirmation-policy-cases.json").toFile());

        for (JsonNode testCase : fixture.get("cases")) {
            JsonNode expected = testCase.get("expected");
            var result = policy.evaluate(new PublicEvidenceConfirmationPolicy.Input(
                    instant(testCase, "evaluated_at"),
                    instant(testCase, "as_of"),
                    Duration.ofSeconds(testCase.get("max_confirmation_age_seconds").longValue()),
                    nullableInstant(testCase, "last_confirmed_at"),
                    nullableInstant(testCase, "latest_observation_at"),
                    testCase.get("has_newer_observation").booleanValue(),
                    nullableStatus(testCase, "latest_observation_extraction_status"),
                    nullableStatus(testCase, "latest_collection_status")));

            assertEquals(expected.get("freshness_status").stringValue(), result.freshnessStatus().name(), name(testCase));
            assertEquals(strings(expected.get("blocking_reasons")), result.blockingReasons(), name(testCase));
            assertEquals(strings(expected.get("warning_reasons")), result.warningReasons(), name(testCase));
            assertEquals(expected.get("historical_query").booleanValue(), result.historicalQuery(), name(testCase));
            assertEquals(
                    expected.get("public_evidence_confirmation_allowed").booleanValue(),
                    result.publicEvidenceConfirmationAllowed(),
                    name(testCase));
            assertEquals(
                    strings(expected.get("confirmation_blocking_reasons")),
                    result.confirmationBlockingReasons(),
                    name(testCase));
        }
    }

    @Test
    void futureAsOfIsRejected() {
        Instant evaluatedAt = Instant.parse("2026-09-23T12:00:00Z");
        var error = assertThrows(
                PublicProductQueryException.class,
                () -> policy.evaluate(new PublicEvidenceConfirmationPolicy.Input(
                        evaluatedAt,
                        evaluatedAt.plusSeconds(1),
                        Duration.ofDays(1),
                        evaluatedAt.minusSeconds(1),
                        evaluatedAt.minusSeconds(1),
                        false,
                        PublicEvidenceConfirmationPolicy.AttemptStatus.SUCCEEDED,
                        PublicEvidenceConfirmationPolicy.AttemptStatus.SUCCEEDED)));
        assertEquals("FUTURE_AS_OF_NOT_ALLOWED", error.code());
    }

    private static String name(JsonNode testCase) {
        return testCase.get("name").stringValue();
    }

    private static Instant instant(JsonNode node, String field) {
        return Instant.parse(node.get(field).stringValue());
    }

    private static Instant nullableInstant(JsonNode node, String field) {
        return node.get(field).isNull() ? null : instant(node, field);
    }

    private static PublicEvidenceConfirmationPolicy.AttemptStatus nullableStatus(JsonNode node, String field) {
        return node.get(field).isNull()
                ? null
                : PublicEvidenceConfirmationPolicy.AttemptStatus.valueOf(node.get(field).stringValue());
    }

    private static java.util.List<String> strings(JsonNode values) {
        return StreamSupport.stream(values.spliterator(), false)
                .map(JsonNode::stringValue)
                .toList();
    }
}
