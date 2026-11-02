package com.buy01.order_service.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.buy01.order_service.dtos.response.CartResponse;
import com.buy01.order_service.exception.GlobalExceptionHandler;
import com.buy01.order_service.security.*;
import com.buy01.order_service.service.CartService;

class CartControllerTest {
    CartService service = mock(CartService.class);
    MockMvc mvc;
    @BeforeEach void setup() {
        var filter = new UserContextFilter();
        mvc = MockMvcBuilders.standaloneSetup(new CartController(service))
            .setCustomArgumentResolvers(new CurrentUserArgumentResolver())
            .setControllerAdvice(new GlobalExceptionHandler()).addFilters(filter).build();
    }
    @Test void missingGatewayIdentityIsUnauthorized() throws Exception {
        mvc.perform(get("/cart")).andExpect(status().isUnauthorized()); verifyNoInteractions(service);
    }
    @Test void trustedGatewayIdentityReachesService() throws Exception {
        var user = new AuthenticatedUser("client", List.of("CLIENT"));
        when(service.get(user)).thenReturn(new CartResponse(List.of(), BigDecimal.ZERO, "token", false, null));
        mvc.perform(get("/cart").header("X-User-Id", "client").header("X-User-Roles", "CLIENT"))
            .andExpect(status().isOk()).andExpect(jsonPath("totalAmount").value(0));
        verify(service).get(user);
    }
    @Test void quantityAndAddressValidationUseExistingErrors() throws Exception {
        mvc.perform(post("/cart/items").header("X-User-Id", "client")
            .contentType(MediaType.APPLICATION_JSON).content("{\"productId\":\"p1\",\"quantity\":0}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("validationErrors.quantity").exists());
        mvc.perform(post("/cart/checkout").header("X-User-Id", "client")
            .header("Idempotency-Key", "key").contentType(MediaType.APPLICATION_JSON)
            .content("{\"reviewToken\":\"token\",\"shippingAddress\":{}}"))
            .andExpect(status().isBadRequest()); verifyNoInteractions(service);
    }
}
