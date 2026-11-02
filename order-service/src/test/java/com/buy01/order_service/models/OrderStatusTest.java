package com.buy01.order_service.models;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class OrderStatusTest {

    @ParameterizedTest(name = "{0} -> {1} allowed={2}")
    @CsvSource({
            // forward flow
            "PENDING,   CONFIRMED, true",
            "CONFIRMED, SHIPPED,   true",
            "SHIPPED,   DELIVERED, true",
            // cancellation only before shipping
            "PENDING,   CANCELLED, true",
            "CONFIRMED, CANCELLED, true",
            "SHIPPED,   CANCELLED, false",
            "DELIVERED, CANCELLED, false",
            // no skipping steps
            "PENDING,   SHIPPED,   false",
            "PENDING,   DELIVERED, false",
            "CONFIRMED, DELIVERED, false",
            // no going back
            "CONFIRMED, PENDING,   false",
            "SHIPPED,   CONFIRMED, false",
            "DELIVERED, SHIPPED,   false",
            // no staying in the same status
            "PENDING,   PENDING,   false",
            "SHIPPED,   SHIPPED,   false",
            // final states
            "CANCELLED, PENDING,   false",
            "CANCELLED, CONFIRMED, false",
            "DELIVERED, PENDING,   false"
    })
    void canTransitionTo(OrderStatus from, OrderStatus to, boolean allowed) {
        assertThat(from.canTransitionTo(to)).isEqualTo(allowed);
    }

    @Test
    void finalStatesHaveNoNextStatus() {
        assertThat(OrderStatus.DELIVERED.allowedNext()).isEmpty();
        assertThat(OrderStatus.CANCELLED.allowedNext()).isEmpty();
    }

    @Test
    void nullIsNeverAValidNextStatus() {
        assertThat(OrderStatus.PENDING.canTransitionTo(null)).isFalse();
    }
}
