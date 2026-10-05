package com.trustagent.core.internalpolicy.proposal;

public final class FixtureApprovedChecklistException extends RuntimeException {
    private final String code;

    FixtureApprovedChecklistException(String code, String message) {
        super(message);
        this.code = code;
    }

    FixtureApprovedChecklistException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
