package com.buy01.order_service.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

class HttpClientConfigTest {
    @Test
    void infrastructureUsesDirectBuilderAndProductRequestsUseQualifiedBuilder() {
        new ApplicationContextRunner().withUserConfiguration(AppConfig.class, Consumers.class).run(context -> {
            var clients = context.getBean(ClientBuilders.class);
            assertThat(context.getBean(RestClient.Builder.class)).isSameAs(clients.direct());
            assertThat(clients.direct()).isSameAs(context.getBean("restClientBuilder"));
            assertThat(clients.balanced()).isSameAs(context.getBean("loadBalancedRestClientBuilder"));
            assertThat(clients.direct()).isNotSameAs(clients.balanced());
        });
    }

    record ClientBuilders(RestClient.Builder direct, RestClient.Builder balanced) {}

    @Configuration(proxyBeanMethods = false)
    static class Consumers {
        @Bean
        ClientBuilders clients(RestClient.Builder direct, @LoadBalanced RestClient.Builder balanced) {
            return new ClientBuilders(direct, balanced);
        }
    }
}
