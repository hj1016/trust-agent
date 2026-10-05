package com.trustagent.core.bootstrap;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import org.junit.jupiter.api.Test;

class AppendOnlyBootstrapChecksTest {

    @Test
    void exactCountsAreAccepted() {
        assertDoesNotThrow(() -> AppendOnlyBootstrapChecks.verifyExactCounts(
                Map.of("records", 2),
                ignored -> 2,
                TestFailure::new));
    }

    @Test
    void extraRowsAreReportedAsRuntimeData() {
        TestFailure failure = assertThrows(TestFailure.class, () ->
                AppendOnlyBootstrapChecks.verifyExactCounts(
                        Map.of("records", 2),
                        ignored -> 3,
                        TestFailure::new));

        assertEquals("RUNTIME_DATA_PRESENT", failure.code);
    }

    @Test
    void missingRowsAreReportedAsBaselineMismatch() {
        TestFailure failure = assertThrows(TestFailure.class, () ->
                AppendOnlyBootstrapChecks.verifyExactCounts(
                        Map.of("records", 2),
                        ignored -> 1,
                        TestFailure::new));

        assertEquals("BASELINE_CONTENT_MISMATCH", failure.code);
    }

    private static final class TestFailure extends RuntimeException {
        private final String code;

        private TestFailure(String code, String message) {
            super(message);
            this.code = code;
        }
    }
}
