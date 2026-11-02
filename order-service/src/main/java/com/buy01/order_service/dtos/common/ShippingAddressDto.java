package com.buy01.order_service.dtos.common;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ShippingAddressDto(
        @NotBlank(message = "full name is required")
        @Size(max = 100, message = "full name must be at most 100 characters")
        String fullName,

        @NotBlank(message = "phone is required")
        @Size(max = 30, message = "phone must be at most 30 characters")
        String phone,

        @NotBlank(message = "street is required")
        @Size(max = 200, message = "street must be at most 200 characters")
        String street,

        @NotBlank(message = "city is required")
        @Size(max = 100, message = "city must be at most 100 characters")
        String city,

        @NotBlank(message = "postal code is required")
        @Size(max = 20, message = "postal code must be at most 20 characters")
        String postalCode,

        @NotBlank(message = "country is required")
        @Size(max = 100, message = "country must be at most 100 characters")
        String country
) {}
