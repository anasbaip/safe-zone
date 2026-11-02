package com.buy01.order_service.models;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
@Builder
@Document(collection = "orders")
@CompoundIndex(name = "customer_idempotency_key", def = "{'customerId': 1, 'idempotencyKey': 1}", unique = true)
@CompoundIndex(name = "customer_created", def = "{'customerId': 1, 'createdAt': -1}")
@CompoundIndex(name = "seller_created", def = "{'sellerIds': 1, 'createdAt': -1}")
public class Order {

    @Id
    private String id;

    @Version
    private Long version;

    private String customerId;

    private String idempotencyKey;

    private OrderStatus status;

    private List<OrderItem> items;

    private List<String> sellerIds;

    private ShippingAddress shippingAddress;

    private PaymentMethod paymentMethod;

    private int totalItems;

    @Field(targetType = FieldType.DECIMAL128)
    private BigDecimal totalAmount;

    private List<StatusChange> statusHistory;

    private Instant createdAt;

    private Instant updatedAt;

    public boolean isPlacedBy(String userId) {
        return customerId != null && customerId.equals(userId);
    }

    public boolean containsProductsOf(String sellerId) {
        return sellerIds != null && sellerIds.contains(sellerId);
    }
}
