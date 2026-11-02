package com.buy01.order_service.models;

public record ShippingAddress(
        String fullName,
        String phone,
        String street,
        String city,
        String postalCode,
        String country) {
}
