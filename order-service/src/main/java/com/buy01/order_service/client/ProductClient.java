package com.buy01.order_service.client;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.buy01.order_service.dtos.external.ProductDetails;
import com.buy01.order_service.dtos.external.StockRequest;
import com.buy01.order_service.dtos.external.UpstreamError;
import com.buy01.order_service.exception.BadRequestException;
import com.buy01.order_service.exception.UpstreamServiceException;
import com.buy01.order_service.models.OrderItem;

/** Reads product data from Product Service, found through Eureka, and reserves or releases its stock. */
@Component
public class ProductClient {

    public record Product(String id, String name, BigDecimal price, int quantity, List<String> images) {}

    private final RestClient restClient;

    public ProductClient(@LoadBalanced RestClient.Builder loadBalancedRestClientBuilder,
            @Value("${product-service.base-url}") String baseUrl) {
        this.restClient = loadBalancedRestClientBuilder.baseUrl(baseUrl).build();
    }

    /** Cart reads retain unavailable products so customers can remove them. */
    public Product get(String id) {
        try {
            Product product = restClient.get().uri("/products/{id}", id)
                    .accept(MediaType.APPLICATION_JSON).retrieve().body(Product.class);
            if (product == null || !id.equals(product.id()) || product.price() == null
                    || product.price().signum() < 0) {
                throw new UpstreamServiceException("Invalid Product Service response");
            }
            return product;
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 404) return null;
            throw new UpstreamServiceException("Product Service could not validate this cart");
        } catch (RestClientException ex) {
            throw new UpstreamServiceException("Product Service is unavailable. Try again later");
        }
    }

    public ProductDetails getProduct(String productId) {
        ProductDetails product;
        try {
            product = restClient.get()
                    .uri("/products/{id}", productId)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(ProductDetails.class);
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 404) {
                throw new BadRequestException("Product not found: " + productId);
            }
            throw new UpstreamServiceException(
                    "Product Service returned " + ex.getStatusCode().value() + " for product " + productId);
        } catch (RestClientException ex) {
            throw new UpstreamServiceException("Product Service is unreachable for product " + productId);
        }
        if (product == null) {
            throw new UpstreamServiceException("Product Service returned no data for product " + productId);
        }
        return product;
    }

    /** Takes all items from stock, or none of them. Not enough stock is the customer's problem: 400. */
    public void reserveStock(List<OrderItem> items) {
        try {
            postStock("/internal/stock/reserve", items);
        } catch (RestClientResponseException ex) {
            int status = ex.getStatusCode().value();
            if (status == 409 || status == 404) {
                throw new BadRequestException(messageOf(ex, "Not enough stock for one or more products"));
            }
            throw new UpstreamServiceException("Product Service returned " + status + " while reserving stock");
        } catch (RestClientException ex) {
            throw new UpstreamServiceException("Product Service is unreachable while reserving stock");
        }
    }

    public void releaseStock(List<OrderItem> items) {
        try {
            postStock("/internal/stock/release", items);
        } catch (RestClientResponseException ex) {
            throw new UpstreamServiceException(
                    "Product Service returned " + ex.getStatusCode().value() + " while releasing stock");
        } catch (RestClientException ex) {
            throw new UpstreamServiceException("Product Service is unreachable while releasing stock");
        }
    }

    private void postStock(String path, List<OrderItem> items) {
        restClient.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .body(StockRequest.of(items))
                .retrieve()
                .toBodilessEntity();
    }

    private static String messageOf(RestClientResponseException ex, String fallback) {
        try {
            UpstreamError error = ex.getResponseBodyAs(UpstreamError.class);
            return error == null || error.message() == null ? fallback : error.message();
        } catch (RuntimeException unreadable) {
            return fallback;
        }
    }
}
