package com.buy01.product_service.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import com.buy01.product_service.dtos.req.StockRequest;
import com.buy01.product_service.exception.ConflictException;
import com.buy01.product_service.exception.ResourceNotFoundException;
import com.buy01.product_service.models.Product;
import com.buy01.product_service.repository.ProductRepository;

import lombok.RequiredArgsConstructor;

/**
 * Stock changes requested by Order Service. Each product is decremented with a single
 * conditional update, so two orders can never both take the last unit.
 */
@Service
@RequiredArgsConstructor
public class StockService {

    private final MongoTemplate mongoTemplate;
    private final ProductRepository productRepository;

    /** Takes every item from stock, or none of them: on failure the items already taken are put back. */
    public void reserve(List<StockRequest.Item> items) {
        Map<String, Integer> quantities = merge(items);
        Map<String, Integer> reserved = new LinkedHashMap<>();

        for (Map.Entry<String, Integer> entry : quantities.entrySet()) {
            String productId = entry.getKey();
            int quantity = entry.getValue();
            Query enoughStock = new Query(Criteria.where("id").is(productId).and("quantity").gte(quantity));

            if (mongoTemplate.updateFirst(enoughStock, change(-quantity), Product.class).getModifiedCount() == 0) {
                putBack(reserved);
                Product product = productRepository.findById(productId)
                        .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + productId));
                throw new ConflictException("Not enough stock for " + product.getName() + ": "
                        + product.getQuantity() + " available");
            }
            reserved.put(productId, quantity);
        }
    }

    /** Puts items back in stock, e.g. after a cancelled order. Deleted products are skipped. */
    public void release(List<StockRequest.Item> items) {
        putBack(merge(items));
    }

    private void putBack(Map<String, Integer> quantities) {
        quantities.forEach((productId, quantity) -> mongoTemplate.updateFirst(
                new Query(Criteria.where("id").is(productId)), change(quantity), Product.class));
    }

    private static Update change(int delta) {
        return new Update().inc("quantity", delta).set("updatedAt", Instant.now());
    }

    private static Map<String, Integer> merge(List<StockRequest.Item> items) {
        Map<String, Integer> quantities = new LinkedHashMap<>();
        items.forEach(item -> quantities.merge(item.productId(), item.quantity(), Integer::sum));
        return quantities;
    }
}
