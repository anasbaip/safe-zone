package com.buy01.order_service.dtos.response;

import java.util.List;

import com.buy01.order_service.dtos.req.OrderItemRequest;

/**
 * Items of a previous order, ready to be sent again through checkout.
 * Prices are not copied: they are resolved again when the new order is created.
 */
public record ReorderResponse(String sourceOrderId, List<OrderItemRequest> items) {

    public ReorderResponse {
        items = items == null ? null : List.copyOf(items);
    }
}
