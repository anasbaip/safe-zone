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
import com.buy01.order_service.dtos.req.PageParams;
import com.buy01.order_service.dtos.req.UpdateOrderStatusRequest;
import com.buy01.order_service.dtos.response.OrderResponse;
import com.buy01.order_service.dtos.response.PageResponse;
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
import lombok.extern.slf4j.Slf4j;

@Slf4j
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
        BigDecimal totalAmount = OrderPricing.totalAmount(items);
        if (request.expectedTotal() != null && request.expectedTotal().compareTo(totalAmount) != 0) {
            // Checked before reserving stock: the customer confirmed a different amount.
            throw new ConflictException("Prices changed since you reviewed your cart: the total is now "
                    + totalAmount + ". Review your cart again.");
        }
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
                .totalAmount(totalAmount)
                .statusHistory(List.of(new StatusChange(OrderStatus.PENDING, now, user.userId(), "Order placed")))
                .createdAt(now)
                .updatedAt(now)
                .build();

        productClient.reserveStock(items);
        try {
            return orderMapper.toResponse(orderRepository.insert(order));
        } catch (DuplicateKeyException ex) {
            // Two identical requests raced past the lookup above; the unique index kept only one,
            // and the other request already reserved the stock for it.
            releaseStock(items, "duplicate request " + idempotencyKey);
            return orderRepository.findByCustomerIdAndIdempotencyKey(user.userId(), idempotencyKey)
                    .map(orderMapper::toResponse)
                    .orElseThrow(() -> ex);
        } catch (RuntimeException ex) {
            releaseStock(items, "order that could not be saved");
            throw ex;
        }
    }

    @Override
    public PageResponse<OrderResponse> getMyOrders(OrderFilter filter, PageParams paging, AuthenticatedUser user) {
        return PageResponse.of(
                orderSearchRepository.findCustomerOrders(user.userId(), filter, paging.toPageable()),
                orderMapper::toResponse);
    }

    @Override
    public PageResponse<OrderResponse> getSellerOrders(OrderFilter filter, PageParams paging,
            AuthenticatedUser user) {
        requireRole(user, SELLER_ROLE, "Only sellers can view seller orders");
        return PageResponse.of(
                orderSearchRepository.findSellerOrders(user.userId(), filter, paging.toPageable()),
                order -> orderMapper.toSellerResponse(order, user.userId()));
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
        Order cancelled = orderRepository.save(order);
        // Only after the save: if it fails (e.g. a concurrent update), the order still holds the stock
        releaseStock(cancelled.getItems(), "order " + cancelled.getId() + " cancelled by customer");
        return orderMapper.toResponse(cancelled);
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
        Order updated = orderRepository.save(order);
        if (updated.getStatus() == OrderStatus.CANCELLED) {
            releaseStock(updated.getItems(), "order " + updated.getId() + " cancelled by seller");
        }
        return orderMapper.toSellerResponse(updated, user.userId());
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
        return new OrderItem(productId, product.ownerId(), product.name(), product.categoryOrDefault(),
                product.firstImage(), unitPrice,
                quantity, OrderPricing.lineTotal(unitPrice, quantity));
    }

    /**
     * The order's outcome is already decided at this point, so a failure here must not fail the request.
     * It is logged with everything needed to correct the stock by hand.
     */
    private void releaseStock(List<OrderItem> items, String reason) {
        try {
            productClient.releaseStock(items);
        } catch (RuntimeException ex) {
            log.error("Could not put stock back for {}: {} ({})", reason,
                    items.stream().map(item -> item.productId() + " x" + item.quantity()).toList(),
                    ex.getMessage());
        }
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
