package com.buy01.order_service.service;

import java.util.List;

import com.buy01.order_service.dtos.req.CreateOrderRequest;
import com.buy01.order_service.dtos.req.OrderFilter;
import com.buy01.order_service.dtos.req.UpdateOrderStatusRequest;
import com.buy01.order_service.dtos.response.OrderResponse;
import com.buy01.order_service.dtos.response.ReorderResponse;
import com.buy01.order_service.security.AuthenticatedUser;

/**
 * Contract between the controller and the business layer. Implementations
 * enforce roles, ownership and status transitions, and signal failures with
 * the exceptions in com.buy01.order_service.exception.
 */
public interface OrderService {

    /** Same idempotency key from the same customer returns the existing order instead of a duplicate. */
    OrderResponse createOrder(CreateOrderRequest request, String idempotencyKey, AuthenticatedUser user);

    List<OrderResponse> getMyOrders(OrderFilter filter, AuthenticatedUser user);

    /** Orders containing at least one product sold by the user. */
    List<OrderResponse> getSellerOrders(OrderFilter filter, AuthenticatedUser user);

    /** Visible to the customer who placed it and to sellers with products in it. */
    OrderResponse getOrder(String orderId, AuthenticatedUser user);

    OrderResponse cancelOrder(String orderId, AuthenticatedUser user);

    ReorderResponse reorder(String orderId, AuthenticatedUser user);

    OrderResponse updateStatus(String orderId, UpdateOrderStatusRequest request, AuthenticatedUser user);
}
