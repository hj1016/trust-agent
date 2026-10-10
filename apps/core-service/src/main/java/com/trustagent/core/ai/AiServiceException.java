package com.trustagent.core.ai;

public final class AiServiceException extends RuntimeException {

    private final String code;
    private final int httpStatus;

    public AiServiceException(String code, int httpStatus, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public String code() {
        return code;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
