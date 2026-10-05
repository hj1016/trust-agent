package com.trustagent.core.internalpolicy.query;

final class InternalPolicyQueryException extends RuntimeException {

    private final String code;

    InternalPolicyQueryException(String code, String message) {
        super(message);
        this.code = code;
    }

    String code() {
        return code;
    }
}
