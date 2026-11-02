package com.buy_01.media.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;

import com.buy_01.media.exception.ForbiddenException;
import com.buy_01.media.exception.UnauthorizedException;
import com.buy_01.media.exception.MediaStorageException;
import com.buy_01.media.exception.GlobalExceptionHandler;
import com.buy_01.media.model.dto.MediaRespDto;
import com.buy_01.media.model.entity.MediaMetaData;
import com.buy_01.media.service.MediaService;

@ExtendWith(MockitoExtension.class)
class MediaControllerTest {

    @Mock
    private MediaService mediaService;

    @InjectMocks
    private MediaController controller;

    @Test
    void uploadProviderFailureReturnsSafeBadGatewayThroughHttp() throws Exception {
        when(mediaService.uploadImage(any(), any()))
                .thenThrow(new MediaStorageException(new RuntimeException("api_secret=private")));
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        var file = new MockMultipartFile("file", "pic.png", "image/png", new byte[]{1});
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/media/images")
                        .file(file).header("X-User-Id", "owner").header("X-User-Roles", "SELLER"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadGateway())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.message")
                        .value("Image storage is unavailable. Please retry or contact support."))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("api_secret"))));
    }

    private final MediaMetaData metadata = MediaMetaData.builder()
            .id("m1")
            .originalFilename("pic.png")
            .contentType("image/png")
            .size(10L)
            .storagePath("https://cdn/pic.png")
            .uploaderId("owner")
            .uploadedAt(LocalDateTime.now())
            .build();

    @Test
    void uploadImage_asSeller_returnsOk() {
        MockMultipartFile file = new MockMultipartFile("file", "pic.png", "image/png", new byte[]{1});
        MediaRespDto dto = MediaRespDto.builder().id("m1").url("u").build();
        when(mediaService.uploadImage(any(), any())).thenReturn(dto);

        var response = controller.uploadImage(file, "owner", "SELLER", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void uploadImage_missingUser_throwsUnauthorized() {
        MockMultipartFile file = new MockMultipartFile("file", "pic.png", "image/png", new byte[]{1});

        assertThrows(UnauthorizedException.class, () -> controller.uploadImage(file, "  ", "SELLER", null));
    }

    @Test
    void uploadImage_anonymous_throwsUnauthorized() {
        MockMultipartFile file = new MockMultipartFile("file", "pic.png", "image/png", new byte[]{1});

        assertThrows(UnauthorizedException.class, () -> controller.uploadImage(file, "anonymous", "SELLER", null));
    }

    @Test
    void uploadImage_nonSeller_throwsForbidden() {
        MockMultipartFile file = new MockMultipartFile("file", "pic.png", "image/png", new byte[]{1});

        assertThrows(ForbiddenException.class, () -> controller.uploadImage(file, "buyer", "CLIENT", null));
    }

    @Test
    void uploadImage_rolesHeaderOnly_returnsOk() {
        MockMultipartFile file = new MockMultipartFile("file", "pic.png", "image/png", new byte[]{1});
        when(mediaService.uploadImage(any(), any())).thenReturn(MediaRespDto.builder().id("m1").build());

        var response = controller.uploadImage(file, "owner", null, "client,seller");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void getImage_jsonAccept_returnsPayload() {
        when(mediaService.getMetadata("m1")).thenReturn(metadata);

        var response = controller.getImage("m1", "application/json");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isInstanceOf(MediaRespDto.class);
    }

    @Test
    void getImage_defaultRedirectsToCdn() {
        when(mediaService.getMetadata("m1")).thenReturn(metadata);

        var response = controller.getImage("m1", "image/*");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(response.getHeaders().getLocation()).hasToString("https://cdn/pic.png");
    }

    @Test
    void getImage_nullAccept_redirects() {
        when(mediaService.getMetadata("m1")).thenReturn(metadata);

        assertThat(controller.getImage("m1", null).getStatusCode()).isEqualTo(HttpStatus.FOUND);
    }

    @Test
    void getImageMetadata_returnsDto() {
        when(mediaService.getMetadata("m1")).thenReturn(metadata);

        var response = controller.getImageMetadata("m1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getId()).isEqualTo("m1");
    }

    @Test
    void deleteImage_asSeller_returnsNoContent() {
        assertThat(controller.deleteImage("m1", "owner", "SELLER", null).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void deleteImage_missingUser_throwsUnauthorized() {
        assertThrows(UnauthorizedException.class, () -> controller.deleteImage("m1", null, "SELLER", null));
    }

    @Test
    void deleteImage_nonSeller_throwsForbidden() {
        assertThrows(ForbiddenException.class, () -> controller.deleteImage("m1", "buyer", "CLIENT", null));
    }
}
