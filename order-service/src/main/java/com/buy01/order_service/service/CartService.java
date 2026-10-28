package com.buy01.order_service.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import org.springframework.stereotype.Service;
import com.buy01.order_service.client.ProductClient;
import com.buy01.order_service.client.OrdersClient;
import com.buy01.order_service.dtos.req.*;
import com.buy01.order_service.dtos.response.*;
import com.buy01.order_service.exception.*;
import com.buy01.order_service.models.*;
import com.buy01.order_service.repository.*;
import com.buy01.order_service.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class CartService {
    private final CartRepository carts;
    private final CheckoutReceiptRepository receipts;
    private final ProductClient products;
    private final OrdersClient orders;

    private Cart load(AuthenticatedUser user) {
        if (user == null)
            throw new UnauthorizedException("Authentication required");
        if (!user.hasRole("CLIENT"))
            throw new ForbiddenException("Only clients can use a shopping cart");
        return carts.findById(user.userId()).orElseGet(() -> new Cart(user.userId()));
    }

    public CartResponse get(AuthenticatedUser user) {
        return view(load(user));
    }

    public CartResponse setItem(String productId, int quantity, boolean add, AuthenticatedUser user) {
        Cart cart = load(user);
        editable(cart);
        if (productId == null || productId.isBlank() || productId.length() > 200)
            throw new BadRequestException("Invalid product ID");
        if (quantity < 1 || quantity > 100)
            throw new BadRequestException("Quantity must be between 1 and 100");
        int next = quantity + (add ? cart.getItems().getOrDefault(productId, 0) : 0);
        if (!add && !cart.getItems().containsKey(productId))
            throw new ResourceNotFoundException("Cart item not found");
        if (next > 100)
            throw new BadRequestException("Quantity must be between 1 and 100");
        if (!cart.getItems().containsKey(productId) && cart.getItems().size() >= 50)
            throw new BadRequestException("Cart can contain at most 50 products");
        ProductClient.Product p = products.get(productId);
        if (p == null || p.quantity() < next)
            throw new ConflictException("Product unavailable or insufficient stock");
        cart.getItems().put(productId, next);
        return view(carts.save(cart));
    }

    public CartResponse remove(String productId, AuthenticatedUser user) {
        Cart cart = load(user);
        editable(cart);
        cart.getItems().remove(productId);
        return view(carts.save(cart));
    }

    public CartResponse clear(AuthenticatedUser user) {
        Cart cart = load(user);
        editable(cart);
        cart.getItems().clear();
        return view(carts.save(cart));
    }

    private void editable(Cart cart) {
        if (cart.getPendingKey() != null)
            throw new ConflictException("Checkout is pending. Retry confirmation before editing the cart");
    }

    private CartResponse view(Cart cart) {
        if (cart.getPendingKey() != null && cart.getPendingReview() != null) {
            var reviewed = cart.getPendingReview();
            return new CartResponse(reviewed.items(), reviewed.totalAmount(), cart.getPendingToken(), true,
                    new CartResponse.Pending(cart.getPendingKey(), cart.getPendingAddress(), cart.getPendingToken()));
        }
        var items = new ArrayList<CartResponse.Item>();
        BigDecimal total = BigDecimal.ZERO;
        for (var entry : cart.getItems().entrySet()) {
            var p = products.get(entry.getKey());
            BigDecimal price = p == null ? BigDecimal.ZERO : p.price().setScale(2, RoundingMode.HALF_UP);
            BigDecimal line = price.multiply(BigDecimal.valueOf(entry.getValue()));
            boolean available = p != null && p.quantity() >= entry.getValue();
            items.add(new CartResponse.Item(entry.getKey(), p == null ? "Unavailable product" : p.name(),
                    p == null || p.images() == null || p.images().isEmpty() ? null : p.images().get(0),
                    entry.getValue(), p == null ? 0 : p.quantity(), price, line, available));
            total = total.add(line);
        }
        // Stable for stock changes that still satisfy the requested quantities.
        String token = digest(cart.getId() + ":" + cart.getVersion() + ":" + items.stream()
                .map(i -> i.productId() + ":" + i.quantity() + ":" + i.unitPrice() + ":" + i.available()).toList());
        return new CartResponse(items, total, token, cart.getPendingKey() != null, cart.getPendingKey() == null ? null
                : new CartResponse.Pending(cart.getPendingKey(), cart.getPendingAddress(), cart.getPendingToken()));
    }

    public CartResponse review(AuthenticatedUser user) {
        CartResponse response = get(user);
        requireAvailable(response);
        return response;
    }

    private void requireAvailable(CartResponse response) {
        if (response.items().isEmpty())
            throw new BadRequestException("Cart is empty");
        if (response.items().stream().anyMatch(i -> !i.available()))
            throw new ConflictException("Some products are unavailable. Update your cart");
    }

    public OrderResponse checkout(CheckoutRequest request, String key, AuthenticatedUser user) {
        Cart cart = load(user);
        if (key == null || key.isBlank() || key.length() > 100)
            throw new BadRequestException("Invalid Idempotency-Key");
        String receiptId = digest(user.userId() + ":" + key);
        var receipt = receipts.findById(receiptId);
        if (receipt.isPresent()) {
            if (!request.equals(receipt.get().request()))
                throw new ConflictException("Checkout key already used with a different request");
            finish(cart, key);
            return receipt.get().order();
        }
        if (cart.getPendingKey() != null) {
            if (!key.equals(cart.getPendingKey()) || !request.shippingAddress().equals(cart.getPendingAddress())
                    || !request.reviewToken().equals(cart.getPendingToken())) {
                throw new ConflictException("Retry the pending checkout using its original key and address");
            }
        } else {
            var current = view(cart);
            requireAvailable(current);
            if (!current.reviewToken().equals(request.reviewToken()))
                throw new ConflictException("Cart or prices changed. Review again before confirming");
            cart.setPendingReview(current);
            cart.setPendingKey(key);
            cart.setPendingAddress(request.shippingAddress());
            cart.setPendingToken(request.reviewToken());
            cart.setPendingRequest(new CreateOrderRequest(cart.getItems().entrySet().stream()
                    .map(e -> new OrderItemRequest(e.getKey(), e.getValue())).toList(), request.shippingAddress(),
                    PaymentMethod.CASH_ON_DELIVERY, current.totalAmount()));
            cart = carts.save(cart); // Optimistic version claims this snapshot across service instances.
        }
        OrderResponse order = orders.create(cart.getPendingRequest(), key, user);
        // Persist acknowledgement before clearing. Retrying after a crash can finish
        // clearing safely.
        receipts.save(new CheckoutReceipt(receiptId, user.userId(), key, request, order));
        finish(cart, key);
        return order;
    }

    private void finish(Cart cart, String key) {
        if (key.equals(cart.getPendingKey())) {
            cart.getItems().clear();
            cart.setPendingKey(null);
            cart.setPendingRequest(null);
            cart.setPendingReview(null);
            cart.setPendingAddress(null);
            cart.setPendingToken(null);
            carts.save(cart);
        }
    }

    private String digest(String text) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
