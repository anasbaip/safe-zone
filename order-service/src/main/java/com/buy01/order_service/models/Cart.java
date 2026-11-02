package com.buy01.order_service.models;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;
import com.buy01.order_service.dtos.common.ShippingAddressDto;
import com.buy01.order_service.dtos.req.CreateOrderRequest;
import lombok.Data;

/** User ID is the Mongo primary key: exactly one cart per customer. */
@Data
@Document("carts")
public class Cart {
    @Id private String id;
    @Version private Long version;
    private Map<String, Integer> items = new LinkedHashMap<>();
    private com.buy01.order_service.dtos.response.CartResponse pendingReview;
    private String pendingKey;
    private CreateOrderRequest pendingRequest;
    private ShippingAddressDto pendingAddress;
    private String pendingToken;
    public Cart(String id) { this.id = id; }
}
