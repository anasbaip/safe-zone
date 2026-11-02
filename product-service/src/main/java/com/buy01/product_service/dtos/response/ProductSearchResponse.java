package com.buy01.product_service.dtos.response;

import java.util.List;

public record ProductSearchResponse(
        List<ProductResponse> content, long totalElements, int totalPages, int page, int size) {

    public ProductSearchResponse {
        content = List.copyOf(content);
    }
}
