package com.buy01.order_service.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.buy01.order_service.dtos.req.CreateOrderRequest;
import com.buy01.order_service.dtos.req.OrderFilter;
import com.buy01.order_service.dtos.req.UpdateOrderStatusRequest;
import com.buy01.order_service.dtos.response.OrderResponse;
import com.buy01.order_service.dtos.response.ReorderResponse;
import com.buy01.order_service.security.AuthenticatedUser;
import com.buy01.order_service.security.CurrentUser;
import com.buy01.order_service.service.OrderService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

/** Exposed by the gateway as /orders/** (the frontend calls /api/orders/**). */
@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
public class OrderController {

    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final OrderService orderService;

    @PostMapping
    public ResponseEntity<OrderResponse> createOrder(
            @RequestHeader(IDEMPOTENCY_KEY_HEADER)
            @NotBlank(message = "Idempotency-Key header must not be blank")
            @Size(max = 100, message = "Idempotency-Key header must be at most 100 characters")
            String idempotencyKey,
            @Valid @RequestBody CreateOrderRequest request,
            @CurrentUser AuthenticatedUser user) {
        OrderResponse response = orderService.createOrder(request, idempotencyKey, user);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/my")
    public ResponseEntity<List<OrderResponse>> getMyOrders(
            @Valid @ModelAttribute OrderFilter filter,
            @CurrentUser AuthenticatedUser user) {
        return ResponseEntity.ok(orderService.getMyOrders(filter, user));
    }

    @GetMapping("/seller")
    public ResponseEntity<List<OrderResponse>> getSellerOrders(
            @Valid @ModelAttribute OrderFilter filter,
            @CurrentUser AuthenticatedUser user) {
        return ResponseEntity.ok(orderService.getSellerOrders(filter, user));
    }

    @GetMapping("/{id}")
    public ResponseEntity<OrderResponse> getOrder(@PathVariable String id, @CurrentUser AuthenticatedUser user) {
        return ResponseEntity.ok(orderService.getOrder(id, user));
    }

    @PatchMapping("/{id}/cancel")
    public ResponseEntity<OrderResponse> cancelOrder(@PathVariable String id, @CurrentUser AuthenticatedUser user) {
        return ResponseEntity.ok(orderService.cancelOrder(id, user));
    }

    @PostMapping("/{id}/reorder")
    public ResponseEntity<ReorderResponse> reorder(@PathVariable String id, @CurrentUser AuthenticatedUser user) {
        return ResponseEntity.ok(orderService.reorder(id, user));
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<OrderResponse> updateStatus(
            @PathVariable String id,
            @Valid @RequestBody UpdateOrderStatusRequest request,
            @CurrentUser AuthenticatedUser user) {
        return ResponseEntity.ok(orderService.updateStatus(id, request, user));
    }
}
