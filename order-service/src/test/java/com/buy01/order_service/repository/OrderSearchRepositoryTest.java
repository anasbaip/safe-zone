package com.buy01.order_service.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.regex.Pattern;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import com.buy01.order_service.dtos.req.OrderFilter;
import com.buy01.order_service.models.Order;
import com.buy01.order_service.models.OrderStatus;

class OrderSearchRepositoryTest {

    private final MongoTemplate mongoTemplate = mock(MongoTemplate.class);
    private final OrderSearchRepository repository = new OrderSearchRepository(mongoTemplate);
    private static final Pageable FIRST_PAGE = PageRequest.of(0, 20);

    @Test
    void customerOrders_withoutFilters_onlyRestrictsByCustomer() {
        Order order = new Order();
        when(mongoTemplate.find(any(Query.class), eq(Order.class))).thenReturn(List.of(order));

        var page = repository.findCustomerOrders("customer-1", OrderFilter.none(), FIRST_PAGE);

        assertThat(page.getContent()).containsExactly(order);
        // a first page that is not full gives the total without a count query
        assertThat(page.getTotalElements()).isEqualTo(1);
        verify(mongoTemplate, never()).count(any(Query.class), eq(Order.class));

        Query query = capturedQuery();
        assertThat(conditions(query)).containsExactly(new Document("customerId", "customer-1"));
        assertThat(query.getSortObject()).isEqualTo(new Document("createdAt", -1));
        assertThat(query.getSkip()).isZero();
        assertThat(query.getLimit()).isEqualTo(20);
    }

    @Test
    void fullPage_skipsEarlierOrdersAndCountsTheTotal() {
        when(mongoTemplate.find(any(Query.class), eq(Order.class)))
                .thenReturn(java.util.Collections.nCopies(10, new Order()));
        when(mongoTemplate.count(any(Query.class), eq(Order.class))).thenReturn(25L);

        var page = repository.findSellerOrders("seller-a", OrderFilter.none(), PageRequest.of(1, 10));

        Query query = capturedQuery();
        assertThat(query.getSkip()).isEqualTo(10);
        assertThat(query.getLimit()).isEqualTo(10);
        assertThat(page.getTotalElements()).isEqualTo(25);
        assertThat(page.getTotalPages()).isEqualTo(3);

        ArgumentCaptor<Query> counted = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).count(counted.capture(), eq(Order.class));
        // the count uses the same filters, without paging
        assertThat(counted.getValue().getQueryObject()).isEqualTo(query.getQueryObject());
        assertThat(counted.getValue().getLimit()).isZero();
    }

    @Test
    void partialLastPage_derivesTheTotalWithoutCounting() {
        when(mongoTemplate.find(any(Query.class), eq(Order.class))).thenReturn(List.of(new Order(), new Order()));

        var page = repository.findSellerOrders("seller-a", OrderFilter.none(), PageRequest.of(1, 10));

        assertThat(page.getTotalElements()).isEqualTo(12);
        verify(mongoTemplate, never()).count(any(Query.class), eq(Order.class));
    }

    @Test
    void sellerOrders_matchSellerIds() {
        repository.findSellerOrders("seller-a", OrderFilter.none(), FIRST_PAGE);

        assertThat(conditions(capturedQuery())).containsExactly(new Document("sellerIds", "seller-a"));
    }

    @Test
    void allFilters_areCombined() {
        var filter = new OrderFilter(OrderStatus.SHIPPED, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31),
                "  head.*  ");

        repository.findCustomerOrders("customer-1", filter, FIRST_PAGE);

        List<Document> conditions = conditions(capturedQuery());
        assertThat(conditions).hasSize(4);
        assertThat(conditions.get(1)).isEqualTo(new Document("status", OrderStatus.SHIPPED));
        assertThat(conditions.get(2)).isEqualTo(new Document("createdAt", new Document()
                .append("$gte", Instant.parse("2026-01-01T00:00:00Z"))
                .append("$lt", Instant.parse("2026-02-01T00:00:00Z"))));

        List<Document> or = conditions.get(3).getList("$or", Document.class);
        assertThat(or.get(0)).isEqualTo(new Document("id", "head.*"));
        Pattern productName = (Pattern) or.get(1).get("items.productName");
        // regex characters typed by the user are matched literally, case-insensitively
        assertThat(productName.matcher("Big HEAD.* Phones").find()).isTrue();
        assertThat(productName.matcher("headphones").find()).isFalse();
    }

    @Test
    void onlyFromDate() {
        repository.findCustomerOrders("customer-1", new OrderFilter(null, LocalDate.of(2026, 3, 5), null, null), FIRST_PAGE);

        assertThat(conditions(capturedQuery()).get(1))
                .isEqualTo(new Document("createdAt", new Document("$gte", Instant.parse("2026-03-05T00:00:00Z"))));
    }

    @Test
    void onlyToDate_includesTheWholeDay() {
        repository.findCustomerOrders("customer-1", new OrderFilter(null, null, LocalDate.of(2026, 3, 5), null), FIRST_PAGE);

        assertThat(conditions(capturedQuery()).get(1))
                .isEqualTo(new Document("createdAt", new Document("$lt", Instant.parse("2026-03-06T00:00:00Z"))));
    }

    @Test
    void blankSearchText_isIgnored() {
        repository.findCustomerOrders("customer-1", new OrderFilter(null, null, null, "   "), FIRST_PAGE);

        assertThat(conditions(capturedQuery())).hasSize(1);
    }

    private Query capturedQuery() {
        ArgumentCaptor<Query> captor = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).find(captor.capture(), eq(Order.class));
        return captor.getValue();
    }

    private static List<Document> conditions(Query query) {
        return query.getQueryObject().getList("$and", Document.class);
    }
}
