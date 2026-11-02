package com.buy01.product_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import com.buy01.product_service.client.MediaServiceClient;
import com.buy01.product_service.dtos.external.MediaMetadataResponse;
import com.buy01.product_service.dtos.req.CreateProductRequest;
import com.buy01.product_service.dtos.req.LinkImagesRequest;
import com.buy01.product_service.dtos.req.UpdateProduct;
import com.buy01.product_service.exception.BadRequest;
import com.buy01.product_service.exception.UnauthorizedException;
import com.buy01.product_service.models.Product;
import com.buy01.product_service.repository.ProductRepository;
import com.buy01.product_service.repository.ProductSearchRepository;
import com.buy01.product_service.dtos.req.ProductSearchRequest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import com.buy01.product_service.security.AuthenticatedUser;

@ExtendWith(MockitoExtension.class)
class ProductServiceCoverageTest {

    @Mock
    private ProductRepository repository;

    @Mock
    private MediaServiceClient media;

    @Mock
    private KafkaTemplate<String, Object> kafka;

    @Mock
    private ProductSearchRepository searchRepository;

    @InjectMocks
    private ProductService service;

    private final AuthenticatedUser seller = new AuthenticatedUser("owner", List.of("SELLER"));

    private Product product;

    @BeforeEach
    void setUp() {
        product = Product.builder()
                .id("p1")
                .name("n")
                .description("d")
                .price(5.0)
                .quantity(2)
                .ownerId("owner")
                .mediaIds(new String[]{"m1"})
                .images(new String[]{"u1"})
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    @Test
    void searchMapsProductsAndPreservesPaginationMetadata() {
        var filters = new ProductSearchRequest(null, null, null, null, null, null, 1, 2);
        when(searchRepository.search(filters))
                .thenReturn(new PageImpl<>(List.of(product), PageRequest.of(1, 2), 3));

        var result = service.search(filters);

        assertThat(result.totalElements()).isEqualTo(3);
        assertThat(result.totalPages()).isEqualTo(2);
        assertThat(result.page()).isEqualTo(1);
        assertThat(result.size()).isEqualTo(2);
        assertThat(result.content()).hasSize(1);
        assertThat(result.content().get(0).id()).isEqualTo("p1");
        assertThat(result.content().get(0).category()).isEqualTo("uncategorized");
    }

    @Test
    void getThemAll_mapsProducts() {
        when(repository.findAll()).thenReturn(List.of(product));

        assertThat(service.getThemAll()).hasSize(1);
    }

    @Test
    void getOneProduct_returnsResponse() {
        when(repository.findById("p1")).thenReturn(Optional.of(product));

        var result = service.getOneProduct("p1");
        assertThat(result.id()).isEqualTo("p1");
        assertThat(result.category()).isEqualTo("uncategorized");
    }

    @Test
    void myProducts_returnsOwnerProducts() {
        when(repository.findByOwnerId("owner")).thenReturn(List.of(product));

        assertThat(service.myProducts(seller)).hasSize(1);
    }

    @Test
    void newProduct_withMedia_publishesAndSaves() {
        when(media.fetchMetadata("m1"))
                .thenReturn(new MediaMetadataResponse("m1", "f", "image/png", "url", 10, "owner"));
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));

        var result = service.newProduct(new CreateProductRequest("n", "d", 5.0, 2, List.of("m1"), " Electronics "), seller);

        assertThat(result.name()).isEqualTo("n");
        assertThat(result.category()).isEqualTo("electronics");
        var saved = org.mockito.ArgumentCaptor.forClass(Product.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getCategory()).isEqualTo("electronics");
        verify(kafka).send(eq("PRODUCT_CREATED"), any(), any());
    }

    @Test
    void newProduct_withoutMedia_throwsBadRequest() {
        var request = new CreateProductRequest("n", "d", 5.0, 2, List.of(), null);

        assertThrows(BadRequest.class, () -> service.newProduct(request, seller));
    }

    @Test
    void newProduct_nullMedia_throwsBadRequest() {
        var request = new CreateProductRequest("n", "d", 5.0, 2, null, null);

        assertThrows(BadRequest.class, () -> service.newProduct(request, seller));
    }

    @Test
    void linkImages_resolvesAndSignals() {
        when(repository.findById("p1")).thenReturn(Optional.of(product));
        when(media.fetchMetadata("m1"))
                .thenReturn(new MediaMetadataResponse("m1", "f", "image/png", "url", 10, "owner"));
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));
        when(kafka.send(eq("PRODUCT_IMAGES_CHANGED"), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));

        var result = service.linkImages("p1", new LinkImagesRequest(List.of("m1")), seller);

        assertThat(result.images()).containsExactly("url");
    }

    @Test
    void linkImages_mediaOwnedByOther_throwsUnauthorized() {
        when(repository.findById("p1")).thenReturn(Optional.of(product));
        when(media.fetchMetadata("m1"))
                .thenReturn(new MediaMetadataResponse("m1", "f", "image/png", "url", 10, "someone"));

        var request = new LinkImagesRequest(List.of("m1"));
        assertThrows(UnauthorizedException.class,
                () -> service.linkImages("p1", request, seller));
    }

    @Test
    void updateProduct_replacesData() {
        when(repository.findById("p1")).thenReturn(Optional.of(product));
        when(media.fetchMetadata("m1"))
                .thenReturn(new MediaMetadataResponse("m1", "f", "image/png", "url2", 10, "owner"));
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));
        when(kafka.send(eq("PRODUCT_IMAGES_CHANGED"), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));

        var result = service.updateProduct("p1", new UpdateProduct("n2", "d2", 9.0, 3, List.of("m1"), " Furniture "), seller);

        assertThat(result.name()).isEqualTo("n2");
        assertThat(product.getCategory()).isEqualTo("furniture");
        assertThat(result.category()).isEqualTo("furniture");
    }

    @Test
    void updateWithoutCategoryPreservesExistingCategory() {
        product.setCategory("furniture");
        when(repository.findById("p1")).thenReturn(Optional.of(product));
        when(media.fetchMetadata("m1"))
                .thenReturn(new MediaMetadataResponse("m1", "f", "image/png", "url", 10, "owner"));
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));
        when(kafka.send(eq("PRODUCT_IMAGES_CHANGED"), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));

        var result = service.updateProduct("p1",
                new UpdateProduct("n", "d", 5, 2, List.of("m1"), null), seller);

        assertThat(result.category()).isEqualTo("furniture");
        assertThat(product.getCategory()).isEqualTo("furniture");
    }

    @Test
    void blankCategoryFallsBackToUncategorized() {
        product.setCategory("   ");
        when(repository.findById("p1")).thenReturn(Optional.of(product));
        assertThat(service.getOneProduct("p1").category()).isEqualTo("uncategorized");
    }

    @Test
    void updateProduct_withoutMedia_throwsBadRequest() {
        var request = new UpdateProduct("n", "d", 5.0, 2, List.of(), null);

        assertThrows(BadRequest.class, () -> service.updateProduct("p1", request, seller));
    }

    @Test
    void updateProduct_nullMedia_throwsBadRequest() {
        var request = new UpdateProduct("n", "d", 5.0, 2, null, null);

        assertThrows(BadRequest.class, () -> service.updateProduct("p1", request, seller));
    }

    @Test
    void deleteP_removesAndSignals() {
        product.setMediaIds(null);
        when(repository.findById("p1")).thenReturn(Optional.of(product));
        when(kafka.send(eq("PRODUCT_IMAGES_CHANGED"), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));

        service.deleteP("p1", seller);

        verify(repository).delete(product);
    }
}