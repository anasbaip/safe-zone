package com.buy01.gateway.security;

import java.io.IOException;
import java.util.List;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.buy01.gateway.filter.CustomHttpServletRequestWrapper;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    static final List<String> IDENTITY_HEADERS = List.of("X-User-Id", "X-User-Role", "X-User-Roles");

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        // Identity headers are only ever set by the gateway; drop any a client sent
        CustomHttpServletRequestWrapper wrappedRequest = new CustomHttpServletRequestWrapper(request);
        IDENTITY_HEADERS.forEach(wrappedRequest::removeHeader);

        String authHeader = request.getHeader("Authorization");

        // No JWT → continue, without identity
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(wrappedRequest, response);
            return;
        }

        String token = authHeader.substring(7);

        // Invalid JWT
        if (!jwtService.isValid(token)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }

        Claims claims = jwtService.extractClaims(token);

        String userId = claims.getSubject();
        String role = claims.get("role", String.class);

        // Convert role to Spring Security authority
        SimpleGrantedAuthority authority =
                new SimpleGrantedAuthority("ROLE_" + role);

        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                        userId,
                        null,
                        List.of(authority)
                );

        // Tell Spring Security that the user is authenticated
        SecurityContextHolder
                .getContext()
                .setAuthentication(authentication);

        wrappedRequest.putHeader("X-User-Id", userId);
        wrappedRequest.putHeader("X-User-Role", role);
        wrappedRequest.putHeader("X-User-Roles", role);

        filterChain.doFilter(wrappedRequest, response);
    }
}