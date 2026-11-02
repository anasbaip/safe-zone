package com.buy01.order_service.repository;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.stereotype.Repository;

import com.buy01.order_service.dtos.req.OrderFilter;
import com.buy01.order_service.models.Order;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class OrderSearchRepository {

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "createdAt");

    private final MongoTemplate mongoTemplate;

    public Page<Order> findCustomerOrders(String customerId, OrderFilter filter, Pageable pageable) {
        return find(Criteria.where("customerId").is(customerId), filter, pageable);
    }

    public Page<Order> findSellerOrders(String sellerId, OrderFilter filter, Pageable pageable) {
        return find(Criteria.where("sellerIds").is(sellerId), filter, pageable);
    }

    private Page<Order> find(Criteria owner, OrderFilter filter, Pageable pageable) {
        Query query = buildQuery(owner, filter);
        List<Order> orders = mongoTemplate.find(Query.of(query).with(NEWEST_FIRST).with(pageable), Order.class);
        // The count is skipped when the page alone tells the total (e.g. a first page that is not full)
        return PageableExecutionUtils.getPage(orders, pageable, () -> mongoTemplate.count(query, Order.class));
    }

    Query buildQuery(Criteria owner, OrderFilter filter) {
        List<Criteria> criteria = new ArrayList<>();
        criteria.add(owner);

        if (filter.status() != null) {
            criteria.add(Criteria.where("status").is(filter.status()));
        }
        if (filter.from() != null || filter.to() != null) {
            Criteria createdAt = Criteria.where("createdAt");
            if (filter.from() != null) {
                createdAt.gte(filter.from().atStartOfDay(ZoneOffset.UTC).toInstant());
            }
            if (filter.to() != null) {
                // "to" is inclusive: everything before the start of the next day
                createdAt.lt(filter.to().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant());
            }
            criteria.add(createdAt);
        }
        if (filter.q() != null && !filter.q().isBlank()) {
            String text = filter.q().trim();
            // Quoted so user input is matched literally, never interpreted as a regex
            criteria.add(new Criteria().orOperator(
                    Criteria.where("id").is(text),
                    Criteria.where("items.productName").regex(Pattern.quote(text), "i")));
        }

        return new Query(new Criteria().andOperator(criteria));
    }
}
