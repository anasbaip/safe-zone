package com.buy01.product_service.service;

import com.buy01.product_service.client.MediaServiceClient;
import com.buy01.product_service.dtos.external.MediaMetadataResponse;
import com.buy01.product_service.dtos.req.UpdateProduct;
import com.buy01.product_service.dtos.req.LinkImagesRequest;
import com.buy01.product_service.events.ProductImagesChangedEvent;
import com.buy01.product_service.models.Product;
import com.buy01.product_service.repository.ProductRepository;
import com.buy01.product_service.repository.ProductSearchRepository;
import com.buy01.product_service.security.AuthenticatedUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import static org.mockito.Mockito.*;

class ProductImageEventsTest {
    private final ProductRepository repository = mock(ProductRepository.class);
    private final MediaServiceClient media = mock(MediaServiceClient.class);
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, Object> kafka = mock(KafkaTemplate.class);
    private final ProductService service = new ProductService(repository, media, kafka, mock(ProductSearchRepository.class));
    private final AuthenticatedUser owner = new AuthenticatedUser("owner", List.of("SELLER"));

    @BeforeEach
    void setup() {
        when(repository.findById("product")).thenReturn(Optional.of(Product.builder()
                .id("product").ownerId("owner").mediaIds(new String[]{"old", "kept"}).build()));
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));
        when(kafka.send(eq("PRODUCT_IMAGES_CHANGED"), eq("product"), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(media.fetchMetadata("kept")).thenReturn(new MediaMetadataResponse(
                "kept", "file", "image/png", "url", 10, "owner"));
    }

    @Test
    void deletionPublishesEmptyCurrentList() {
        service.deleteP("product", owner);
        verify(kafka).send("PRODUCT_IMAGES_CHANGED", "product",
                new ProductImagesChangedEvent("product", "owner", List.of("old", "kept"), List.of()));
    }

    @Test
    void updatePublishesPreviousAndCurrentLists() {
        service.updateProduct("product", new UpdateProduct("name", "description", 10, 1,
                List.of("kept"), null), owner);
        verifyChange();
    }

    @Test
    void linkImagesAlsoPublishesCleanup() {
        service.linkImages("product", new LinkImagesRequest(List.of("kept")), owner);
        verifyChange();
    }

    private void verifyChange() {
        verify(kafka).send("PRODUCT_IMAGES_CHANGED", "product",
                new ProductImagesChangedEvent("product", "owner", List.of("old", "kept"), List.of("kept")));
    }
}
