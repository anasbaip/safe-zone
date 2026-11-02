package com.buy01.product_service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.buy01.product_service.repository.ProductSearchRepository;
import com.buy01.product_service.service.ProductService;

@SpringBootTest(properties = {
        // This smoke test checks bean wiring without a running MongoDB or Eureka.
        // Index creation stays enabled in the application's normal configuration.
        "spring.data.mongodb.auto-index-creation=false",
        "spring.mongodb.uri=mongodb://localhost:27017/product_service_test?serverSelectionTimeoutMS=1000",
        "eureka.client.enabled=false"
})
class ProductServiceApplicationTests {

    @Autowired
    private ProductService productService;

    @Autowired
    private ProductSearchRepository productSearchRepository;

    @Test
    void contextLoads() {
        assertThat(productService).isNotNull();
        assertThat(productSearchRepository).isNotNull();
    }

}
