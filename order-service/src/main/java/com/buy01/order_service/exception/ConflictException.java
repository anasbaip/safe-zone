package com.buy01.order_service.exception;

/** 409: the order is not in a state that allows the action (e.g. cancelling a shipped order). */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
