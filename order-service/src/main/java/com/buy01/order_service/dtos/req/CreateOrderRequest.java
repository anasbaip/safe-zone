package com.buy01.order_service.dtos.req;

import java.util.List;

import com.buy01.order_service.dtos.common.ShippingAddressDto;
import com.buy01.order_service.models.PaymentMethod;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Payment method is optional and defaults to cash on delivery. */
public record CreateOrderRequest(
        @NotEmpty(message = "order must contain at least one item")
        @Size(max = 50, message = "order can contain at most 50 items")
        List<@NotNull(message = "order item cannot be null") @Valid OrderItemRequest> items,

        @NotNull(message = "shipping address is required")
        @Valid
        ShippingAddressDto shippingAddress,

        PaymentMethod paymentMethod,

        @jakarta.validation.constraints.DecimalMin("0.00")
        java.math.BigDecimal expectedTotal
) {
    public CreateOrderRequest(List<OrderItemRequest> items, ShippingAddressDto shippingAddress, PaymentMethod paymentMethod) {
        this(items, shippingAddress, paymentMethod, null);
    }
}
