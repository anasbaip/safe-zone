package com.buy01.product_service.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.TextCriteria;
import org.springframework.stereotype.Repository;

import com.buy01.product_service.dtos.req.ProductSearchRequest;
import com.buy01.product_service.models.Product;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class ProductSearchRepository {

    private static final String CATEGORY_FIELD = "category";
    private static final String PRICE_FIELD = "price";

    private final MongoTemplate mongoTemplate;

    public Page<Product> search(ProductSearchRequest filters) {
        Query query = buildQuery(filters);
        // Count every matching product before adding the page's skip/limit.
        long total = mongoTemplate.count(Query.of(query), Product.class);
        var pageable = PageRequest.of(filters.page(), filters.size(), sortBy(filters.sort()));
        var products = mongoTemplate.find(query.with(pageable), Product.class);
        return new PageImpl<>(products, pageable, total);
    }

    Query buildQuery(ProductSearchRequest filters) {
        Query query = new Query();
        if (filters.q() != null) {
            query.addCriteria(TextCriteria.forDefaultLanguage().matching(filters.q()));
        }
        if (filters.category() != null) {
            if ("uncategorized".equals(filters.category())) {
                // MongoDB's null match also includes legacy documents with no category field.
                query.addCriteria(new Criteria().orOperator(
                        Criteria.where(CATEGORY_FIELD).is("uncategorized"),
                        Criteria.where(CATEGORY_FIELD).is(null),
                        Criteria.where(CATEGORY_FIELD).regex("^\\s*$")));
            } else {
                query.addCriteria(Criteria.where(CATEGORY_FIELD).is(filters.category()));
            }
        }
        if (filters.minPrice() != null || filters.maxPrice() != null) {
            Criteria price = Criteria.where(PRICE_FIELD);
            // Products currently store prices as doubles; query the same numeric type.
            if (filters.minPrice() != null) {
                price.gte(filters.minPrice().doubleValue());
            }
            if (filters.maxPrice() != null) {
                price.lte(filters.maxPrice().doubleValue());
            }
            query.addCriteria(price);
        }
        if (Boolean.TRUE.equals(filters.inStock())) {
            query.addCriteria(Criteria.where("quantity").gt(0));
        }
        return query;
    }

    private Sort sortBy(String sort) {
        Sort primary = switch (sort) {
            case "price-asc" -> Sort.by(PRICE_FIELD).ascending();
            case "price-desc" -> Sort.by(PRICE_FIELD).descending();
            case "name-asc" -> Sort.by("name").ascending();
            default -> Sort.by("createdAt").descending();
        };
        // Equal prices/dates must retain a stable order across pages.
        return primary.and(Sort.by("id").ascending());
    }
}
