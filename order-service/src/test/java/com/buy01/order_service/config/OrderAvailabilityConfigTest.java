package com.buy01.order_service.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class OrderAvailabilityConfigTest {
    @Test void missingOrdersImplementationNeverFabricatesSuccess() {
        var orders = new OrderAvailabilityConfig().unavailableOrders();
        assertThatThrownBy(() -> orders.createOrder(null, "key", null))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                ex -> org.assertj.core.api.Assertions.assertThat(ex.getStatusCode().value()).isEqualTo(503));
    }
}
