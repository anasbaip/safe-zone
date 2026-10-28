package com.buy01.order_service.repository;
import org.springframework.data.mongodb.repository.MongoRepository;
import com.buy01.order_service.models.CheckoutReceipt;
public interface CheckoutReceiptRepository extends MongoRepository<CheckoutReceipt, String> {}
