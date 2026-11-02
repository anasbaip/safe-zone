package com.buy01.order_service.security;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Reads the identity headers the gateway adds after validating the JWT. */
@Component
public class UserContextFilter extends OncePerRequestFilter {

    public static final String USER_ATTRIBUTE = "authenticatedUser";

    private static final String USER_ID_HEADER = "X-User-Id";
    private static final String USER_ROLES_HEADER = "X-User-Roles";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String userId = request.getHeader(USER_ID_HEADER);

        if (userId != null && !userId.isBlank()) {
            String rolesHeader = request.getHeader(USER_ROLES_HEADER);
            List<String> roles = (rolesHeader == null || rolesHeader.isBlank())
                    ? List.of()
                    : Arrays.stream(rolesHeader.split(","))
                            .map(String::trim)
                            .filter(role -> !role.isEmpty())
                            .toList();

            request.setAttribute(USER_ATTRIBUTE, new AuthenticatedUser(userId, roles));
        }

        filterChain.doFilter(request, response);
    }
}
