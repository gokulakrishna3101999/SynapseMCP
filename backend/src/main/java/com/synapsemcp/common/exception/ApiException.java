package com.synapsemcp.common.exception;

import org.springframework.http.HttpStatus;

/** Business error rendered as an RFC 7807 problem with a stable machine-readable {@code code}. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
