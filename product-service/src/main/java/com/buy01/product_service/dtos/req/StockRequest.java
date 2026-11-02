package com.buy01.product_service.dtos.req;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/** Units to take from or give back to stock, sent by Order Service. */
public record StockRequest(
        @NotEmpty(message = "At least one item is required")
        List<@Valid @NotNull Item> items
) {

    public StockRequest {
        items = items == null ? null : List.copyOf(items);
    }

    public record Item(
            @NotBlank(message = "productId is required")
            String productId,

            @Min(value = 1, message = "quantity must be at least 1")
            int quantity
    ) {}
}
