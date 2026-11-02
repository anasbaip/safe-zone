package com.buy_01.media.service;

import java.time.LocalDateTime;
import java.util.Map;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import com.buy_01.media.events.ProductImagesChangedEvent;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.buy_01.media.events.ImageUploadedEvent;
import com.buy_01.media.exception.MediaNotFoundException;
import com.buy_01.media.model.dto.MediaRespDto;
import com.buy_01.media.model.entity.MediaMetaData;
import com.buy_01.media.repository.MediaMetaDataRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class MediaService {

    private final MediaMetaDataRepository repository;
    private final FileValidationService filieValidationService;
    private final CloudinaryStorageService cloudinaryStorageService;

    private final KafkaTemplate<String, ImageUploadedEvent> kafkaTemplate;

    

    public MediaRespDto uploadImage(MultipartFile file, String uploaderId) {
        //  Run Tika MIME type and file size checks
        filieValidationService.validate(file);

        //  Upload to Cloudinary
        Map<String, Object> uploadResult = cloudinaryStorageService.uploadFile(file);

        String secureUrl = uploadResult.get("secure_url").toString();
        String publicId = uploadResult.get("public_id").toString();

        //  Save metadata to MongoDB
        MediaMetaData metadata = MediaMetaData.builder()
                .filename(publicId) // Stores Cloudinary public_id
                .originalFilename(file.getOriginalFilename())
                .contentType(file.getContentType())
                .size(file.getSize())
                .storagePath(secureUrl) // Stores Cloudinary HTTPS CDN URL
                .uploaderId(uploaderId)
                .uploadedAt(LocalDateTime.now(java.time.ZoneOffset.UTC))
                .build();

        MediaMetaData saved = repository.save(metadata);

        //  Publish Kafka Event
            kafkaTemplate.send(
                "IMAGE_UPLOADED",
                saved.getId(),
                new ImageUploadedEvent(
                    saved.getId(),
                    saved.getUploaderId(),
                    saved.getStoragePath(),
                    saved.getOriginalFilename(),
                    saved.getContentType(),
                    saved.getSize(),
                    saved.getUploadedAt()
                )
            );

        return MediaRespDto.builder()
                .id(saved.getId())
                .url(saved.getStoragePath())
                .filename(saved.getOriginalFilename())
                .contentType(saved.getContentType())
                .size(saved.getSize())
                .uploaderId(saved.getUploaderId())
                .build();

    }

    public MediaMetaData getMetadata(String id) {
        return repository.findById(id)
                .orElseThrow(() -> new MediaNotFoundException("Media not found with id: " + id));
    }

   

    @KafkaListener(topics = "PRODUCT_IMAGES_CHANGED", groupId = "media-product-image-cleanup")
    public void onProductImagesChanged(ProductImagesChangedEvent event) {
        event.previousMediaIds().stream()
                .distinct()
                .filter(id -> !event.mediaIds().contains(id))
                .forEach(id -> repository.findById(id).ifPresent(metadata -> {
                    // Replayed events may refer to images already deleted.
                    if (!metadata.getUploaderId().equals(event.ownerId())) {
                        throw new com.buy_01.media.exception.ForbiddenException(
                                "Forbidden: You do not own this image.");
                    }
                    cloudinaryStorageService.deleteFile(metadata.getFilename());
                    repository.deleteById(id);
                }));
    }

    public void deleteImage(String id, String requestingUser) {
        MediaMetaData metadata = getMetadata(id);

        // Security check: ensure requesting user owns the resource
        if (!metadata.getUploaderId().equals(requestingUser)) {
            throw new com.buy_01.media.exception.ForbiddenException("Forbidden: You do not own this image.");
        }

        // Delete asset from Cloudinary using stored public_id (saved in
        // metadata.getFilename())
        cloudinaryStorageService.deleteFile(metadata.getFilename());

        // Delete record from MongoDB
        repository.deleteById(id);
    }
}
