package com.buy01.order_service.exception;

/**
 * 409: the order step refused the checkout for good (e.g. prices or stock changed), so retrying the
 * same request can never succeed. The cart must be reviewed again.
 */
public class OrderRejectedException extends ConflictException {
    public OrderRejectedException(String message) {
        super(message);
    }
}
