package com.buy01.order_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.buy01.order_service.models.OrderItem;

class OrderPricingTest {

    @Test
    void toPrice_roundsToTwoDecimalsHalfUp() {
        assertThat(OrderPricing.toPrice(19.99)).isEqualTo(new BigDecimal("19.99"));
        assertThat(OrderPricing.toPrice(10)).isEqualTo(new BigDecimal("10.00"));
        assertThat(OrderPricing.toPrice(1.005)).isEqualTo(new BigDecimal("1.01"));
        assertThat(OrderPricing.toPrice(2.344)).isEqualTo(new BigDecimal("2.34"));
        assertThat(OrderPricing.toPrice(0)).isEqualTo(new BigDecimal("0.00"));
    }

    @Test
    void toPrice_avoidsBinaryFloatingPointErrors() {
        // 0.1 + 0.2 = 0.30000000000000004 as double
        assertThat(OrderPricing.toPrice(0.1 + 0.2)).isEqualTo(new BigDecimal("0.30"));
    }

    @ParameterizedTest
    @ValueSource(doubles = {-0.01, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
    void toPrice_rejectsInvalidPrices(double price) {
        assertThatThrownBy(() -> OrderPricing.toPrice(price)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void lineTotal_multipliesUnitPriceByQuantity() {
        assertThat(OrderPricing.lineTotal(new BigDecimal("19.99"), 3)).isEqualTo(new BigDecimal("59.97"));
        assertThat(OrderPricing.lineTotal(new BigDecimal("0.10"), 100)).isEqualTo(new BigDecimal("10.00"));
    }

    @Test
    void totals_sumAllLines() {
        List<OrderItem> items = List.of(
                item(new BigDecimal("19.99"), 3),
                item(new BigDecimal("0.10"), 1),
                item(new BigDecimal("5.00"), 2));

        assertThat(OrderPricing.totalAmount(items)).isEqualTo(new BigDecimal("70.07"));
        assertThat(OrderPricing.totalItems(items)).isEqualTo(6);
    }

    @Test
    void totals_ofNoItemsAreZero() {
        assertThat(OrderPricing.totalAmount(List.of())).isEqualTo(new BigDecimal("0.00"));
        assertThat(OrderPricing.totalItems(List.of())).isZero();
    }

    private static OrderItem item(BigDecimal unitPrice, int quantity) {
        return new OrderItem("p", "s", "name", null, unitPrice, quantity, OrderPricing.lineTotal(unitPrice, quantity));
    }
}
