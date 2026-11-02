package com.buy01.order_service.dtos.response;
import java.math.BigDecimal;
import java.util.List;
public record CartResponse(List<Item> items, BigDecimal totalAmount, String reviewToken, boolean checkoutPending, Pending pending) {
    public record Pending(String key, com.buy01.order_service.dtos.common.ShippingAddressDto shippingAddress, String reviewToken) {}
    public record Item(String productId, String name, String image, int quantity,
                       int availableQuantity, BigDecimal unitPrice, BigDecimal lineTotal, boolean available) {}
}
