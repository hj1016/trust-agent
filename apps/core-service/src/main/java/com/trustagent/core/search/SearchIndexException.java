package com.trustagent.core.search;

/** 색인·ES 호출 실패. 메시지에 자격증명은 넣지 않는다. */
public class SearchIndexException extends RuntimeException {

    private final String code;
    private final int httpStatus;

    public SearchIndexException(String code, String message) {
        this(code, message, 0, null);
    }

    public SearchIndexException(String code, String message, int httpStatus, Throwable cause) {
        super(code + ": " + message, cause);
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
