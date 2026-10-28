package com.buy01.order_service.client;

import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import com.buy01.order_service.dtos.common.ShippingAddressDto;
import com.buy01.order_service.dtos.req.*;
import com.buy01.order_service.exception.UpstreamServiceException;
import com.buy01.order_service.models.PaymentMethod;
import com.buy01.order_service.security.AuthenticatedUser;

/** Wire-contract tests with a controlled HTTP peer, not evidence of order persistence. */
class OrdersClientTest {
    HttpServer server;
    OrdersClient client;
    int status = 201;
    String body = validOrder();
    AtomicReference<String> payload = new AtomicReference<>();
    AtomicReference<String> identity = new AtomicReference<>();
    AtomicReference<String> roles = new AtomicReference<>();
    AtomicReference<String> key = new AtomicReference<>();
    AuthenticatedUser user = new AuthenticatedUser("client", List.of("CLIENT"));
    ShippingAddressDto address = new ShippingAddressDto("Jane", "123", "Street", "City", "100", "Morocco");
    CreateOrderRequest request = new CreateOrderRequest(List.of(new OrderItemRequest("p1", 2)), address,
        PaymentMethod.CASH_ON_DELIVERY, new BigDecimal("20.50"));

    static String validOrder() { return """
        {"id":"order-1","customerId":"client","status":"PENDING",
         "items":[{"productId":"p1","sellerId":"seller","productName":"Product",
                   "unitPrice":10.25,"quantity":2,"lineTotal":20.50}],
         "shippingAddress":{"fullName":"Jane","phone":"123","street":"Street","city":"City","postalCode":"100","country":"Morocco"},
         "paymentMethod":"CASH_ON_DELIVERY","totalItems":2,"totalAmount":20.50,"statusHistory":[]}
        """; }
    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/orders", exchange -> {
            payload.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            identity.set(exchange.getRequestHeaders().getFirst("X-User-Id"));
            roles.set(exchange.getRequestHeaders().getFirst("X-User-Roles"));
            key.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start(); client = new OrdersClient("http://127.0.0.1:" + server.getAddress().getPort() + "/orders");
    }
    @AfterEach void stop() { server.stop(0); }
    @Test void sendsExistingOrdersContractOverRest() {
        assertThat(client.create(request, "checkout-key", user).id()).isEqualTo("order-1");
        assertThat(identity.get()).isEqualTo("client"); assertThat(roles.get()).isEqualTo("CLIENT");
        assertThat(key.get()).isEqualTo("checkout-key");
        assertThat(payload.get()).contains("\"productId\":\"p1\"", "\"quantity\":2", "\"paymentMethod\":\"CASH_ON_DELIVERY\"", "\"expectedTotal\":20.50")
            .doesNotContain("customerId", "unitPrice");
    }
    @Test void unavailableOrdersIsNotReportedAsSuccess() {
        status = 503; body = "{\"message\":\"Orders unavailable\"}";
        assertThatThrownBy(() -> client.create(request, "key", user)).isInstanceOf(UpstreamServiceException.class);
    }
    @Test void acceptedButUnconfirmedResponseIsNotSuccess() {
        status = 202;
        assertThatThrownBy(() -> client.create(request, "key", user)).isInstanceOf(UpstreamServiceException.class);
    }
    @Test void rejectsResponseForAnotherCustomer() {
        body = body.replace("\"customerId\":\"client\"", "\"customerId\":\"victim\""); reject();
    }
    @Test void rejectsChangedPrice() { body = body.replace("\"totalAmount\":20.50", "\"totalAmount\":99.00"); reject(); }
    @Test void rejectsDifferentProducts() { body = body.replace("\"productId\":\"p1\"", "\"productId\":\"other\""); reject(); }
    @Test void rejectsDifferentQuantities() { body = body.replace("\"quantity\":2", "\"quantity\":1"); reject(); }
    @Test void rejectsMissingPaymentMethod() { body = body.replace("\"CASH_ON_DELIVERY\"", "null"); reject(); }
    @Test void rejectsMissingId() { body = body.replace("\"order-1\"", "\"\""); reject(); }
    @Test void rejectsDifferentAddress() { body = body.replace("\"street\":\"Street\"", "\"street\":\"Other\""); reject(); }
    @Test void rejectsMalformedResponse() { body = "{invalid"; reject(); }
    void reject() { assertThatThrownBy(() -> client.create(request, "key", user)).isInstanceOf(UpstreamServiceException.class); }
}
