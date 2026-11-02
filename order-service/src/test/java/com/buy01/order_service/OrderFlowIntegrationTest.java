package com.buy01.order_service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

import com.buy01.order_service.client.ProductClient;
import com.buy01.order_service.dtos.external.ProductDetails;
import com.buy01.order_service.models.Order;
import com.buy01.order_service.models.OrderStatus;
import com.buy01.order_service.models.StatusChange;
import com.buy01.order_service.repository.OrderRepository;
import com.jayway.jsonpath.JsonPath;

/**
 * Order creation and retrieval through the real HTTP layer, service and MongoDB (Testcontainers).
 * Only Product Service is replaced. Skipped automatically where Docker is not available.
 */
@SpringBootTest(properties = "eureka.client.enabled=false")
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class OrderFlowIntegrationTest {

    @Container
    @ServiceConnection
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7");

    private static final String CUSTOMER = "customer-1";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private OrderRepository orders;

    @MockitoBean
    private ProductClient products;

    @BeforeEach
    void reset() {
        orders.deleteAll();
        when(products.getProduct("headphones"))
                .thenReturn(new ProductDetails("headphones", "Headphones", 19.99, "seller-a", List.of(), 10, "audio"));
        when(products.getProduct("cable"))
                .thenReturn(new ProductDetails("cable", "USB Cable", 4.5, "seller-b", List.of(), 50, null));
    }

    @Test
    void placedOrderIsStoredAndCanBeReadBack() throws Exception {
        String id = place("key-1", "headphones", 2);

        Order stored = orders.findById(id).orElseThrow();
        assertThat(stored.getCustomerId()).isEqualTo(CUSTOMER);
        assertThat(stored.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(stored.getTotalAmount()).isEqualByComparingTo(new BigDecimal("39.98"));
        assertThat(stored.getItems()).singleElement()
                .satisfies(item -> assertThat(item.category()).isEqualTo("audio"));
        assertThat(stored.getVersion()).isZero();

        mvc.perform(as(get("/orders/" + id), CUSTOMER, "CLIENT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalAmount").value(39.98))
                .andExpect(jsonPath("$.items[0].productName").value("Headphones"))
                .andExpect(jsonPath("$.statusHistory[0].note").value("Order placed"));
        mvc.perform(as(get("/orders/my"), CUSTOMER, "CLIENT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(id));
    }

    @Test
    void sameIdempotencyKeyCreatesOnlyOneOrder() throws Exception {
        String first = place("same-key", "headphones", 1);
        String second = place("same-key", "headphones", 1);

        assertThat(second).isEqualTo(first);
        assertThat(orders.count()).isEqualTo(1);
        verify(products, times(1)).reserveStock(anyList());
    }

    @Test
    void sellerSeesOnlyTheirLinesAndStrangersAreRefused() throws Exception {
        String id = mvc.perform(as(post("/orders"), CUSTOMER, "CLIENT")
                        .header("Idempotency-Key", "key-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("""
                                {"productId": "headphones", "quantity": 1}, {"productId": "cable", "quantity": 3}""")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        id = JsonPath.read(id, "$.id");

        mvc.perform(as(get("/orders/" + id), "seller-b", "SELLER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].productName").value("USB Cable"))
                .andExpect(jsonPath("$.items[0].category").value("uncategorized"))
                .andExpect(jsonPath("$.totalAmount").value(13.5));
        mvc.perform(as(get("/orders/seller"), "seller-b", "SELLER"))
                .andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(as(get("/orders/" + id), "customer-2", "CLIENT"))
                .andExpect(status().isForbidden());
    }

    @Test
    void cancellationIsPersistedWithItsHistory() throws Exception {
        String id = place("key-3", "headphones", 1);

        mvc.perform(as(patch("/orders/" + id + "/cancel"), CUSTOMER, "CLIENT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        Order stored = orders.findById(id).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(stored.getStatusHistory()).extracting(StatusChange::status)
                .containsExactly(OrderStatus.PENDING, OrderStatus.CANCELLED);
        assertThat(stored.getVersion()).isEqualTo(1);
        verify(products).releaseStock(anyList());

        mvc.perform(as(patch("/orders/" + id + "/cancel"), CUSTOMER, "CLIENT"))
                .andExpect(status().isConflict());
    }

    @Test
    void listsAreFilteredInTheDatabase() throws Exception {
        place("key-4", "headphones", 1);
        String cableOrder = place("key-5", "cable", 2);

        mvc.perform(as(get("/orders/my").param("q", "usb"), CUSTOMER, "CLIENT"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(cableOrder));
        mvc.perform(as(get("/orders/my").param("q", ".*"), CUSTOMER, "CLIENT"))
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(as(get("/orders/my").param("status", "CANCELLED"), CUSTOMER, "CLIENT"))
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(as(get("/orders/my").param("size", "1"), CUSTOMER, "CLIENT"))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    private String place(String key, String productId, int quantity) throws Exception {
        String response = mvc.perform(as(post("/orders"), CUSTOMER, "CLIENT")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{\"productId\": \"" + productId + "\", \"quantity\": " + quantity + "}")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.id");
    }

    private static String body(String items) {
        return """
                {"items": [%s],
                 "shippingAddress": {"fullName": "Sara B.", "phone": "0600000000", "street": "1 Main St",
                                     "city": "Casablanca", "postalCode": "20000", "country": "Morocco"}}
                """.formatted(items);
    }

    /** The identity headers the gateway would add after checking the JWT. */
    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String userId, String role) {
        return request.header("X-User-Id", userId).header("X-User-Roles", role);
    }
}
