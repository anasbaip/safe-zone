package com.buy01.order_service.exception;

/** 400: the request is well formed but cannot be accepted (e.g. a product that no longer exists). */
public class BadRequestException extends RuntimeException {
    public BadRequestException(String message) {
        super(message);
    }
}
