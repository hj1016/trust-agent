package com.trustagent.core.publicproduct.baseline;

import java.util.Map;

public record BaselineImportResult(
        String runId,
        String baselineFingerprint,
        String status,
        Map<String, Integer> importedCounts) {
}
