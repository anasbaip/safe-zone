package com.buy01.order_service.dtos.req;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * Only the product id and quantity come from the client. Name, price and
 * seller are resolved server side so a client cannot choose its own price.
 */
public record OrderItemRequest(
        @NotBlank(message = "product id is required")
        String productId,

        @Min(value = 1, message = "quantity must be at least 1")
        @Max(value = 100, message = "quantity must be at most 100")
        int quantity
) {}
