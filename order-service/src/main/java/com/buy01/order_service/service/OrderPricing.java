package com.buy01.order_service.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import com.buy01.order_service.models.OrderItem;

public final class OrderPricing {

    public static final int SCALE = 2;

    private OrderPricing() {
    }

    public static BigDecimal toPrice(double price) {
        if (Double.isNaN(price) || Double.isInfinite(price) || price < 0) {
            throw new IllegalArgumentException("Invalid product price: " + price);
        }
        return BigDecimal.valueOf(price).setScale(SCALE, RoundingMode.HALF_UP);
    }

    public static BigDecimal lineTotal(BigDecimal unitPrice, int quantity) {
        return unitPrice.multiply(BigDecimal.valueOf(quantity)).setScale(SCALE, RoundingMode.HALF_UP);
    }

    public static BigDecimal totalAmount(List<OrderItem> items) {
        return items.stream()
                .map(OrderItem::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(SCALE, RoundingMode.HALF_UP);
    }

    public static int totalItems(List<OrderItem> items) {
        return items.stream().mapToInt(OrderItem::quantity).sum();
    }
}
