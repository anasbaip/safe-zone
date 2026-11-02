package com.buy01.order_service.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.buy01.order_service.exception.BadRequestException;
import com.buy01.order_service.exception.UpstreamServiceException;
import com.buy01.order_service.models.OrderItem;

class ProductClientTest {

    private MockRestServiceServer server;
    private ProductClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new ProductClient(builder, "http://product-service");
    }

    @Test
    void getProduct_mapsProductServiceResponse() {
        server.expect(requestTo("http://product-service/products/p1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"id": "p1", "name": "Headphones", "description": "ignored", "price": 19.99,
                         "ownerId": "seller-a", "images": ["https://img/1.png"], "mediaIds": ["m1"],
                         "quantity": 7, "category": "audio"}
                        """, MediaType.APPLICATION_JSON));

        var product = client.getProduct("p1");

        assertThat(product.name()).isEqualTo("Headphones");
        assertThat(product.price()).isEqualTo(19.99);
        assertThat(product.ownerId()).isEqualTo("seller-a");
        assertThat(product.firstImage()).isEqualTo("https://img/1.png");
        assertThat(product.quantity()).isEqualTo(7);
        assertThat(product.categoryOrDefault()).isEqualTo("audio");
        server.verify();
    }

    @Test
    void getProduct_withoutImages() {
        server.expect(requestTo("http://product-service/products/p1"))
                .andRespond(withSuccess("{\"id\": \"p1\", \"name\": \"n\", \"price\": 1, \"ownerId\": \"s\", \"quantity\": 3}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.getProduct("p1").firstImage()).isNull();
    }

    @Test
    void getProduct_notFound_isBadRequest() {
        server.expect(requestTo("http://product-service/products/gone")).andRespond(withResourceNotFound());

        assertThatThrownBy(() -> client.getProduct("gone"))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Product not found: gone");
    }

    @Test
    void getProduct_serverError_isUpstreamError() {
        server.expect(requestTo("http://product-service/products/p1")).andRespond(withServerError());

        assertThatThrownBy(() -> client.getProduct("p1"))
                .isInstanceOf(UpstreamServiceException.class)
                .hasMessageContaining("returned 500");
    }

    @Test
    void getProduct_unreachable_isUpstreamError() {
        server.expect(requestTo("http://product-service/products/p1")).andRespond(request -> {
            throw new IOException("connection refused");
        });

        assertThatThrownBy(() -> client.getProduct("p1"))
                .isInstanceOf(UpstreamServiceException.class)
                .hasMessageContaining("unreachable");
    }

    @Test
    void getProduct_emptyBody_isUpstreamError() {
        server.expect(requestTo("http://product-service/products/p1")).andRespond(withSuccess());

        assertThatThrownBy(() -> client.getProduct("p1"))
                .isInstanceOf(UpstreamServiceException.class)
                .hasMessageContaining("no data");
    }

    private static final List<OrderItem> ITEMS = List.of(
            new OrderItem("p1", "seller-a", "Headphones", "audio", null, new BigDecimal("20.00"), 2, new BigDecimal("40.00")),
            new OrderItem("p2", "seller-b", "Cable", "accessories", null, new BigDecimal("5.00"), 1, new BigDecimal("5.00")));

    @Test
    void reserveStock_postsProductIdsAndQuantities() {
        server.expect(requestTo("http://product-service/internal/stock/reserve"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"items": [{"productId": "p1", "quantity": 2}, {"productId": "p2", "quantity": 1}]}
                        """, org.springframework.test.json.JsonCompareMode.STRICT))
                .andRespond(withNoContent());

        client.reserveStock(ITEMS);

        server.verify();
    }

    @Test
    void reserveStock_notEnoughStock_isBadRequestWithProductServiceMessage() {
        server.expect(requestTo("http://product-service/internal/stock/reserve"))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"status\": 409, \"message\": \"Not enough stock for Cable: 0 available\"}"));

        assertThatThrownBy(() -> client.reserveStock(ITEMS))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Not enough stock for Cable: 0 available");
    }

    @Test
    void reserveStock_withoutReadableMessage_usesGenericMessage() {
        server.expect(requestTo("http://product-service/internal/stock/reserve"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.TEXT_PLAIN).body("gone"));

        assertThatThrownBy(() -> client.reserveStock(ITEMS))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Not enough stock for one or more products");
    }

    @Test
    void reserveStock_serverError_isUpstreamError() {
        server.expect(requestTo("http://product-service/internal/stock/reserve")).andRespond(withServerError());

        assertThatThrownBy(() -> client.reserveStock(ITEMS))
                .isInstanceOf(UpstreamServiceException.class)
                .hasMessage("Product Service returned 500 while reserving stock");
    }

    @Test
    void reserveStock_unreachable_isUpstreamError() {
        server.expect(requestTo("http://product-service/internal/stock/reserve")).andRespond(request -> {
            throw new IOException("connection refused");
        });

        assertThatThrownBy(() -> client.reserveStock(ITEMS))
                .isInstanceOf(UpstreamServiceException.class)
                .hasMessageContaining("unreachable");
    }

    @Test
    void releaseStock_postsItems() {
        server.expect(requestTo("http://product-service/internal/stock/release"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"items\": [{\"productId\": \"p1\", \"quantity\": 2}, "
                        + "{\"productId\": \"p2\", \"quantity\": 1}]}"))
                .andRespond(withNoContent());

        client.releaseStock(ITEMS);

        server.verify();
    }

    @Test
    void releaseStock_errors_areUpstreamErrors() {
        server.expect(requestTo("http://product-service/internal/stock/release")).andRespond(withServerError());

        assertThatThrownBy(() -> client.releaseStock(ITEMS))
                .isInstanceOf(UpstreamServiceException.class)
                .hasMessage("Product Service returned 500 while releasing stock");
    }

    @Test
    void releaseStock_unreachable_isUpstreamError() {
        server.expect(requestTo("http://product-service/internal/stock/release")).andRespond(request -> {
            throw new IOException("connection refused");
        });

        assertThatThrownBy(() -> client.releaseStock(ITEMS))
                .isInstanceOf(UpstreamServiceException.class)
                .hasMessageContaining("unreachable");
    }

    @Test
    void cartGet_preservesExactPriceAndProductPath() {
        server.expect(requestTo("http://product-service/products/p1"))
                .andRespond(withSuccess("""
                        {"id":"p1","name":"Product","price":10.25,"quantity":7,"images":[]}
                        """, MediaType.APPLICATION_JSON));

        var product = client.get("p1");

        assertThat(product.price()).isEqualByComparingTo("10.25");
        assertThat(product.quantity()).isEqualTo(7);
        server.verify();
    }

    @Test
    void cartGet_missingProductRemainsRemovable() {
        server.expect(requestTo("http://product-service/products/gone")).andRespond(withResourceNotFound());

        assertThat(client.get("gone")).isNull();
        server.verify();
    }

    @Test
    void cartGet_invalidPriceIsRejected() {
        server.expect(requestTo("http://product-service/products/p1"))
                .andRespond(withSuccess("""
                        {"id":"p1","name":"Product","price":-1,"quantity":7,"images":[]}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.get("p1")).isInstanceOf(UpstreamServiceException.class);
        server.verify();
    }
}
