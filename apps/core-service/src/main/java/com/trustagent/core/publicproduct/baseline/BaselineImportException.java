package com.trustagent.core.publicproduct.baseline;

import tools.jackson.databind.JsonNode;

public final class BaselineImportException extends RuntimeException {

    private final String code;
    private final String baselineFingerprint;
    private final JsonNode inputFiles;

    public BaselineImportException(String code, String message) {
        this(code, message, null, null, null);
    }

    public BaselineImportException(String code, String message, Throwable cause) {
        this(code, message, cause, null, null);
    }

    private BaselineImportException(
            String code,
            String message,
            Throwable cause,
            String baselineFingerprint,
            JsonNode inputFiles) {
        super(message, cause);
        this.code = code;
        this.baselineFingerprint = baselineFingerprint;
        this.inputFiles = inputFiles == null ? null : inputFiles.deepCopy();
    }

    public String code() {
        return code;
    }

    BaselineImportException withAuditInput(String fingerprint, JsonNode files) {
        return new BaselineImportException(code, getMessage(), this, fingerprint, files);
    }

    String baselineFingerprint() {
        return baselineFingerprint;
    }

    JsonNode inputFiles() {
        return inputFiles == null ? null : inputFiles.deepCopy();
    }
}
