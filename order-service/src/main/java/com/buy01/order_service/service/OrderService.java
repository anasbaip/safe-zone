package com.buy01.order_service.service;

import com.buy01.order_service.dtos.req.CreateOrderRequest;
import com.buy01.order_service.dtos.req.OrderFilter;
import com.buy01.order_service.dtos.req.PageParams;
import com.buy01.order_service.dtos.req.UpdateOrderStatusRequest;
import com.buy01.order_service.dtos.response.OrderResponse;
import com.buy01.order_service.dtos.response.PageResponse;
import com.buy01.order_service.dtos.response.ReorderResponse;
import com.buy01.order_service.security.AuthenticatedUser;

/**
 * Contract between the controller and the business layer. Implementations
 * enforce roles, ownership and status transitions, and signal failures with
 * the exceptions in com.buy01.order_service.exception.
 */
public interface OrderService {

    /**
     * Same idempotency key from the same customer returns the existing order instead of a duplicate.
     * The ordered units are reserved in Product Service before the order is saved.
     */
    OrderResponse createOrder(CreateOrderRequest request, String idempotencyKey, AuthenticatedUser user);

    /** Newest first. */
    PageResponse<OrderResponse> getMyOrders(OrderFilter filter, PageParams paging, AuthenticatedUser user);

    /** Orders containing at least one product sold by the user, newest first. */
    PageResponse<OrderResponse> getSellerOrders(OrderFilter filter, PageParams paging, AuthenticatedUser user);

    /** Visible to the customer who placed it and to sellers with products in it. */
    OrderResponse getOrder(String orderId, AuthenticatedUser user);

    /** Puts the ordered units back in stock. */
    OrderResponse cancelOrder(String orderId, AuthenticatedUser user);

    ReorderResponse reorder(String orderId, AuthenticatedUser user);

    OrderResponse updateStatus(String orderId, UpdateOrderStatusRequest request, AuthenticatedUser user);
}
