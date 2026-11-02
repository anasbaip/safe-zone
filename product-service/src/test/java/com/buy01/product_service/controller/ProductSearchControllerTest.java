package com.buy01.product_service.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.buy01.product_service.dtos.req.ProductSearchRequest;
import com.buy01.product_service.dtos.response.ProductSearchResponse;
import com.buy01.product_service.exception.GlobalExceptionHandler;
import com.buy01.product_service.service.ProductService;

class ProductSearchControllerTest {

    private final ProductService service = mock(ProductService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ProductController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void publicSearchBindsDefaultPaginationAndReturnsMetadata() throws Exception {
        when(service.search(any())).thenReturn(new ProductSearchResponse(List.of(), 0, 0, 0, 12));

        mvc.perform(get("/products/search"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.totalPages").value(0))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(12));

        var request = ArgumentCaptor.forClass(ProductSearchRequest.class);
        verify(service).search(request.capture());
        assertThat(request.getValue().page()).isZero();
        assertThat(request.getValue().size()).isEqualTo(12);
        assertThat(request.getValue().sort()).isEqualTo("newest");
    }

    @Test
    void bindsAndNormalizesFilters() throws Exception {
        when(service.search(any())).thenReturn(new ProductSearchResponse(List.of(), 0, 0, 1, 5));
        mvc.perform(get("/products/search").param("q", " phone ").param("category", " Electronics ")
                .param("minPrice", "500").param("maxPrice", "3000").param("inStock", "true")
                .param("sort", "price-asc").param("page", "1").param("size", "5"))
                .andExpect(status().isOk());

        var request = ArgumentCaptor.forClass(ProductSearchRequest.class);
        verify(service).search(request.capture());
        assertThat(request.getValue().q()).isEqualTo("phone");
        assertThat(request.getValue().category()).isEqualTo("electronics");
        assertThat(request.getValue().minPrice()).isEqualByComparingTo("500");
        assertThat(request.getValue().maxPrice()).isEqualByComparingTo("3000");
        assertThat(request.getValue().inStock()).isTrue();
        assertThat(request.getValue().page()).isEqualTo(1);
    }

    @ParameterizedTest
    @CsvSource({"page,-1", "page,10001", "page,abc", "size,0", "size,101", "size,abc",
            "sort,ownerId", "minPrice,-1", "maxPrice,-1", "minPrice,NaN", "maxPrice,Infinity",
            "minPrice,1.001", "maxPrice,1000000000000", "inStock,invalid"})
    void invalidFiltersReturn400WithoutQuerying(String key, String value) throws Exception {
        mvc.perform(get("/products/search").param(key, value)).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void invertedPriceRangeReturns400() throws Exception {
        mvc.perform(get("/products/search").param("minPrice", "100").param("maxPrice", "50"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void oversizedTextFiltersReturn400() throws Exception {
        mvc.perform(get("/products/search").param("q", "x".repeat(201)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/products/search").param("category", "x".repeat(81)))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
