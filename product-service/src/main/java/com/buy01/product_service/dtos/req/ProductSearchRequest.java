package com.buy01.product_service.dtos.req;

import java.math.BigDecimal;
import java.util.Locale;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record ProductSearchRequest(
        @Size(max = 200) String q,
        @Size(max = 80) String category,
        @PositiveOrZero @Digits(integer = 12, fraction = 2) BigDecimal minPrice,
        @PositiveOrZero @Digits(integer = 12, fraction = 2) BigDecimal maxPrice,
        Boolean inStock,
        @Pattern(regexp = "newest|featured|price-asc|price-desc|name-asc") String sort,
        @Min(0) @Max(10000) Integer page,
        @Min(1) @Max(100) Integer size) {

    public ProductSearchRequest {
        q = clean(q);
        category = clean(category);
        if (category != null) {
            category = category.toLowerCase(Locale.ROOT);
        }
        sort = sort == null ? "newest" : sort;
        page = page == null ? 0 : page;
        size = size == null ? 12 : size;
    }

    @AssertTrue(message = "minPrice must not exceed maxPrice")
    public boolean isPriceRangeValid() {
        return minPrice == null || maxPrice == null || minPrice.compareTo(maxPrice) <= 0;
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
