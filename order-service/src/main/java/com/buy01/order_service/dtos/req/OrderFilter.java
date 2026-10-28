package com.buy01.order_service.dtos.req;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;

import com.buy01.order_service.models.OrderStatus;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Size;

/**
 * Query parameters shared by the customer and seller order lists, e.g.
 * ?status=PENDING&from=2026-01-01&to=2026-01-31&q=headphones
 * All fields are optional; q matches the order id or a product name.
 */
public record OrderFilter(
        OrderStatus status,

        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        LocalDate from,

        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        LocalDate to,

        @Size(max = 100, message = "search text must be at most 100 characters")
        String q
) {

    public static OrderFilter none() {
        return new OrderFilter(null, null, null, null);
    }

    @AssertTrue(message = "from date must not be after to date")
    public boolean isDateRangeValid() {
        return from == null || to == null || !from.isAfter(to);
    }
}
