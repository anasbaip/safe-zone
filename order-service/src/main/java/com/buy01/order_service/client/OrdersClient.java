package com.buy01.order_service.client;

import java.time.Duration;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import com.buy01.order_service.models.PaymentMethod;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import com.buy01.order_service.dtos.req.CreateOrderRequest;
import com.buy01.order_service.dtos.response.OrderResponse;
import com.buy01.order_service.security.AuthenticatedUser;
import com.buy01.order_service.exception.UpstreamServiceException;

@Component
public class OrdersClient {
    private final RestClient client;
    public OrdersClient(@Value("${orders-service.base-url}") String url) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(10));
        client = RestClient.builder().requestFactory(factory).baseUrl(url).build();
    }
    public OrderResponse create(CreateOrderRequest request, String key, AuthenticatedUser user) {
        try {
            var response = client.post().header("Idempotency-Key", key)
                .header("X-User-Id", user.userId()).header("X-User-Roles", String.join(",", user.roles()))
                .body(request).retrieve().toEntity(OrderResponse.class);
            OrderResponse order = response.getBody();
            if (response.getStatusCode() != HttpStatus.CREATED || !matchesRequest(order, request, user)) {
                throw new UpstreamServiceException("Orders Service did not confirm the requested Pay on Delivery order; cart retained");
            }
            return order;
        } catch (RestClientException ex) {
            throw new UpstreamServiceException("Order creation is not confirmed. Your cart is retained; retry with the same checkout key");
        }
    }
    /** An arbitrary successful body is not sufficient evidence to clear a customer's cart. */
    private boolean matchesRequest(OrderResponse order, CreateOrderRequest request, AuthenticatedUser user) {
        if (order == null || order.id() == null || order.id().isBlank()
                || !user.userId().equals(order.customerId()) || order.status() == null
                || order.paymentMethod() != PaymentMethod.CASH_ON_DELIVERY
                || !request.shippingAddress().equals(order.shippingAddress())
                || order.totalAmount() == null || order.totalAmount().signum() < 0
                || order.items() == null || order.items().isEmpty()) return false;
        if (request.expectedTotal() != null && request.expectedTotal().compareTo(order.totalAmount()) != 0) return false;
        Map<String, Integer> expected = new HashMap<>();
        request.items().forEach(item -> expected.merge(item.productId(), item.quantity(), Integer::sum));
        Map<String, Integer> actual = new HashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        int quantity = 0;
        for (var item : order.items()) {
            if (item == null || item.quantity() < 1 || item.unitPrice() == null || item.unitPrice().signum() < 0
                    || item.lineTotal() == null
                    || item.unitPrice().multiply(BigDecimal.valueOf(item.quantity())).compareTo(item.lineTotal()) != 0) return false;
            actual.merge(item.productId(), item.quantity(), Integer::sum);
            quantity += item.quantity();
            total = total.add(item.lineTotal());
        }
        return expected.equals(actual) && quantity == order.totalItems() && total.compareTo(order.totalAmount()) == 0;
    }
}
