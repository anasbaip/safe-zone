package com.buy01.order_service.repository;
import org.springframework.data.mongodb.repository.MongoRepository;
import com.buy01.order_service.models.Cart;
public interface CartRepository extends MongoRepository<Cart, String> {}
