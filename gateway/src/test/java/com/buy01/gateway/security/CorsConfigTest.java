package com.buy01.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;

class CorsConfigTest {

    private final CorsConfiguration config = new SecurityConfig(mock(JwtAuthenticationFilter.class))
            .corsConfigurationSource()
            .getCorsConfiguration(new MockHttpServletRequest("POST", "/orders"));

    @Test
    void allowsIdempotencyKeyForCheckout() {
        assertThat(config.checkHeaders(java.util.List.of("Idempotency-Key", "Authorization", "Content-Type")))
                .containsExactlyInAnyOrder("Idempotency-Key", "Authorization", "Content-Type");
    }

    @Test
    void doesNotAllowBrowsersToSendIdentityHeaders() {
        assertThat(config.checkHeaders(java.util.List.of("X-User-Id"))).isNull();
        assertThat(config.checkHeaders(java.util.List.of("X-User-Roles"))).isNull();
    }
}
