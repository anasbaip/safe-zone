package com.buy01.order_service.repository;

import java.util.Optional;

import org.springframework.data.mongodb.repository.MongoRepository;

import com.buy01.order_service.models.Order;

public interface OrderRepository extends MongoRepository<Order, String> {

    Optional<Order> findByCustomerIdAndIdempotencyKey(String customerId, String idempotencyKey);
}
