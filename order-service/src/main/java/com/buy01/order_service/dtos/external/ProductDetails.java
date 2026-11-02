package com.buy01.order_service.dtos.external;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ProductDetails(
        String id,
        String name,
        double price,
        String ownerId,
        List<String> images,
        int quantity,
        String category) {

    public ProductDetails {
        images = images == null ? List.of() : List.copyOf(images);
    }

    /** Product Service reports products without a category as "uncategorized"; same rule if it's missing. */
    public String categoryOrDefault() {
        return category == null || category.isBlank() ? "uncategorized" : category;
    }

    public String firstImage() {
        return images.isEmpty() ? null : images.get(0);
    }
}
