package com.trustagent.core.internalpolicy.proposal;

public final class ProposalGenerationException extends RuntimeException {
    private final String code;

    ProposalGenerationException(String code, String message) {
        super(message);
        this.code = code;
    }

    ProposalGenerationException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
