package com.buy01.order_service.dtos.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.buy01.order_service.dtos.common.ShippingAddressDto;
import com.buy01.order_service.models.OrderStatus;
import com.buy01.order_service.models.PaymentMethod;

public record OrderResponse(
        String id,
        String customerId,
        OrderStatus status,
        List<OrderItemResponse> items,
        ShippingAddressDto shippingAddress,
        PaymentMethod paymentMethod,
        int totalItems,
        BigDecimal totalAmount,
        List<StatusChangeResponse> statusHistory,
        Instant createdAt,
        Instant updatedAt) {

    public OrderResponse {
        items = items == null ? null : List.copyOf(items);
        statusHistory = statusHistory == null ? null : List.copyOf(statusHistory);
    }
}
