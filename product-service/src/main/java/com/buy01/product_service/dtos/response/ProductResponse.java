package com.buy01.product_service.dtos.response;

import java.util.Arrays;
import java.util.List;

public record ProductResponse(
        String id, String name, String description, double price, String ownerId,
        List<String> images, List<String> mediaIds, int quantity, String category) {

    public ProductResponse {
        images = images == null ? null : List.copyOf(images);
        mediaIds = mediaIds == null ? null : List.copyOf(mediaIds);
    }

    public ProductResponse(String id, String name, String description, double price,
            String ownerId, String[] images, String[] mediaIds, int quantity, String category) {
        this(id, name, description, price, ownerId,
                images == null ? null : Arrays.asList(images),
                mediaIds == null ? null : Arrays.asList(mediaIds), quantity, category);
    }
}
