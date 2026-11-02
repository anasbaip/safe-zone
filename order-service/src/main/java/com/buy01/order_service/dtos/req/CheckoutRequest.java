package com.buy01.order_service.dtos.req;
import com.buy01.order_service.dtos.common.ShippingAddressDto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
public record CheckoutRequest(@NotNull @Valid ShippingAddressDto shippingAddress,
                              @NotBlank @Size(max=64) String reviewToken) {}
