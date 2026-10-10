package com.trustagent.core.grant;

/** grant 검사 실패. 코드와 HTTP 상태를 함께 가진다(401 GRANT_REQUIRED, 403 범위·만료·소진, 409 CONSUMED, 503 발급 저장 실패). */
public final class GrantException extends RuntimeException {

    private final String code;
    private final int httpStatus;

    public GrantException(String code, int httpStatus, String message) {
        super(message);
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
