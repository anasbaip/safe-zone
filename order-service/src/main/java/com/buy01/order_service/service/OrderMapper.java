package com.buy01.order_service.service;

import java.util.List;

import org.springframework.stereotype.Component;

import com.buy01.order_service.dtos.common.ShippingAddressDto;
import com.buy01.order_service.dtos.req.OrderItemRequest;
import com.buy01.order_service.dtos.response.OrderItemResponse;
import com.buy01.order_service.dtos.response.OrderResponse;
import com.buy01.order_service.dtos.response.StatusChangeResponse;
import com.buy01.order_service.models.Order;
import com.buy01.order_service.models.OrderItem;
import com.buy01.order_service.models.ShippingAddress;

@Component
public class OrderMapper {

    /** Full order, as the customer who placed it sees it. */
    public OrderResponse toResponse(Order order) {
        return toResponse(order, order.getItems());
    }

    /**
     * A seller only sees their own items and the totals for those items,
     * not what the customer bought from other sellers in the same order.
     */
    public OrderResponse toSellerResponse(Order order, String sellerId) {
        List<OrderItem> ownItems = order.getItems().stream()
                .filter(item -> sellerId.equals(item.sellerId()))
                .toList();
        return toResponse(order, ownItems);
    }

    public ShippingAddress toModel(ShippingAddressDto dto) {
        return new ShippingAddress(dto.fullName(), dto.phone(), dto.street(), dto.city(), dto.postalCode(),
                dto.country());
    }

    public List<OrderItemRequest> toReorderItems(Order order) {
        return order.getItems().stream()
                .map(item -> new OrderItemRequest(item.productId(), item.quantity()))
                .toList();
    }

    private OrderResponse toResponse(Order order, List<OrderItem> items) {
        return new OrderResponse(
                order.getId(),
                order.getCustomerId(),
                order.getStatus(),
                items.stream().map(this::toItemResponse).toList(),
                toDto(order.getShippingAddress()),
                order.getPaymentMethod(),
                OrderPricing.totalItems(items),
                OrderPricing.totalAmount(items),
                order.getStatusHistory().stream()
                        .map(change -> new StatusChangeResponse(change.status(), change.changedAt(),
                                change.changedBy(), change.note()))
                        .toList(),
                order.getCreatedAt(),
                order.getUpdatedAt());
    }

    private OrderItemResponse toItemResponse(OrderItem item) {
        return new OrderItemResponse(item.productId(), item.sellerId(), item.productName(), item.category(), item.imageUrl(),
                item.unitPrice(), item.quantity(), item.lineTotal());
    }

    private ShippingAddressDto toDto(ShippingAddress address) {
        if (address == null) {
            return null;
        }
        return new ShippingAddressDto(address.fullName(), address.phone(), address.street(), address.city(),
                address.postalCode(), address.country());
    }
}
