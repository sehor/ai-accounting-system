package com.example.accounting.reporting.internal.persistence;

public class BalanceProjectionException extends RuntimeException {

    private final String code;

    public BalanceProjectionException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
