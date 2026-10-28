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
        int quantity) {

    public ProductDetails {
        images = images == null ? List.of() : List.copyOf(images);
    }

    public String firstImage() {
        return images.isEmpty() ? null : images.get(0);
    }
}
