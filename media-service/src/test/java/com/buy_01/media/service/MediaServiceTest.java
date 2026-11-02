package com.buy_01.media.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.mock.web.MockMultipartFile;

import com.buy_01.media.events.ImageUploadedEvent;
import com.buy_01.media.events.ProductImagesChangedEvent;
import com.buy_01.media.exception.ForbiddenException;
import com.buy_01.media.exception.MediaNotFoundException;
import com.buy_01.media.exception.MediaStorageException;
import com.buy_01.media.model.dto.MediaRespDto;
import com.buy_01.media.model.entity.MediaMetaData;
import com.buy_01.media.repository.MediaMetaDataRepository;

@ExtendWith(MockitoExtension.class)
class MediaServiceTest {

    @Mock
    private MediaMetaDataRepository repository;

    @Mock
    private FileValidationService fileValidationService;

    @Mock
    private CloudinaryStorageService cloudinaryStorageService;

    @Mock
    private KafkaTemplate<String, ImageUploadedEvent> kafkaTemplate;

    @InjectMocks
    private MediaService mediaService;

    private MediaMetaData metadata;

    @BeforeEach
    void setUp() {
        metadata = MediaMetaData.builder()
                .id("m1")
                .filename("public-1")
                .originalFilename("pic.png")
                .contentType("image/png")
                .size(10L)
                .storagePath("https://cdn/pic.png")
                .uploaderId("owner")
                .uploadedAt(LocalDateTime.now())
                .build();
    }

    @Test
    void uploadImage_savesAndPublishes() {
        MockMultipartFile file = new MockMultipartFile("file", "pic.png", "image/png", new byte[]{1, 2, 3});
        when(cloudinaryStorageService.uploadFile(file))
                .thenReturn(Map.of("secure_url", "https://cdn/pic.png", "public_id", "public-1"));
        when(repository.save(any(MediaMetaData.class))).thenReturn(metadata);

        MediaRespDto response = mediaService.uploadImage(file, "owner");

        assertThat(response.getId()).isEqualTo("m1");
        assertThat(response.getUrl()).isEqualTo("https://cdn/pic.png");
        verify(kafkaTemplate).send(eq("IMAGE_UPLOADED"), eq("m1"), any(ImageUploadedEvent.class));
    }

    @Test
    void uploadImage_storageFailurePropagatesWithoutSaving() {
        MockMultipartFile file = new MockMultipartFile("file", "pic.png", "image/png", new byte[]{1});
        when(cloudinaryStorageService.uploadFile(file)).thenThrow(new MediaStorageException(new java.io.IOException("boom")));

        assertThrows(MediaStorageException.class, () -> mediaService.uploadImage(file, "owner"));
        org.mockito.Mockito.verifyNoInteractions(repository, kafkaTemplate);
    }

    @Test
    void getMetadata_found() {
        when(repository.findById("m1")).thenReturn(Optional.of(metadata));

        assertThat(mediaService.getMetadata("m1").getId()).isEqualTo("m1");
    }

    @Test
    void getMetadata_notFound_throws() {
        when(repository.findById("x")).thenReturn(Optional.empty());

        assertThrows(MediaNotFoundException.class, () -> mediaService.getMetadata("x"));
    }

    @Test
    void deleteImage_owner_deletesFromCloudinaryAndDb() {
        when(repository.findById("m1")).thenReturn(Optional.of(metadata));

        mediaService.deleteImage("m1", "owner");

        verify(cloudinaryStorageService).deleteFile("public-1");
        verify(repository).deleteById("m1");
    }

    @Test
    void deleteImage_notOwner_throwsForbidden() {
        when(repository.findById("m1")).thenReturn(Optional.of(metadata));

        assertThrows(ForbiddenException.class, () -> mediaService.deleteImage("m1", "intruder"));
    }

    @Test
    void onProductImagesChanged_deletesRemovedOwnedImages() {
        when(repository.findById("old")).thenReturn(Optional.of(metadata));

        mediaService.onProductImagesChanged(
                new ProductImagesChangedEvent("p1", "owner", List.of("old", "kept"), List.of("kept")));

        verify(cloudinaryStorageService).deleteFile("public-1");
        verify(repository).deleteById("old");
    }

    @Test
    void onProductImagesChanged_forbiddenOwner_throws() {
        when(repository.findById("old")).thenReturn(Optional.of(metadata));

        var event = new ProductImagesChangedEvent("p1", "intruder", List.of("old"), List.of());
        assertThrows(ForbiddenException.class, () -> mediaService.onProductImagesChanged(event));
    }
}