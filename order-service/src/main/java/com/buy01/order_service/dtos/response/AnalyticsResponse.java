package com.buy01.order_service.dtos.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Delivered orders placed in the selected UTC date range. No customer PII is returned. */
public record AnalyticsResponse(
        LocalDate from, LocalDate to, String countedStatus,
        BigDecimal totalAmount, long orderCount, long totalUnits,
        List<ProductSummary> products, List<CategorySummary> categories, List<MonthlySummary> months) {

    public record ProductSummary(String productId, String name, long units, BigDecimal amount) {}
    public record CategorySummary(String category, long units, BigDecimal amount) {}
    public record MonthlySummary(String month, long orderCount, long units, BigDecimal amount) {}
}
