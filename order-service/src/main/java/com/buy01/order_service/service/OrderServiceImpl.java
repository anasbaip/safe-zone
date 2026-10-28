package com.buy01.order_service.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import com.buy01.order_service.client.ProductClient;
import com.buy01.order_service.dtos.external.ProductDetails;
import com.buy01.order_service.dtos.req.CreateOrderRequest;
import com.buy01.order_service.dtos.req.OrderFilter;
import com.buy01.order_service.dtos.req.OrderItemRequest;
import com.buy01.order_service.dtos.req.UpdateOrderStatusRequest;
import com.buy01.order_service.dtos.response.OrderResponse;
import com.buy01.order_service.dtos.response.ReorderResponse;
import com.buy01.order_service.exception.BadRequestException;
import com.buy01.order_service.exception.ConflictException;
import com.buy01.order_service.exception.ForbiddenException;
import com.buy01.order_service.exception.ResourceNotFoundException;
import com.buy01.order_service.exception.UpstreamServiceException;
import com.buy01.order_service.models.Order;
import com.buy01.order_service.models.OrderItem;
import com.buy01.order_service.models.OrderStatus;
import com.buy01.order_service.models.PaymentMethod;
import com.buy01.order_service.models.StatusChange;
import com.buy01.order_service.repository.OrderRepository;
import com.buy01.order_service.repository.OrderSearchRepository;
import com.buy01.order_service.security.AuthenticatedUser;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    static final String CLIENT_ROLE = "CLIENT";
    static final String SELLER_ROLE = "SELLER";
    static final int MAX_QUANTITY_PER_PRODUCT = 100;

    private final OrderRepository orderRepository;
    private final OrderSearchRepository orderSearchRepository;
    private final ProductClient productClient;
    private final OrderMapper orderMapper;
    private final Clock clock;

    @Override
    public OrderResponse createOrder(CreateOrderRequest request, String idempotencyKey, AuthenticatedUser user) {
        requireRole(user, CLIENT_ROLE, "Only customers can place orders");

        var existing = orderRepository.findByCustomerIdAndIdempotencyKey(user.userId(), idempotencyKey);
        if (existing.isPresent()) {
            return orderMapper.toResponse(existing.get());
        }

        List<OrderItem> items = mergeQuantities(request.items()).entrySet().stream()
                .map(entry -> snapshot(entry.getKey(), entry.getValue()))
                .toList();
        Instant now = clock.instant();

        Order order = Order.builder()
                .customerId(user.userId())
                .idempotencyKey(idempotencyKey)
                .status(OrderStatus.PENDING)
                .items(items)
                .sellerIds(items.stream().map(OrderItem::sellerId).distinct().toList())
                .shippingAddress(orderMapper.toModel(request.shippingAddress()))
                .paymentMethod(request.paymentMethod() == null ? PaymentMethod.CASH_ON_DELIVERY
                        : request.paymentMethod())
                .totalItems(OrderPricing.totalItems(items))
                .totalAmount(OrderPricing.totalAmount(items))
                .statusHistory(List.of(new StatusChange(OrderStatus.PENDING, now, user.userId(), "Order placed")))
                .createdAt(now)
                .updatedAt(now)
                .build();

        try {
            return orderMapper.toResponse(orderRepository.insert(order));
        } catch (DuplicateKeyException ex) {
            // Two identical requests raced past the lookup above; the unique index kept only one.
            return orderRepository.findByCustomerIdAndIdempotencyKey(user.userId(), idempotencyKey)
                    .map(orderMapper::toResponse)
                    .orElseThrow(() -> ex);
        }
    }

    @Override
    public List<OrderResponse> getMyOrders(OrderFilter filter, AuthenticatedUser user) {
        return orderSearchRepository.findCustomerOrders(user.userId(), filter).stream()
                .map(orderMapper::toResponse)
                .toList();
    }

    @Override
    public List<OrderResponse> getSellerOrders(OrderFilter filter, AuthenticatedUser user) {
        requireRole(user, SELLER_ROLE, "Only sellers can view seller orders");
        return orderSearchRepository.findSellerOrders(user.userId(), filter).stream()
                .map(order -> orderMapper.toSellerResponse(order, user.userId()))
                .toList();
    }

    @Override
    public OrderResponse getOrder(String orderId, AuthenticatedUser user) {
        Order order = findOrder(orderId);
        if (order.isPlacedBy(user.userId())) {
            return orderMapper.toResponse(order);
        }
        if (isSellerOf(order, user)) {
            return orderMapper.toSellerResponse(order, user.userId());
        }
        throw new ForbiddenException("You do not have access to this order");
    }

    @Override
    public OrderResponse cancelOrder(String orderId, AuthenticatedUser user) {
        Order order = findOrder(orderId);
        if (!order.isPlacedBy(user.userId())) {
            throw new ForbiddenException("Only the customer who placed the order can cancel it");
        }
        changeStatus(order, OrderStatus.CANCELLED, user, "Cancelled by customer");
        return orderMapper.toResponse(orderRepository.save(order));
    }

    @Override
    public ReorderResponse reorder(String orderId, AuthenticatedUser user) {
        Order order = findOrder(orderId);
        if (!order.isPlacedBy(user.userId())) {
            throw new ForbiddenException("Only the customer who placed the order can reorder it");
        }
        return new ReorderResponse(order.getId(), orderMapper.toReorderItems(order));
    }

    @Override
    public OrderResponse updateStatus(String orderId, UpdateOrderStatusRequest request, AuthenticatedUser user) {
        Order order = findOrder(orderId);
        if (!isSellerOf(order, user)) {
            throw new ForbiddenException("Only a seller with products in this order can update its status");
        }
        changeStatus(order, request.status(), user, request.note());
        return orderMapper.toSellerResponse(orderRepository.save(order), user.userId());
    }

    private void changeStatus(Order order, OrderStatus next, AuthenticatedUser user, String note) {
        OrderStatus current = order.getStatus();
        if (!current.canTransitionTo(next)) {
            throw new ConflictException("Cannot change order status from " + current + " to " + next);
        }
        Instant now = clock.instant();
        List<StatusChange> history = new ArrayList<>(order.getStatusHistory());
        history.add(new StatusChange(next, now, user.userId(), note));
        order.setStatus(next);
        order.setStatusHistory(history);
        order.setUpdatedAt(now);
    }

    private Map<String, Integer> mergeQuantities(List<OrderItemRequest> items) {
        Map<String, Integer> quantities = new LinkedHashMap<>();
        for (OrderItemRequest item : items) {
            quantities.merge(item.productId(), item.quantity(), Integer::sum);
        }
        quantities.forEach((productId, quantity) -> {
            if (quantity > MAX_QUANTITY_PER_PRODUCT) {
                throw new BadRequestException("Quantity for product " + productId + " must be at most "
                        + MAX_QUANTITY_PER_PRODUCT);
            }
        });
        return quantities;
    }

    private OrderItem snapshot(String productId, int quantity) {
        ProductDetails product = productClient.getProduct(productId);
        if (product.ownerId() == null || product.name() == null) {
            throw new UpstreamServiceException("Product Service returned incomplete data for product " + productId);
        }
        if (product.quantity() < quantity) {
            throw new BadRequestException("Not enough stock for " + product.name() + ": "
                    + product.quantity() + " available");
        }
        BigDecimal unitPrice;
        try {
            unitPrice = OrderPricing.toPrice(product.price());
        } catch (IllegalArgumentException ex) {
            throw new UpstreamServiceException("Product Service returned an invalid price for product " + productId);
        }
        return new OrderItem(productId, product.ownerId(), product.name(), product.firstImage(), unitPrice,
                quantity, OrderPricing.lineTotal(unitPrice, quantity));
    }

    private Order findOrder(String orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderId));
    }

    private static boolean isSellerOf(Order order, AuthenticatedUser user) {
        return user.hasRole(SELLER_ROLE) && order.containsProductsOf(user.userId());
    }

    private static void requireRole(AuthenticatedUser user, String role, String message) {
        if (!user.hasRole(role)) {
            throw new ForbiddenException(message);
        }
    }
}
