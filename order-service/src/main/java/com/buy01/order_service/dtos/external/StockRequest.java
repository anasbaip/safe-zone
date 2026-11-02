package com.buy01.order_service.dtos.external;

import java.util.List;

import com.buy01.order_service.models.OrderItem;

/** Body of Product Service's /internal/stock/reserve and /internal/stock/release. */
public record StockRequest(List<Item> items) {

    public StockRequest {
        items = List.copyOf(items);
    }

    public static StockRequest of(List<OrderItem> orderItems) {
        return new StockRequest(orderItems.stream()
                .map(item -> new Item(item.productId(), item.quantity()))
                .toList());
    }

    public record Item(String productId, int quantity) {
    }
}
