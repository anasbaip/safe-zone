package com.buy01.order_service.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.buy01.order_service.dtos.common.ShippingAddressDto;
import com.buy01.order_service.dtos.req.CreateOrderRequest;
import com.buy01.order_service.dtos.req.OrderFilter;
import com.buy01.order_service.dtos.req.OrderItemRequest;
import com.buy01.order_service.dtos.req.UpdateOrderStatusRequest;
import com.buy01.order_service.dtos.response.OrderItemResponse;
import com.buy01.order_service.dtos.response.OrderResponse;
import com.buy01.order_service.dtos.response.ReorderResponse;
import com.buy01.order_service.dtos.response.StatusChangeResponse;
import com.buy01.order_service.exception.ConflictException;
import com.buy01.order_service.exception.ForbiddenException;
import com.buy01.order_service.exception.GlobalExceptionHandler;
import com.buy01.order_service.exception.ResourceNotFoundException;
import com.buy01.order_service.exception.BadRequestException;
import com.buy01.order_service.exception.UpstreamServiceException;
import com.buy01.order_service.models.OrderStatus;
import com.buy01.order_service.models.PaymentMethod;
import com.buy01.order_service.security.AuthenticatedUser;
import com.buy01.order_service.security.CurrentUserArgumentResolver;
import com.buy01.order_service.security.UserContextFilter;
import com.buy01.order_service.service.OrderService;

class OrderControllerTest {

    private static final String CUSTOMER_ID = "customer-1";
    private static final String SELLER_ID = "seller-1";
    private static final String ORDER_ID = "order-1";

    private static final String VALID_ORDER_JSON = """
            {
              "items": [{"productId": "product-1", "quantity": 2}],
              "shippingAddress": {
                "fullName": "Jane Doe", "phone": "0600000000", "street": "1 Main St",
                "city": "Casablanca", "postalCode": "20000", "country": "Morocco"
              },
              "paymentMethod": "CASH_ON_DELIVERY"
            }
            """;

    private final OrderService orderService = mock(OrderService.class);
    private final AuthenticatedUser customer = new AuthenticatedUser(CUSTOMER_ID, List.of("CLIENT"));
    private final AuthenticatedUser seller = new AuthenticatedUser(SELLER_ID, List.of("SELLER"));
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new OrderController(orderService))
                .setCustomArgumentResolvers(new CurrentUserArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new UserContextFilter())
                .build();
    }

    // ---------- POST /orders ----------

    @Test
    void createOrder_returnsCreatedOrder() throws Exception {
        var expectedRequest = new CreateOrderRequest(
                List.of(new OrderItemRequest("product-1", 2)),
                new ShippingAddressDto("Jane Doe", "0600000000", "1 Main St", "Casablanca", "20000", "Morocco"),
                PaymentMethod.CASH_ON_DELIVERY);
        when(orderService.createOrder(expectedRequest, "key-1", customer)).thenReturn(order(OrderStatus.PENDING));

        mvc.perform(asCustomer(post("/orders")).header(OrderController.IDEMPOTENCY_KEY_HEADER, "key-1")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_ORDER_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(ORDER_ID))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.totalAmount").value(40.0))
                .andExpect(jsonPath("$.items[0].productName").value("Headphones"))
                .andExpect(jsonPath("$.statusHistory[0].status").value("PENDING"));
    }

    @Test
    void createOrder_withoutIdempotencyKey_returnsBadRequest() throws Exception {
        mvc.perform(asCustomer(post("/orders")).contentType(MediaType.APPLICATION_JSON).content(VALID_ORDER_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        verifyNoInteractions(orderService);
    }

    @Test
    void createOrder_withBlankIdempotencyKey_returnsValidationError() throws Exception {
        mvc.perform(asCustomer(post("/orders")).header(OrderController.IDEMPOTENCY_KEY_HEADER, " ")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_ORDER_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation Failed"))
                .andExpect(jsonPath("$.validationErrors.idempotencyKey")
                        .value("Idempotency-Key header must not be blank"));

        verifyNoInteractions(orderService);
    }

    @Test
    void createOrder_withInvalidBody_returnsFieldErrors() throws Exception {
        String body = """
                {"items": [], "shippingAddress": {"fullName": "", "phone": "1", "street": "s",
                 "city": "c", "postalCode": "p", "country": "x"}}
                """;

        mvc.perform(asCustomer(post("/orders")).header(OrderController.IDEMPOTENCY_KEY_HEADER, "key-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.items").value("order must contain at least one item"))
                .andExpect(jsonPath("$.validationErrors['shippingAddress.fullName']").value("full name is required"));

        verifyNoInteractions(orderService);
    }

    @Test
    void createOrder_withInvalidItemQuantity_returnsFieldError() throws Exception {
        String body = VALID_ORDER_JSON.replace("\"quantity\": 2", "\"quantity\": 0");

        mvc.perform(asCustomer(post("/orders")).header(OrderController.IDEMPOTENCY_KEY_HEADER, "key-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors['items[0].quantity']").value("quantity must be at least 1"));
    }

    @Test
    void createOrder_withUnknownPaymentMethod_returnsBadRequest() throws Exception {
        String body = VALID_ORDER_JSON.replace("CASH_ON_DELIVERY", "BITCOIN");

        mvc.perform(asCustomer(post("/orders")).header(OrderController.IDEMPOTENCY_KEY_HEADER, "key-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Malformed request body"));
    }

    @Test
    void createOrder_withoutAuthentication_returnsUnauthorized() throws Exception {
        mvc.perform(post("/orders").header(OrderController.IDEMPOTENCY_KEY_HEADER, "key-1")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_ORDER_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Unauthorized"));

        verifyNoInteractions(orderService);
    }

    @Test
    void createOrder_whenProductIsInvalid_returnsBadRequest() throws Exception {
        when(orderService.createOrder(any(), eq("key-1"), eq(customer)))
                .thenThrow(new BadRequestException("Product not found: product-1"));

        mvc.perform(asCustomer(post("/orders")).header(OrderController.IDEMPOTENCY_KEY_HEADER, "key-1")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_ORDER_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Product not found: product-1"));
    }

    @Test
    void createOrder_whenProductServiceIsDown_returnsBadGateway() throws Exception {
        when(orderService.createOrder(any(), eq("key-1"), eq(customer)))
                .thenThrow(new UpstreamServiceException("Product service is unreachable"));

        mvc.perform(asCustomer(post("/orders")).header(OrderController.IDEMPOTENCY_KEY_HEADER, "key-1")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_ORDER_JSON))
                .andExpect(status().isBadGateway());
    }

    // ---------- GET /orders/my and /orders/seller ----------

    @Test
    void getMyOrders_withoutFilters_passesEmptyFilter() throws Exception {
        when(orderService.getMyOrders(OrderFilter.none(), customer)).thenReturn(List.of(order(OrderStatus.PENDING)));

        mvc.perform(asCustomer(get("/orders/my")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(ORDER_ID));
    }

    @Test
    void getMyOrders_bindsAllFilters() throws Exception {
        var filter = new OrderFilter(OrderStatus.SHIPPED, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31),
                "headphones");
        when(orderService.getMyOrders(filter, customer)).thenReturn(List.of());

        mvc.perform(asCustomer(get("/orders/my")).param("status", "SHIPPED").param("from", "2026-01-01")
                        .param("to", "2026-01-31").param("q", "headphones"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());

        verify(orderService).getMyOrders(filter, customer);
    }

    @Test
    void getMyOrders_withFromAfterTo_returnsValidationError() throws Exception {
        mvc.perform(asCustomer(get("/orders/my")).param("from", "2026-02-01").param("to", "2026-01-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.dateRangeValid").value("from date must not be after to date"));

        verifyNoInteractions(orderService);
    }

    @Test
    void getMyOrders_withUnknownStatus_returnsBadRequest() throws Exception {
        mvc.perform(asCustomer(get("/orders/my")).param("status", "LOST"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.status").exists());

        verifyNoInteractions(orderService);
    }

    @Test
    void getSellerOrders_returnsOrders() throws Exception {
        when(orderService.getSellerOrders(new OrderFilter(OrderStatus.PENDING, null, null, null), seller))
                .thenReturn(List.of(order(OrderStatus.PENDING)));

        mvc.perform(asSeller(get("/orders/seller")).param("status", "PENDING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].items[0].sellerId").value(SELLER_ID));
    }

    @Test
    void getSellerOrders_whenNotSeller_returnsForbidden() throws Exception {
        when(orderService.getSellerOrders(OrderFilter.none(), customer))
                .thenThrow(new ForbiddenException("Only sellers can view seller orders"));

        mvc.perform(asCustomer(get("/orders/seller")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Only sellers can view seller orders"));
    }

    // ---------- GET /orders/{id} ----------

    @Test
    void getOrder_returnsOrder() throws Exception {
        when(orderService.getOrder(ORDER_ID, customer)).thenReturn(order(OrderStatus.CONFIRMED));

        mvc.perform(asCustomer(get("/orders/{id}", ORDER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.shippingAddress.city").value("Casablanca"));
    }

    @Test
    void getOrder_whenMissing_returnsNotFound() throws Exception {
        when(orderService.getOrder("missing", customer))
                .thenThrow(new ResourceNotFoundException("Order not found: missing"));

        mvc.perform(asCustomer(get("/orders/{id}", "missing")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.path").value("/orders/missing"));
    }

    @Test
    void getOrder_whenNotOwner_returnsForbidden() throws Exception {
        when(orderService.getOrder(ORDER_ID, seller)).thenThrow(new ForbiddenException("Not your order"));

        mvc.perform(asSeller(get("/orders/{id}", ORDER_ID)))
                .andExpect(status().isForbidden());
    }

    // ---------- PATCH /orders/{id}/cancel ----------

    @Test
    void cancelOrder_returnsCancelledOrder() throws Exception {
        when(orderService.cancelOrder(ORDER_ID, customer)).thenReturn(order(OrderStatus.CANCELLED));

        mvc.perform(asCustomer(patch("/orders/{id}/cancel", ORDER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void cancelOrder_whenAlreadyShipped_returnsConflict() throws Exception {
        when(orderService.cancelOrder(ORDER_ID, customer))
                .thenThrow(new ConflictException("A SHIPPED order cannot be cancelled"));

        mvc.perform(asCustomer(patch("/orders/{id}/cancel", ORDER_ID)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("A SHIPPED order cannot be cancelled"));
    }

    // ---------- POST /orders/{id}/reorder ----------

    @Test
    void reorder_returnsItemsToPurchaseAgain() throws Exception {
        when(orderService.reorder(ORDER_ID, customer))
                .thenReturn(new ReorderResponse(ORDER_ID, List.of(new OrderItemRequest("product-1", 2))));

        mvc.perform(asCustomer(post("/orders/{id}/reorder", ORDER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceOrderId").value(ORDER_ID))
                .andExpect(jsonPath("$.items[0].productId").value("product-1"))
                .andExpect(jsonPath("$.items[0].quantity").value(2));
    }

    // ---------- PATCH /orders/{id}/status ----------

    @Test
    void updateStatus_returnsUpdatedOrder() throws Exception {
        var request = new UpdateOrderStatusRequest(OrderStatus.SHIPPED, "Sent with DHL");
        when(orderService.updateStatus(ORDER_ID, request, seller)).thenReturn(order(OrderStatus.SHIPPED));

        mvc.perform(asSeller(patch("/orders/{id}/status", ORDER_ID)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"SHIPPED\", \"note\": \"Sent with DHL\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SHIPPED"));
    }

    @Test
    void updateStatus_withoutStatus_returnsValidationError() throws Exception {
        mvc.perform(asSeller(patch("/orders/{id}/status", ORDER_ID)).contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.status").value("status is required"));

        verifyNoInteractions(orderService);
    }

    @Test
    void updateStatus_withInvalidTransition_returnsConflict() throws Exception {
        when(orderService.updateStatus(eq(ORDER_ID), any(), eq(seller)))
                .thenThrow(new ConflictException("Cannot move order from DELIVERED to PENDING"));

        mvc.perform(asSeller(patch("/orders/{id}/status", ORDER_ID)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"PENDING\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void updateStatus_whenModifiedConcurrently_returnsConflict() throws Exception {
        when(orderService.updateStatus(eq(ORDER_ID), any(), eq(seller)))
                .thenThrow(new OptimisticLockingFailureException("version mismatch"));

        mvc.perform(asSeller(patch("/orders/{id}/status", ORDER_ID)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"CONFIRMED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("The order was modified at the same time, please retry"));
    }

    // ---------- generic errors ----------

    @Test
    void unexpectedError_returnsGenericInternalServerError() throws Exception {
        when(orderService.getOrder(ORDER_ID, customer)).thenThrow(new IllegalStateException("db password=secret"));

        mvc.perform(asCustomer(get("/orders/{id}", ORDER_ID)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"));
    }

    @Test
    void unsupportedMethod_returnsMethodNotAllowed() throws Exception {
        mvc.perform(asCustomer(delete("/orders/{id}", ORDER_ID)))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.error").value("Method Not Allowed"));
    }

    // ---------- helpers ----------

    private static MockHttpServletRequestBuilder asCustomer(MockHttpServletRequestBuilder builder) {
        return builder.header("X-User-Id", CUSTOMER_ID).header("X-User-Roles", "CLIENT");
    }

    private static MockHttpServletRequestBuilder asSeller(MockHttpServletRequestBuilder builder) {
        return builder.header("X-User-Id", SELLER_ID).header("X-User-Roles", "SELLER");
    }

    private static OrderResponse order(OrderStatus status) {
        Instant now = Instant.parse("2026-01-15T10:00:00Z");
        var item = new OrderItemResponse("product-1", SELLER_ID, "Headphones", "https://img/1.png",
                new BigDecimal("20.00"), 2, new BigDecimal("40.00"));
        var address = new ShippingAddressDto("Jane Doe", "0600000000", "1 Main St", "Casablanca", "20000", "Morocco");
        return new OrderResponse(ORDER_ID, CUSTOMER_ID, status, List.of(item), address,
                PaymentMethod.CASH_ON_DELIVERY, 2, new BigDecimal("40.00"),
                List.of(new StatusChangeResponse(OrderStatus.PENDING, now, CUSTOMER_ID, null)), now, now);
    }
}
