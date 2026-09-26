package com.trustagent.core.publicproduct.query;

public class PublicProductQueryException extends RuntimeException {

    private final String code;

    public PublicProductQueryException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
