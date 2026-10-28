package com.buy01.order_service.exception;

/** 401: no authenticated user on the request. */
public class UnauthorizedException extends RuntimeException {
    public UnauthorizedException(String message) {
        super(message);
    }
}
