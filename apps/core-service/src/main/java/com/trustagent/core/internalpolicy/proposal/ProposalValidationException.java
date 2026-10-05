package com.trustagent.core.internalpolicy.proposal;

public final class ProposalValidationException extends RuntimeException {
    private final String code;

    ProposalValidationException(String code, String message) {
        super(message);
        this.code = code;
    }

    ProposalValidationException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
