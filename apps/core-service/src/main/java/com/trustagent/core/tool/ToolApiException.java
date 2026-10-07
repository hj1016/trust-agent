package com.trustagent.core.tool;

public final class ToolApiException extends RuntimeException {
    private final String code;

    ToolApiException(String code, String message) {
        super(message);
        this.code = code;
    }

    ToolApiException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
