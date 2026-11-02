package com.buy01.product_service.models;
import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.TextIndexed;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
@Builder
@Document(collection = "products", language = "none")
@CompoundIndex(name = "category_price_id", def = "{'category': 1, 'price': 1, '_id': 1}")
@CompoundIndex(name = "price_id", def = "{'price': 1, '_id': 1}")
@CompoundIndex(name = "created_id", def = "{'createdAt': -1, '_id': 1}")

public class Product {

    @Id
    private String id;

    @TextIndexed
    private String name;

    @TextIndexed
    private String description;

    private String category;

    private double price;

    private String ownerId;
    
    private String[] images;            

    private String[] mediaIds;

    private int quantity;

    private Instant createdAt;

    private Instant updatedAt;
    
}
