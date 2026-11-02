package com.buy01.order_service.dtos.req;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** Optional ?page=0&size=20 query parameters of the order lists. Pages start at 0. */
public record PageParams(
        @Min(value = 0, message = "page must be 0 or greater")
        @Max(value = 10_000, message = "page must be at most 10000")
        Integer page,

        @Min(value = 1, message = "size must be at least 1")
        @Max(value = PageParams.MAX_SIZE, message = "size must be at most 100")
        Integer size
) {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    public static PageParams first() {
        return new PageParams(null, null);
    }

    public Pageable toPageable() {
        return PageRequest.of(page == null ? 0 : page, size == null ? DEFAULT_SIZE : size);
    }
}
