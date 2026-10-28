package com.buy01.order_service.exception;

/** 403: the user is authenticated but does not own the order or lacks the role. */
public class ForbiddenException extends RuntimeException {
    public ForbiddenException(String message) {
        super(message);
    }
}
