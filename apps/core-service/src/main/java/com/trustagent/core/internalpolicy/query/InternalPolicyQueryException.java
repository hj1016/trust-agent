package com.trustagent.core.internalpolicy.query;

public final class InternalPolicyQueryException extends RuntimeException {

    private final String code;

    InternalPolicyQueryException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
