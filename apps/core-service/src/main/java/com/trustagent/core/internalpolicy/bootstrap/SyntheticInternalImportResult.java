package com.trustagent.core.internalpolicy.bootstrap;

import java.util.Map;

public record SyntheticInternalImportResult(String runId, String fingerprint, Map<String, Integer> counts) {}
