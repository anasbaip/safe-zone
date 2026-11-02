package com.buy01.order_service.models;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import com.buy01.order_service.dtos.response.OrderResponse;

@Document("checkout_receipts")
public record CheckoutReceipt(@Id String id, String userId, String key, com.buy01.order_service.dtos.req.CheckoutRequest request, OrderResponse order) {}
