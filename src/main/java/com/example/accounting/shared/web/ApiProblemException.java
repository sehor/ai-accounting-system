package com.example.accounting.shared.web;

/** 可安全返回给 API 调用方的业务错误。 */
public class ApiProblemException extends RuntimeException {

    private final int status;
    private final String code;
    private final String title;
    private final boolean retryable;

    public ApiProblemException(int status, String code, String title, String detail, boolean retryable) {
        super(detail);
        this.status = status;
        this.code = code;
        this.title = title;
        this.retryable = retryable;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public String title() {
        return title;
    }

    public boolean retryable() {
        return retryable;
    }
}
