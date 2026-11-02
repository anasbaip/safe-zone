package com.buy01.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.concurrent.atomic.AtomicReference;
import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Exercises the real Gateway routing + security filter chain against a REST backend. */
@org.springframework.test.context.TestPropertySource(locations = "file:src/main/resources/application.properties",
    properties = {"jwt.secret=test-secret-key-must-be-at-least-256-bits-long-for-hmac-sha-256!", "eureka.client.enabled=false", "spring.config.import="})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CartRoutingTest {
    static final String SECRET = "test-secret-key-must-be-at-least-256-bits-long-for-hmac-sha-256!";
    static final AtomicReference<String> received = new AtomicReference<>();
    static final HttpServer backend = startBackend();
    @LocalServerPort int port;
    HttpClient http = HttpClient.newHttpClient();

    static HttpServer startBackend() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/cart", exchange -> {
                received.set(exchange.getRequestURI().getPath() + ":" + exchange.getRequestHeaders().getFirst("X-User-Id")
                    + ":" + exchange.getRequestHeaders().getFirst("X-User-Roles"));
                byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body); exchange.close();
            });
            server.start(); return server;
        } catch (java.io.IOException ex) { throw new IllegalStateException(ex); }
    }
    @DynamicPropertySource static void config(DynamicPropertyRegistry registry) {
        registry.add("cart-service.uri", () -> "http://127.0.0.1:" + backend.getAddress().getPort());
    }
    @AfterAll static void stop() { backend.stop(0); }
    String jwt(String role) {
        return Jwts.builder().subject("client-1").claim("role", role)
            .expiration(new Date(System.currentTimeMillis() + 60000))
            .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
    }
    HttpRequest.Builder request(String path) { return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)); }
    @Test void anonymousAndSellerCannotReadEitherCartPath() throws Exception {
        for (String path : new String[]{"/cart", "/api/cart", "/api/cart/items", "/cart/checkout", "/api/cart/checkout"}) {
            var anonymous = http.send(request(path).header("X-User-Id", "victim").header("X-User-Roles", "CLIENT").GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(anonymous.statusCode()).isIn(401, 403);
            var seller = http.send(request(path).header("Authorization", "Bearer " + jwt("SELLER")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(seller.statusCode()).isEqualTo(403);
        }
    }
    @Test void bothPathsRouteWithAuthenticatedIdentityOverwritingSpoofedHeaders() throws Exception {
        for (String path : new String[]{"/cart", "/api/cart"}) {
            var result = http.send(request(path).header("Authorization", "Bearer " + jwt("CLIENT"))
                .header("x-user-id", "victim").header("x-user-roles", "SELLER").GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(result.statusCode()).isEqualTo(200);
            assertThat(received.get()).isEqualTo("/cart:client-1:CLIENT");
        }
    }
    @Test void checkoutPreservesExistingCsrfProtectionAndRoutesWithToken() throws Exception {
        var csrf = http.send(request("/csrf").GET().build(), HttpResponse.BodyHandlers.ofString());
        String token = new ObjectMapper().readTree(csrf.body()).required("token").asText();
        String cookie = csrf.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
        for (String path : new String[]{"/cart/checkout", "/api/cart/checkout"}) {
            var rejected = http.send(request(path).header("Authorization", "Bearer " + jwt("CLIENT"))
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(rejected.statusCode()).isEqualTo(403);
            var accepted = http.send(request(path).header("Authorization", "Bearer " + jwt("CLIENT"))
                .header("Cookie", cookie).header("X-XSRF-TOKEN", token).header("Idempotency-Key", "test-key")
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(accepted.statusCode()).isEqualTo(200);
            assertThat(received.get()).isEqualTo("/cart/checkout:client-1:CLIENT");
        }
    }
}
