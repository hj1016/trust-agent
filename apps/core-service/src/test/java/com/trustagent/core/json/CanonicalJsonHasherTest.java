package com.trustagent.core.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class CanonicalJsonHasherTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private final CanonicalJsonHasher hasher = new CanonicalJsonHasher(objectMapper);

    @Test
    void javaMatchesTheSharedCanonicalJsonHashCases() throws Exception {
        Path repositoryRoot = Path.of(System.getProperty("trustAgent.repositoryRoot"));
        JsonNode fixture = objectMapper.readTree(Files.readString(
                repositoryRoot.resolve("contracts/fixtures/canonical-json-hash-cases.json")));

        for (JsonNode testCase : fixture.get("cases")) {
            var actual = hasher.canonicalize(testCase.get("input"));
            assertEquals(testCase.get("expected_canonical_json").stringValue(), actual.json());
            assertEquals(testCase.get("expected_sha256").stringValue(), actual.sha256());
        }
    }

    @Test
    void floatingPointNumbersAreRejected() throws Exception {
        Path repositoryRoot = Path.of(System.getProperty("trustAgent.repositoryRoot"));
        JsonNode fixture = objectMapper.readTree(Files.readString(
                repositoryRoot.resolve("contracts/fixtures/canonical-json-hash-cases.json")));

        for (JsonNode testCase : fixture.get("rejected_cases")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> hasher.canonicalize(testCase.get("input")),
                    testCase.get("name").stringValue());
        }
    }
}
