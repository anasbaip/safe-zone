package com.buy01.order_service.security;

import java.util.List;

public record AuthenticatedUser(String userId, List<String> roles) {

    public AuthenticatedUser {
        roles = roles == null ? List.of() : List.copyOf(roles);
    }

    public boolean hasRole(String role) {
        return roles.stream().anyMatch(r -> r.equalsIgnoreCase(role));
    }
}
