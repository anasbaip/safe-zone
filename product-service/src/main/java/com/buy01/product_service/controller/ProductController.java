package com.buy01.product_service.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.buy01.product_service.dtos.req.CreateProductRequest;
import com.buy01.product_service.dtos.req.LinkImagesRequest;
import com.buy01.product_service.dtos.req.ProductSearchRequest;
import com.buy01.product_service.dtos.req.UpdateProduct;
import com.buy01.product_service.dtos.response.ProductResponse;
import com.buy01.product_service.dtos.response.ProductSearchResponse;
import com.buy01.product_service.security.AuthenticatedUser;
import com.buy01.product_service.security.CurrentUser;
import com.buy01.product_service.service.ProductService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/products")
@RequiredArgsConstructor
public class  ProductController {

    private final ProductService productService;


    @GetMapping
    public ResponseEntity<List<ProductResponse>> getAllProducts() {
        return ResponseEntity.ok(productService.getThemAll());
    }

    @GetMapping("/search")
    public ProductSearchResponse search(@Valid @ModelAttribute ProductSearchRequest filters) {
        return productService.search(filters);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProductResponse> getProductById(@PathVariable String id) {
        return ResponseEntity.ok(productService.getOneProduct(id));
    }


    @GetMapping("/myProducts")
    public ResponseEntity<List<ProductResponse>> getMyProducts(@CurrentUser AuthenticatedUser user) {
        return ResponseEntity.ok(productService.myProducts(user));
    }


    @PostMapping
    public ResponseEntity<ProductResponse> createProduct(
            @Valid @RequestBody CreateProductRequest request,
            @CurrentUser AuthenticatedUser user) {
        ProductResponse response = productService.newProduct(request, user);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }


    @PutMapping("/{id}")
    public ResponseEntity<ProductResponse> updateProduct(
            @PathVariable String id,
            @Valid @RequestBody UpdateProduct request,
            @CurrentUser AuthenticatedUser user) {
        return ResponseEntity.ok(productService.updateProduct(id, request, user));
    }


    @PutMapping("/{id}/images")
    public ResponseEntity<ProductResponse> addImages(
            @PathVariable String id,
            @Valid @RequestBody LinkImagesRequest request,
            @CurrentUser AuthenticatedUser user) {
        return ResponseEntity.ok(productService.linkImages(id, request, user));
    }


    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteProduct(@PathVariable String id, @CurrentUser AuthenticatedUser user) {
        productService.deleteP(id, user);
        return ResponseEntity.noContent().build();
    }
 

}
