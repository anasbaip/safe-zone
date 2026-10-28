package com.buy01.order_service.config;

import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import com.buy01.order_service.service.OrderService;
import com.buy01.order_service.dtos.req.*;
import com.buy01.order_service.dtos.response.*;
import com.buy01.order_service.security.AuthenticatedUser;

/** Replace automatically when a real Orders implementation is registered. */
@Configuration
public class OrderAvailabilityConfig {
    @Bean
    @ConditionalOnMissingBean(OrderService.class)
    OrderService unavailableOrders() {
        return new UnavailableOrders();
    }

    private static class UnavailableOrders implements OrderService {
        private ResponseStatusException unavailable() {
            return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Orders Service implementation is not available yet");
        }

        @Override
        public OrderResponse createOrder(CreateOrderRequest request, String key, AuthenticatedUser user) {
            throw unavailable();
        }

        @Override
        public List<OrderResponse> getMyOrders(OrderFilter filter, AuthenticatedUser user) {
            throw unavailable();
        }

        @Override
        public List<OrderResponse> getSellerOrders(OrderFilter filter, AuthenticatedUser user) {
            throw unavailable();
        }

        @Override
        public OrderResponse getOrder(String id, AuthenticatedUser user) {
            throw unavailable();
        }

        @Override
        public OrderResponse cancelOrder(String id, AuthenticatedUser user) {
            throw unavailable();
        }

        @Override
        public ReorderResponse reorder(String id, AuthenticatedUser user) {
            throw unavailable();
        }

        @Override
        public OrderResponse updateStatus(String id, UpdateOrderStatusRequest request, AuthenticatedUser user) {
            throw unavailable();
        }
    }
}
