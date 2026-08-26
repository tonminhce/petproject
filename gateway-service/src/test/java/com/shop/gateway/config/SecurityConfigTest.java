package com.shop.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityConfigTest {

    @Test
    void corsExposesRateLimitHeadersToBrowserClients() {
        var properties = new GatewayProperties(
                "http://localhost:9999/realms/test",
                List.of("http://localhost:3000"),
                List.of("/api/v1/products"));
        var filter = new SecurityConfig(properties).corsWebFilter();
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get(
                        "http://gateway.local/api/v1/products")
                .header(HttpHeaders.ORIGIN, "http://localhost:3000")
                .build());

        filter.filter(exchange, ignored -> Mono.empty()).block();

        assertThat(exchange.getResponse().getHeaders().getAccessControlExposeHeaders())
                .containsExactlyInAnyOrder(
                        "Authorization",
                        "Content-Type",
                        "X-Request-Id",
                        "X-RateLimit-Remaining",
                        "X-RateLimit-Replenish-Rate",
                        "X-RateLimit-Burst-Capacity",
                        "X-RateLimit-Requested-Tokens");
    }
}
