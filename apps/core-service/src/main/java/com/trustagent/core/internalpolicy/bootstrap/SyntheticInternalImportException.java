package com.trustagent.core.internalpolicy.bootstrap;

public final class SyntheticInternalImportException extends RuntimeException {
    private final String code;

    SyntheticInternalImportException(String code, String message) {
        super(message);
        this.code = code;
    }

    SyntheticInternalImportException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
