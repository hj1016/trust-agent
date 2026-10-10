package com.trustagent.core.consultation;

public final class ConsultationException extends RuntimeException {

    private final String code;

    public ConsultationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
