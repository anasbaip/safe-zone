package com.buy01.product_service.service;

import java.time.Instant;
import java.util.List;
import java.util.Arrays;
import java.util.Locale;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import com.buy01.product_service.client.MediaServiceClient;
import com.buy01.product_service.dtos.external.MediaMetadataResponse;
import com.buy01.product_service.dtos.req.CreateProductRequest;
import com.buy01.product_service.dtos.req.LinkImagesRequest;
import com.buy01.product_service.dtos.req.ProductSearchRequest;
import com.buy01.product_service.dtos.req.UpdateProduct;
import com.buy01.product_service.dtos.response.ProductResponse;
import com.buy01.product_service.dtos.response.ProductSearchResponse;
import com.buy01.product_service.events.ProductCreatedEvent;
import com.buy01.product_service.events.ProductImagesChangedEvent;
import com.buy01.product_service.exception.BadRequest;
import com.buy01.product_service.exception.ResourceNotFoundException;
import com.buy01.product_service.exception.UnauthorizedException;
import com.buy01.product_service.models.Product;
import com.buy01.product_service.repository.ProductRepository;
import com.buy01.product_service.repository.ProductSearchRepository;
import com.buy01.product_service.security.AuthenticatedUser;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
 
public class ProductService {
 
 private static final String SELLER_ROLE = "SELLER";
 private final ProductRepository productRepository;
 private final MediaServiceClient mediaServiceClient;
 private final KafkaTemplate<String, Object> kafkaTemplate;
 private final ProductSearchRepository productSearchRepository;

 public ProductSearchResponse search(ProductSearchRequest filters) {
    var result = productSearchRepository.search(filters);
    return new ProductSearchResponse(result.getContent().stream().map(this::toResponse).toList(),
            result.getTotalElements(), result.getTotalPages(), result.getNumber(), result.getSize());
 }



 public  List<ProductResponse> getThemAll(){
    return productRepository.findAll().stream().map(this::toResponse).toList();
 } 
 


 public Product findAproduuct(String id){
    return productRepository.findById(id).orElseThrow( ( ) -> 
    new ResourceNotFoundException("sorrry man no product found with this id: " + id));
 }

public ProductResponse getOneProduct(String is){
    return toResponse(findAproduuct(is));
}

public Product findMyProduct(String id , AuthenticatedUser user){
    Product p = findAproduuct(id);
    if(!p.getOwnerId().equals(user.userId())){
        throw new UnauthorizedException("u are not the owner of this product");
    }
    return p;
}

public List<ProductResponse> myProducts(AuthenticatedUser user){
    return productRepository.findByOwnerId(user.userId()).stream().map(this::toResponse).toList();
}


public void isSeller(AuthenticatedUser user){
    if (!user.hasRole(SELLER_ROLE)) {
        throw new UnauthorizedException("u need to be a seller to add a new product");
    }
}


//  i dn't know if this the right approach to it or not 
// firstt we gonnna create the product without media , therefore we gonna wait for a basic product response
// then will wiat for l media to be created triger the media service via REST then update the product images field . 
//  fach adi nabghiw n requesteewhom me l frontend , we gonna send the prodct with is image URLs , so we gonna fetch them directly frome cloudinary;

// yla bantkum shi haja hassan ndirouhah 🤓

public ProductResponse linkImages(String is , LinkImagesRequest req , AuthenticatedUser u){
    isSeller(u);
    Product p = findMyProduct(is, u);
    List<String> resolvedlinks = req.imageUrls().stream().
    map(m -> resolvedMediaUrl(m, u)).toList();
    List<String> previousMediaIds = mediaIds(p);
    p.setImages(resolvedlinks.toArray(new String[0]));
    p.setMediaIds(req.imageUrls().toArray(new String[0]));
    p.setUpdatedAt(Instant.now());
    Product saved = productRepository.save(p);
    publishImageChange(saved, previousMediaIds, mediaIds(saved));
    return toResponse(saved);
}


private String resolvedMediaUrl(String mediaId, AuthenticatedUser user) {
        MediaMetadataResponse media = mediaServiceClient.fetchMetadata(mediaId);
        if (!user.userId().equals(media.uploaderId())) {
            throw new UnauthorizedException("You do not own media: " + mediaId);
        }
        return media.url();
}


public ProductResponse newProduct(CreateProductRequest req , AuthenticatedUser user){
        isSeller(user);
    requireImages(req.mediaIds());
        Instant now = Instant.now();

        List<String> resolvedImages = (req.mediaIds() != null && !req.mediaIds().isEmpty())
                ? req.mediaIds().stream().map(m -> resolvedMediaUrl(m, user)).toList()
                : List.of();

        Product p = Product.builder()
                .name(req.name())
                .description(req.description())
                .category(normalizeCategory(req.category()))
                .price(req.price())
                .quantity(req.quantity())
                .ownerId(user.userId())
                .images(resolvedImages.toArray(new String[0]))
                .mediaIds(req.mediaIds().toArray(new String[0]))
                .createdAt(now)
                .updatedAt(now)
                .build();
        Product saved = productRepository.save(p);
        kafkaTemplate.send(
            "PRODUCT_CREATED",
            saved.getId(),
            new ProductCreatedEvent(
                saved.getId(),
                saved.getOwnerId(),
                saved.getName(),
                saved.getPrice(),
                saved.getQuantity(),
                saved.getCreatedAt()
            )
        );
        return toResponse(saved);
    }


public ProductResponse updateProduct(String id, UpdateProduct request, AuthenticatedUser user) {
    requireImages(request.mediaIds());
    List<String> resolvedImages = (request.mediaIds() != null && !request.mediaIds().isEmpty())
                ? request.mediaIds().stream().map(m -> resolvedMediaUrl(m, user)).toList()
                : List.of();

        isSeller(user);
        Product product = findMyProduct(id, user);

        List<String> previousMediaIds = mediaIds(product);
        product.setName(request.name());
        product.setDescription(request.description());
        if (request.category() != null) {
            product.setCategory(normalizeCategory(request.category()));
        }
        product.setPrice(request.price());
        product.setImages(resolvedImages.toArray(new String[0]));
        product.setMediaIds(request.mediaIds().toArray(new String[0]));
        product.setQuantity(request.quantity());
        product.setUpdatedAt(Instant.now());

        Product saved = productRepository.save(product);
        publishImageChange(saved, previousMediaIds, mediaIds(saved));
        return toResponse(saved);
    }

    private void requireImages(List<String> mediaIds) {
        if (mediaIds == null || mediaIds.isEmpty()) {
            throw new BadRequest("At least one product image is required");
        }
    }



public void deleteP(String id , AuthenticatedUser user){
    isSeller(user);
   Product p = findMyProduct(id, user);
    productRepository.delete(p);
    publishImageChange(p, mediaIds(p), List.of());
}
 
  private List<String> mediaIds(Product product) {
        return product.getMediaIds() == null ? List.of() : List.copyOf(Arrays.asList(product.getMediaIds()));
    }

    private void publishImageChange(Product product, List<String> previous, List<String> current) {
        kafkaTemplate.send("PRODUCT_IMAGES_CHANGED", product.getId(),
                new ProductImagesChangedEvent(product.getId(), product.getOwnerId(), previous, current))
                .join();
    }

  private static String normalizeCategory(String category) {
        return category == null || category.isBlank()
                ? "uncategorized"
                : category.strip().toLowerCase(Locale.ROOT);
    }

  private ProductResponse toResponse(Product product) {
        return new ProductResponse(
                product.getId(),
                product.getName(),
                product.getDescription(),
                product.getPrice(),
                product.getOwnerId(),
                product.getImages(),
                product.getMediaIds(),
                product.getQuantity(),
                normalizeCategory(product.getCategory()));
    }
 
}
