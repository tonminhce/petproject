package com.shop.gateway.filter;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.shop.common.core.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayErrorResponseWriterTest {

    private final GatewayErrorResponseWriter writer = new GatewayErrorResponseWriter(new ObjectMapper());

    @Test
    void rateLimitEnvelopeMirrorsFleetApiResponseShape() throws Exception {
        var exchange = exchangeFor("/api/v1/backoffice/products", null);

        writer.write(exchange, ErrorCode.TOO_MANY_REQUESTS).block();

        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(429);
        var json = json(exchange);
        assertThat(fieldNames(json)).containsExactlyInAnyOrder(
                "success", "code", "message", "path", "timestamp");
        assertThat(json.get("success").asBoolean()).isFalse();
        assertThat(json.get("code").asText()).isEqualTo("ERR-0429");
        assertThat(json.get("message").asText()).isEqualTo("Too many requests. Please slow down.");
        assertThat(json.get("path").asText()).isEqualTo("/api/v1/backoffice/products");
    }

    @Test
    void accessDeniedEnvelopeMirrorsFleetApiResponseShape() throws Exception {
        var exchange = exchangeFor("/api/v1/backoffice/ratings", null);

        writer.write(exchange, ErrorCode.ACCESS_DENIED).block();

        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(403);
        var json = json(exchange);
        assertThat(fieldNames(json)).containsExactlyInAnyOrder(
                "success", "code", "message", "path", "timestamp");
        assertThat(json.get("success").asBoolean()).isFalse();
        assertThat(json.get("code").asText()).isEqualTo("ERR-0403-A");
        assertThat(json.get("message").asText())
                .isEqualTo("You do not have permission to access this resource.");
        assertThat(json.get("path").asText()).isEqualTo("/api/v1/backoffice/ratings");
    }

    @Test
    void correlationIdHeaderFillsTraceIdField() throws Exception {
        var exchange = exchangeFor("/api/v1/backoffice/ratings", "corr-123");

        writer.write(exchange, ErrorCode.ACCESS_DENIED).block();

        var json = json(exchange);
        assertThat(fieldNames(json)).containsExactlyInAnyOrder(
                "success", "code", "message", "path", "traceId", "timestamp");
        assertThat(json.get("traceId").asText()).isEqualTo("corr-123");
    }

    private JsonNode json(MockServerWebExchange exchange) throws Exception {
        return new ObjectMapper().readTree(exchange.getResponse().getBodyAsString().block());
    }

    private List<String> fieldNames(JsonNode json) {
        return new ArrayList<>(json.propertyNames());
    }

    private MockServerWebExchange exchangeFor(String path, String correlationId) {
        var request = MockServerHttpRequest.get("http://gateway.local" + path);
        if (correlationId != null) {
            request.header("X-Correlation-Id", correlationId);
        }
        return MockServerWebExchange.from(request.build());
    }
}
