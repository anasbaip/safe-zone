package com.buy01.order_service.dtos.req;

import com.buy01.order_service.models.OrderStatus;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateOrderStatusRequest(
        @NotNull(message = "status is required")
        OrderStatus status,

        @Size(max = 255, message = "note must be at most 255 characters")
        String note
) {}
