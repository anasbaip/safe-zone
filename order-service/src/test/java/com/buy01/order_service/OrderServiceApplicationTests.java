package com.buy01.order_service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.buy01.order_service.service.CartService;
import com.buy01.order_service.service.OrderService;
import com.buy01.order_service.service.OrderServiceImpl;

@SpringBootTest(properties = {
        "spring.data.mongodb.auto-index-creation=false",
        "eureka.client.enabled=false"})
class OrderServiceApplicationTests {

    @Autowired
    private OrderService orderService;

    @Autowired
    private CartService cartService;

    @Test
    void contextLoadsWithRealOrdersAndCartServices() {
        assertThat(orderService).isInstanceOf(OrderServiceImpl.class);
        assertThat(cartService).isNotNull();
    }
}
