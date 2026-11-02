package com.buy01.order_service.exception;

/** 404: the order does not exist. */
public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String message) {
        super(message);
    }
}
