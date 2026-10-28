package com.buy01.order_service.dtos.response;

import java.time.LocalDateTime;
import java.util.Map;

public record ErrorResponse(
        LocalDateTime timestamp,
        int status,
        String error,
        String message,
        String path,
        Map<String, String> validationErrors) {

    public ErrorResponse {
        validationErrors = validationErrors == null ? null : Map.copyOf(validationErrors);
    }

    public ErrorResponse(LocalDateTime timestamp, int status, String error, String message, String path) {
        this(timestamp, status, error, message, path, null);
    }
}
