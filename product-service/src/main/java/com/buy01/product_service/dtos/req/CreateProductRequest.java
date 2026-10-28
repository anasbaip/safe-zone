package com.buy01.product_service.dtos.req;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

public record CreateProductRequest (
    @NotBlank(message = "product name is required")
    String name,
    @NotBlank(message = "product description is required")
    String description,
    @NotNull(message = "product price is required")
    @Positive(message = "product price must be a positive value")
    double price,
    @PositiveOrZero(message = "product quantity must be zero or a positive value")
    int quantity,
    List<String> mediaIds,
    @Size(max = 80, message = "Category must be at most 80 characters")
    String category
) {}
