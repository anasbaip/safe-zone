package com.buy01.order_service.models;

import java.time.Instant;

public record StatusChange(OrderStatus status, Instant changedAt, String changedBy, String note) {
}
