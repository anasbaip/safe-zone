package com.buy01.order_service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

import com.buy01.order_service.dtos.response.AnalyticsResponse;
import com.buy01.order_service.dtos.response.AnalyticsResponse.ProductSummary;
import com.buy01.order_service.dtos.response.AnalyticsResponse.CategorySummary;
import com.buy01.order_service.dtos.response.AnalyticsResponse.MonthlySummary;
import com.buy01.order_service.models.Order;
import com.buy01.order_service.models.OrderItem;
import com.buy01.order_service.models.OrderStatus;
import com.buy01.order_service.repository.AnalyticsRepository;

@SpringBootTest(properties = "eureka.client.enabled=false")
@Testcontainers(disabledWithoutDocker = true)
class AnalyticsIntegrationTest {
    @Container
    @ServiceConnection
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7");

    @Autowired private MongoTemplate mongo;
    @Autowired private AnalyticsRepository analytics;
    private static final LocalDate FROM = LocalDate.parse("2026-01-01");
    private static final LocalDate TO = LocalDate.parse("2026-03-31");

    @BeforeEach
    void reset() { mongo.remove(new org.springframework.data.mongodb.core.query.Query(), Order.class); }

    @Test
    void aggregatesDecimalSnapshotsAndCountsDistinctOrdersRatherThanLines() {
        save("a", "c1", "2026-01-01T00:00:00Z", OrderStatus.DELIVERED,
                item("p1", "s1", "audio", 2, "0.10"), item("p2", "s2", null, 3, "0.20"));
        save("b", "c1", "2026-03-31T23:59:59.999Z", OrderStatus.DELIVERED,
                item("p1", "s1", "audio", 1, "0.15"));
        AnalyticsResponse result = analytics.summarize("c1", false, FROM, TO);
        assertThat(result.totalAmount()).isEqualByComparingTo("0.95");
        assertThat(result.orderCount()).isEqualTo(2);
        assertThat(result.totalUnits()).isEqualTo(6);
        assertThat(result.products()).extracting(ProductSummary::productId).containsExactly("p2", "p1");
        assertThat(result.categories()).extracting(CategorySummary::category).containsExactly("uncategorized", "audio");
        assertThat(result.months()).extracting(MonthlySummary::orderCount).containsExactly(1L, 0L, 1L);
        assertThat(result.months().get(0).amount()).isEqualByComparingTo("0.80");
    }

    @Test
    void filtersStatusOwnerAndInclusiveUtcDatesBeforeAggregation() {
        save("inside", "c1", "2026-01-01T00:00:00Z", OrderStatus.DELIVERED, item("p", "s1", " ", 1, "5.00"));
        save("before", "c1", "2025-12-31T23:59:59.999Z", OrderStatus.DELIVERED, item("p", "s1", "x", 1, "100"));
        save("after", "c1", "2026-04-01T00:00:00Z", OrderStatus.DELIVERED, item("p", "s1", "x", 1, "100"));
        save("other", "c2", "2026-01-02T00:00:00Z", OrderStatus.DELIVERED, item("p", "s2", "x", 1, "100"));
        for (OrderStatus status : List.of(OrderStatus.PENDING, OrderStatus.CONFIRMED, OrderStatus.SHIPPED, OrderStatus.CANCELLED)) {
            save(status.name(), "c1", "2026-01-02T00:00:00Z", status, item("p", "s1", "x", 1, "100"));
        }
        var result = analytics.summarize("c1", false, FROM, TO);
        assertThat(result.totalAmount()).isEqualByComparingTo("5.00");
        assertThat(result.orderCount()).isEqualTo(1);
        assertThat(result.categories().get(0).category()).isEqualTo("uncategorized");
    }

    @Test
    void sellerCanOnlyCountTheirLinesInMixedSellerOrders() {
        save("mixed", "c1", "2026-01-15T00:00:00Z", OrderStatus.DELIVERED,
                item("mine", "s1", "books", 2, "10.00"), item("theirs", "s2", "audio", 5, "100.00"));
        var mine = analytics.summarize("s1", true, FROM, TO);
        assertThat(mine.totalAmount()).isEqualByComparingTo("20.00");
        assertThat(mine.orderCount()).isEqualTo(1);
        assertThat(mine.totalUnits()).isEqualTo(2);
        assertThat(mine.products()).extracting(ProductSummary::productId).containsExactly("mine");
        assertThat(mine.categories()).extracting(CategorySummary::category).containsExactly("books");
        assertThat(mine.months().get(0).amount()).isEqualByComparingTo("20.00");
        assertThat(analytics.summarize("stranger", true, FROM, TO).orderCount()).isZero();
    }

    @Test
    void emptyResultsHaveZeroTotalsAndZeroFilledMonths() {
        var result = analytics.summarize("empty", false, FROM, TO);
        assertThat(result.totalAmount()).isEqualByComparingTo("0");
        assertThat(result.orderCount()).isZero();
        assertThat(result.totalUnits()).isZero();
        assertThat(result.products()).isEmpty();
        assertThat(result.categories()).isEmpty();
        assertThat(result.months()).hasSize(3).allSatisfy(month -> {
            assertThat(month.amount()).isEqualByComparingTo("0");
            assertThat(month.orderCount()).isZero();
        });
    }

    @Test
    void rankingsAreLimitedAndTiesHaveStableProductOrder() {
        for (int i = 6; i >= 0; i--) {
            save("order" + i, "c1", "2026-01-01T00:00:00Z", OrderStatus.DELIVERED,
                    item("p" + i, "s1", "category" + i, 1, "1.00"));
        }
        var result = analytics.summarize("c1", false, FROM, TO);
        assertThat(result.products()).extracting(ProductSummary::productId).containsExactly("p0", "p1", "p2", "p3", "p4");
        assertThat(result.categories()).hasSize(5);
        assertThat(result.orderCount()).isEqualTo(7);
    }

    private void save(String id, String customer, String created, OrderStatus status, OrderItem... items) {
        mongo.insert(Order.builder().id(id).idempotencyKey(id).customerId(customer).status(status)
                .createdAt(Instant.parse(created)).items(List.of(items))
                .sellerIds(List.of(items).stream().map(OrderItem::sellerId).distinct().toList())
                .totalAmount(new BigDecimal("999999.00")).build());
    }

    private static OrderItem item(String product, String seller, String category, int units, String price) {
        BigDecimal unitPrice = new BigDecimal(price);
        return new OrderItem(product, seller, product + " snapshot", category, null, unitPrice, units,
                unitPrice.multiply(BigDecimal.valueOf(units)));
    }
}
