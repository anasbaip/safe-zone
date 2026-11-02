package com.buy01.order_service.dtos.response;

import java.time.Instant;

import com.buy01.order_service.models.OrderStatus;

/** One entry of the order status timeline. */
public record StatusChangeResponse(
        OrderStatus status,
        Instant changedAt,
        String changedBy,
        String note
) {}
