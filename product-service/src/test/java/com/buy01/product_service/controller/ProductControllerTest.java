package com.buy01.product_service.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import com.buy01.product_service.dtos.req.CreateProductRequest;
import com.buy01.product_service.dtos.req.LinkImagesRequest;
import com.buy01.product_service.dtos.req.UpdateProduct;
import com.buy01.product_service.dtos.response.ProductResponse;
import com.buy01.product_service.security.AuthenticatedUser;
import com.buy01.product_service.service.ProductService;

@ExtendWith(MockitoExtension.class)
class ProductControllerTest {

    @Mock
    private ProductService productService;

    @InjectMocks
    private ProductController controller;

    private final AuthenticatedUser user = new AuthenticatedUser("owner", List.of("SELLER"));
    private final ProductResponse response = new ProductResponse("p1", "n", "d", 5.0, "owner",
            new String[]{"u"}, new String[]{"m"}, 1, null);

    @Test
    void getAllProducts_returnsOk() {
        when(productService.getThemAll()).thenReturn(List.of(response));

        assertThat(controller.getAllProducts().getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void getProductById_returnsOk() {
        when(productService.getOneProduct("p1")).thenReturn(response);

        assertThat(controller.getProductById("p1").getBody().id()).isEqualTo("p1");
    }

    @Test
    void getMyProducts_returnsOk() {
        when(productService.myProducts(user)).thenReturn(List.of(response));

        assertThat(controller.getMyProducts(user).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void createProduct_returnsCreated() {
        var request = new CreateProductRequest("n", "d", 5.0, 1, List.of("m"), null);
        when(productService.newProduct(request, user)).thenReturn(response);

        assertThat(controller.createProduct(request, user).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void updateProduct_returnsOk() {
        var request = new UpdateProduct("n", "d", 5.0, 1, List.of("m"), null);
        when(productService.updateProduct("p1", request, user)).thenReturn(response);

        assertThat(controller.updateProduct("p1", request, user).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void addImages_returnsOk() {
        var request = new LinkImagesRequest(List.of("m"));
        when(productService.linkImages("p1", request, user)).thenReturn(response);

        assertThat(controller.addImages("p1", request, user).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void deleteProduct_returnsNoContent() {
        assertThat(controller.deleteProduct("p1", user).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(productService).deleteP("p1", user);
    }
}