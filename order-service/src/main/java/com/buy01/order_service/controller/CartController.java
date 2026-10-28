package com.buy01.order_service.controller;

import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import com.buy01.order_service.dtos.req.*;
import com.buy01.order_service.dtos.response.*;
import com.buy01.order_service.security.*;
import com.buy01.order_service.service.CartService;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/cart")
@RequiredArgsConstructor
public class CartController {
    private final CartService carts;

    @GetMapping
    public CartResponse get(@CurrentUser AuthenticatedUser user) {
        return carts.get(user);
    }

    @PostMapping("/items")
    public CartResponse add(@Valid @RequestBody OrderItemRequest item, @CurrentUser AuthenticatedUser user) {
        return carts.setItem(item.productId(), item.quantity(), true, user);
    }

    @PutMapping("/items/{id}")
    public CartResponse update(@PathVariable String id, @Valid @RequestBody Quantity quantity,
            @CurrentUser AuthenticatedUser user) {
        return carts.setItem(id, quantity.quantity(), false, user);
    }

    public record Quantity(
            @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(100) int quantity) {
    }

    @DeleteMapping("/items/{id}")
    public CartResponse remove(@PathVariable String id, @CurrentUser AuthenticatedUser user) {
        return carts.remove(id, user);
    }

    @DeleteMapping
    public CartResponse clear(@CurrentUser AuthenticatedUser user) {
        return carts.clear(user);
    }

    @PostMapping("/review")
    public CartResponse review(@CurrentUser AuthenticatedUser user) {
        return carts.review(user);
    }

    @PostMapping("/checkout")
    public OrderResponse checkout(@Valid @RequestBody CheckoutRequest request,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 100) String key,
            @CurrentUser AuthenticatedUser user) {
        return carts.checkout(request, key, user);
    }
}
