package com.buy01.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.nio.charset.StandardCharsets;
import java.util.Date;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import javax.crypto.SecretKey;

class JwtAuthenticationFilterTest {

    private static final String SECRET = "my-ultra-secret-test-key-which-is-at-least-256-bits-long-1234567890";
    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        filter = new JwtAuthenticationFilter(new JwtService(SECRET));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private String tokenFor(String userId, String role) {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .subject(userId)
                .claim("role", role)
                .expiration(new Date(System.currentTimeMillis() + 3600000))
                .signWith(key)
                .compact();
    }

    private jakarta.servlet.http.HttpServletRequest forwardedRequest(FilterChain chain,
            MockHttpServletResponse response) throws Exception {
        org.mockito.ArgumentCaptor<jakarta.servlet.ServletRequest> captor =
                org.mockito.ArgumentCaptor.forClass(jakarta.servlet.ServletRequest.class);
        verify(chain).doFilter(captor.capture(), org.mockito.ArgumentMatchers.eq(response));
        return (jakarta.servlet.http.HttpServletRequest) captor.getValue();
    }

    @Test
    void noAuthHeader_continuesChain() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Accept", "application/json");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(forwardedRequest(chain, response).getHeader("Accept")).isEqualTo("application/json");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void nonBearerHeader_continuesChain() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Basic abc");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(forwardedRequest(chain, response).getHeader("Authorization")).isEqualTo("Basic abc");
    }

    @Test
    void noAuthHeader_dropsClientSuppliedIdentityHeaders() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-Id", "victim");
        request.addHeader("x-user-role", "SELLER");
        request.addHeader("X-USER-ROLES", "SELLER");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        jakarta.servlet.http.HttpServletRequest forwarded = forwardedRequest(chain, response);
        assertThat(forwarded.getHeader("X-User-Id")).isNull();
        assertThat(forwarded.getHeader("X-User-Role")).isNull();
        assertThat(forwarded.getHeader("X-User-Roles")).isNull();
        assertThat(java.util.Collections.list(forwarded.getHeaderNames()))
                .noneMatch(name -> name.toLowerCase().startsWith("x-user-"));
    }

    @Test
    void validToken_replacesClientSuppliedIdentityHeaders() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + tokenFor("user-9", "CLIENT"));
        request.addHeader("x-user-id", "victim");
        request.addHeader("X-User-Roles", "SELLER");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        jakarta.servlet.http.HttpServletRequest forwarded = forwardedRequest(chain, response);
        assertThat(java.util.Collections.list(forwarded.getHeaders("X-User-Id"))).containsExactly("user-9");
        assertThat(java.util.Collections.list(forwarded.getHeaders("X-User-Roles"))).containsExactly("CLIENT");
        assertThat(java.util.Collections.list(forwarded.getHeaderNames()))
                .filteredOn(name -> name.equalsIgnoreCase("X-User-Id")).hasSize(1);
    }

    @Test
    void invalidToken_returnsUnauthorized() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer not.a.jwt");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        verifyNoInteractions(chain);
    }

    @Test
    void validToken_setsContextAndForwardsHeaders() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + tokenFor("user-9", "SELLER"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication().getName()).isEqualTo("user-9");
        org.mockito.ArgumentCaptor<jakarta.servlet.ServletRequest> captor =
                org.mockito.ArgumentCaptor.forClass(jakarta.servlet.ServletRequest.class);
        verify(chain).doFilter(captor.capture(), org.mockito.ArgumentMatchers.eq(response));
        jakarta.servlet.http.HttpServletRequest forwarded =
                (jakarta.servlet.http.HttpServletRequest) captor.getValue();
        assertThat(forwarded.getHeader("X-User-Id")).isEqualTo("user-9");
        assertThat(forwarded.getHeader("X-User-Role")).isEqualTo("SELLER");
        assertThat(forwarded.getHeader("X-User-Roles")).isEqualTo("SELLER");
    }
}