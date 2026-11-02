package com.buy_01.media.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import com.cloudinary.Cloudinary;
import com.cloudinary.Uploader;
import com.buy_01.media.exception.MediaStorageException;

class CloudinaryStorageServiceTest {
    private final Cloudinary cloudinary = mock(Cloudinary.class);
    private final Uploader uploader = mock(Uploader.class);
    private final CloudinaryStorageService storage = new CloudinaryStorageService(cloudinary);
    private final MockMultipartFile file = new MockMultipartFile("file", "image.png", "image/png", new byte[]{1});

    @BeforeEach
    void setup() { when(cloudinary.uploader()).thenReturn(uploader); }

    @Test
    void successfulUploadReturnsProviderMetadata() throws Exception {
        Map<String, Object> result = Map.of("secure_url", "https://example.test/image.png", "public_id", "image");
        when(uploader.upload(any(byte[].class), any(Map.class))).thenReturn(result);
        assertThat(storage.uploadFile(file)).isEqualTo(result);
    }

    @Test
    void uploadProviderAndNetworkFailuresHaveSafeMessages() throws Exception {
        when(uploader.upload(any(byte[].class), any(Map.class)))
                .thenThrow(new RuntimeException("Invalid cloud_name private-account"))
                .thenThrow(new IOException("secret-value"));
        assertThatThrownBy(() -> storage.uploadFile(file)).isInstanceOf(MediaStorageException.class)
                .hasMessage("Image storage is unavailable. Please retry or contact support.");
        assertThatThrownBy(() -> storage.uploadFile(file)).isInstanceOf(MediaStorageException.class)
                .hasMessageNotContaining("secret-value");
    }

    @Test
    void deleteProviderAndNetworkFailuresHaveSafeMessages() throws Exception {
        when(uploader.destroy(any(String.class), any(Map.class)))
                .thenThrow(new RuntimeException("private-provider-error"))
                .thenThrow(new IOException("private-network-error"));
        assertThatThrownBy(() -> storage.deleteFile("image")).isInstanceOf(MediaStorageException.class)
                .hasMessageNotContaining("private-provider-error");
        assertThatThrownBy(() -> storage.deleteFile("image")).isInstanceOf(MediaStorageException.class)
                .hasMessageNotContaining("private-network-error");
    }
}
