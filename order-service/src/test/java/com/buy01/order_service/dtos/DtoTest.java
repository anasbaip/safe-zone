package com.buy01.order_service.dtos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.buy01.order_service.dtos.req.OrderFilter;
import com.buy01.order_service.dtos.req.OrderItemRequest;
import com.buy01.order_service.dtos.response.ErrorResponse;
import com.buy01.order_service.dtos.response.OrderResponse;
import com.buy01.order_service.dtos.response.ReorderResponse;
import com.buy01.order_service.dtos.response.StatusChangeResponse;

class DtoTest {

    @Test
    void orderFilter_dateRangeValidation() {
        LocalDate jan1 = LocalDate.of(2026, 1, 1);
        LocalDate jan31 = LocalDate.of(2026, 1, 31);

        assertThat(OrderFilter.none().isDateRangeValid()).isTrue();
        assertThat(new OrderFilter(null, jan1, null, null).isDateRangeValid()).isTrue();
        assertThat(new OrderFilter(null, null, jan31, null).isDateRangeValid()).isTrue();
        assertThat(new OrderFilter(null, jan1, jan1, null).isDateRangeValid()).isTrue();
        assertThat(new OrderFilter(null, jan1, jan31, null).isDateRangeValid()).isTrue();
        assertThat(new OrderFilter(null, jan31, jan1, null).isDateRangeValid()).isFalse();
    }

    @Test
    void orderResponse_copiesListsDefensively() {
        List<StatusChangeResponse> history = new ArrayList<>();
        var response = new OrderResponse("o1", "c1", null, new ArrayList<>(), null, null, 0, null, history,
                null, null);
        history.add(new StatusChangeResponse(null, null, null, null));

        assertThat(response.statusHistory()).isEmpty();
        var items = response.items();
        assertThatThrownBy(() -> items.add(null)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void orderResponse_keepsNullLists() {
        var response = new OrderResponse("o1", "c1", null, null, null, null, 0, null, null, null, null);

        assertThat(response.items()).isNull();
        assertThat(response.statusHistory()).isNull();
    }

    @Test
    void reorderResponse_copiesItems() {
        List<OrderItemRequest> items = new ArrayList<>(List.of(new OrderItemRequest("p1", 1)));
        var response = new ReorderResponse("o1", items);
        items.clear();

        assertThat(response.items()).hasSize(1);
        assertThat(new ReorderResponse("o1", null).items()).isNull();
    }

    @Test
    void errorResponse_shortConstructorHasNoValidationErrors() {
        var response = new ErrorResponse(LocalDateTime.now(), 404, "Not Found", "missing", "/orders/x");

        assertThat(response.validationErrors()).isNull();
    }

    @Test
    void errorResponse_copiesValidationErrors() {
        Map<String, String> errors = new HashMap<>(Map.of("status", "status is required"));
        var response = new ErrorResponse(LocalDateTime.now(), 400, "Validation Failed", "invalid", "/orders",
                errors);
        errors.clear();

        assertThat(response.validationErrors()).containsEntry("status", "status is required");
    }
}
