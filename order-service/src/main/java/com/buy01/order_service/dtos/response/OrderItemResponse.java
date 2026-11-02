package com.buy01.order_service.dtos.response;

import java.math.BigDecimal;

/** Snapshot of the product taken when the order was placed. */
public record OrderItemResponse(
        String productId,
        String sellerId,
        String productName,
        String category,
        String imageUrl,
        BigDecimal unitPrice,
        int quantity,
        BigDecimal lineTotal
) {}
