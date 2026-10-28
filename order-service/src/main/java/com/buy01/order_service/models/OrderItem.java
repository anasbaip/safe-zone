package com.buy01.order_service.models;

import java.math.BigDecimal;

import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

public record OrderItem(
        String productId,
        String sellerId,
        String productName,
        String imageUrl,
        @Field(targetType = FieldType.DECIMAL128) BigDecimal unitPrice,
        int quantity,
        @Field(targetType = FieldType.DECIMAL128) BigDecimal lineTotal) {
}
