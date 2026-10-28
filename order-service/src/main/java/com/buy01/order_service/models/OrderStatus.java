package com.buy01.order_service.models;

import java.util.EnumSet;
import java.util.Set;

public enum OrderStatus {
    PENDING,
    CONFIRMED,
    SHIPPED,
    DELIVERED,
    CANCELLED;

    public Set<OrderStatus> allowedNext() {
        return switch (this) {
            case PENDING -> EnumSet.of(CONFIRMED, CANCELLED);
            case CONFIRMED -> EnumSet.of(SHIPPED, CANCELLED);
            case SHIPPED -> EnumSet.of(DELIVERED);
            case DELIVERED, CANCELLED -> EnumSet.noneOf(OrderStatus.class);
        };
    }

    public boolean canTransitionTo(OrderStatus next) {
        return next != null && allowedNext().contains(next);
    }
}
