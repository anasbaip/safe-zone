package com.buy01.product_service.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.buy01.product_service.dtos.req.StockRequest;
import com.buy01.product_service.service.StockService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Service-to-service endpoints for Order Service. The gateway has no route for /internal/**,
 * so they are only reachable inside the Docker network, never from the browser.
 */
@RestController
@RequestMapping("/internal/stock")
@RequiredArgsConstructor
public class StockController {

    private final StockService stockService;

    @PostMapping("/reserve")
    public ResponseEntity<Void> reserve(@Valid @RequestBody StockRequest request) {
        stockService.reserve(request.items());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/release")
    public ResponseEntity<Void> release(@Valid @RequestBody StockRequest request) {
        stockService.release(request.items());
        return ResponseEntity.noContent().build();
    }
}
