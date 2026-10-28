package com.buy01.order_service.repository;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

import com.buy01.order_service.dtos.req.OrderFilter;
import com.buy01.order_service.models.Order;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class OrderSearchRepository {

    /** The lists are not paginated yet, so cap them. */
    static final int MAX_RESULTS = 200;

    private final MongoTemplate mongoTemplate;

    public List<Order> findCustomerOrders(String customerId, OrderFilter filter) {
        return mongoTemplate.find(buildQuery(Criteria.where("customerId").is(customerId), filter), Order.class);
    }

    public List<Order> findSellerOrders(String sellerId, OrderFilter filter) {
        return mongoTemplate.find(buildQuery(Criteria.where("sellerIds").is(sellerId), filter), Order.class);
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

        return new Query(new Criteria().andOperator(criteria))
                .with(Sort.by(Sort.Direction.DESC, "createdAt"))
                .limit(MAX_RESULTS);
    }
}
