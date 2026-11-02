package com.buy01.order_service.exception;

/** 502: a service the order depends on (e.g. product service) failed or is unreachable. */
public class UpstreamServiceException extends RuntimeException {
    public UpstreamServiceException(String message) {
        super(message);
    }
}
