package com.buy_01.media.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void storageFailureReturnsBadGatewayWithoutProviderDetails() {
        var response = handler.handleStorage(new MediaStorageException(new RuntimeException("api_secret=private")));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody()).containsEntry("message", "Image storage is unavailable. Please retry or contact support.");
        assertThat(response.getBody().toString()).doesNotContain("api_secret");
    }

    @Test
    void handleInvalidFile_returnsBadRequest() {
        var response = handler.handleInvalidFile(new InvalidFileException("bad"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("message", "bad");
    }

    @Test
    void handleMaxUploadSize_returnsBadRequest() {
        var response = handler.handleMaxUploadSize(mock(MaxUploadSizeExceededException.class));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("error", "Bad Request");
    }

    @Test
    void handleNotFound_returnsNotFound() {
        assertThat(handler.handleNotFound(new MediaNotFoundException("missing")).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void handleUnauthorized_returnsUnauthorized() {
        assertThat(handler.handleUnauthorized(new UnauthorizedException("no")).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void handleForbidden_returnsForbidden() {
        assertThat(handler.handleForbidden(new ForbiddenException("no")).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void handleIllegalArgument_returnsBadRequest() {
        assertThat(handler.handleIllegalArgument(new IllegalArgumentException("bad")).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
