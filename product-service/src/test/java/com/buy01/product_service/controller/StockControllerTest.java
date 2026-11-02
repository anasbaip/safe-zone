package com.buy01.product_service.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import com.buy01.product_service.dtos.req.StockRequest;
import com.buy01.product_service.service.StockService;

@ExtendWith(MockitoExtension.class)
class StockControllerTest {

    @Mock
    private StockService stockService;

    @InjectMocks
    private StockController controller;

    private final List<StockRequest.Item> items = List.of(new StockRequest.Item("p1", 2));

    @Test
    void reserve_returnsNoContent() {
        assertThat(controller.reserve(new StockRequest(items)).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(stockService).reserve(items);
    }

    @Test
    void release_returnsNoContent() {
        assertThat(controller.release(new StockRequest(items)).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(stockService).release(items);
    }
}
