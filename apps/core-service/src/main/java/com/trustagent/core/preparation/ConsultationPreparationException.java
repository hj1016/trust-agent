package com.trustagent.core.preparation;

/** 기록 경로의 거부·실패. code는 HTTP 상태와 실행 기록의 error_code에 쓰인다. */
public final class ConsultationPreparationException extends RuntimeException {

    private final String code;

    ConsultationPreparationException(String code, String message) {
        super(message);
        this.code = code;
    }

    ConsultationPreparationException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
