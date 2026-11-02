package com.buy01.product_service.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;

import jakarta.servlet.http.HttpServletRequest;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final HttpServletRequest request = mock(HttpServletRequest.class);

    @Test
    void handleValidationErrors_returnsBadRequest() {
        when(request.getRequestURI()).thenReturn("/products");
        var binding = new BeanPropertyBindingResult(new Object(), "target");
        var ex = new MethodArgumentNotValidException(mock(MethodParameter.class), binding);

        var response = handler.handleValidationErrrors(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().error()).isEqualTo("Validation Failed");
    }

    @Test
    void handleBadRequest_returnsBadRequest() {
        when(request.getRequestURI()).thenReturn("/products");

        var response = handler.handleBadRequest(new BadRequest("bad"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message()).isEqualTo("bad");
    }

    @Test
    void handleResourceNotFound_returnsNotFound() {
        when(request.getRequestURI()).thenReturn("/products/1");

        var response = handler.handleResourceNotFound(new ResourceNotFoundException("missing"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().message()).isEqualTo("missing");
    }

    @Test
    void handleUnauthorized_returnsForbidden() {
        when(request.getRequestURI()).thenReturn("/products/1");

        var response = handler.handleUnauthorized(new UnauthorizedException("nope"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().message()).isEqualTo("nope");
    }

    @Test
    void handleGlobalException_returnsInternalServerError() {
        when(request.getRequestURI()).thenReturn("/products");

        var response = handler.handleGlobalException(new RuntimeException("boom"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().message()).contains("boom");
    }

    @Test
    void handleConflict_returnsConflict() {
        when(request.getRequestURI()).thenReturn("/internal/stock/reserve");

        var response = handler.handleConflict(new ConflictException("Not enough stock"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().message()).isEqualTo("Not enough stock");
    }
}
