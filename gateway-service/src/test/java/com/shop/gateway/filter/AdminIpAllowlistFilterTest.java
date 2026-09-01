package com.shop.gateway.filter;

import com.shop.common.core.exception.ErrorCode;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminIpAllowlistFilterTest {

    private static final String FALLBACK_CHAIN_MARKER = "chain-completed";

    private GatewayFilterChain chain;
    private GatewayErrorResponseWriter errorWriter;
    private ClientIpResolver ipResolver;

    @BeforeEach
    void setUp() {
        chain = mock(GatewayFilterChain.class);
        errorWriter = new GatewayErrorResponseWriter(new ObjectMapper());
        ipResolver = new ClientIpResolver();
        when(chain.filter(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            var exchange = invocation.getArgument(0, ServerWebExchange.class);
            exchange.getResponse().setStatusCode(org.springframework.http.HttpStatus.OK);
            return Mono.empty();
        });
    }

    @Test
    void absentAllowlistIsInactiveAndPassesEverythingThrough() {
        var filter = new AdminIpAllowlistFilter(
                new AdminIpAllowlistProperties(List.of()), errorWriter, ipResolver);
        var exchange = exchange("/api/v1/backoffice/products", null);

        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode())
                .isEqualTo(org.springframework.http.HttpStatus.OK);
    }

    @Test
    void presentAllowlistPassesMatchingIp() {
        var filter = new AdminIpAllowlistFilter(
                new AdminIpAllowlistProperties(List.of("203.0.113.0/24")), errorWriter, ipResolver);
        var exchange = exchange("/api/v1/backoffice/products", "203.0.113.7");

        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode())
                .isEqualTo(org.springframework.http.HttpStatus.OK);
    }

    @Test
    void presentAllowlistBlocksNonMatchingIpWith403Envelope() throws Exception {
        var filter = new AdminIpAllowlistFilter(
                new AdminIpAllowlistProperties(List.of("203.0.113.0/24")), errorWriter, ipResolver);
        var exchange = exchange("/api/v1/backoffice/products", "8.8.8.8");

        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode())
                .isEqualTo(org.springframework.http.HttpStatus.FORBIDDEN);
        var json = new ObjectMapper().readTree(exchange.getResponse().getBodyAsString().block());
        assertThat(json.get("success").asBoolean()).isFalse();
        assertThat(json.get("code").asText()).isEqualTo(ErrorCode.ACCESS_DENIED.getCode());
        assertThat(json.get("path").asText()).isEqualTo("/api/v1/backoffice/products");
    }

    @Test
    void firstForwardedEntryIsDecisive() {
        var filter = new AdminIpAllowlistFilter(
                new AdminIpAllowlistProperties(List.of("10.0.0.0/8")), errorWriter, ipResolver);
        var exchange = exchange("/api/v1/backoffice/products", "10.1.2.3, 203.0.113.9");

        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode())
                .isEqualTo(org.springframework.http.HttpStatus.OK);
    }

    @Test
    void webhookPathsBypassAllowlist() {
        var filter = new AdminIpAllowlistFilter(
                new AdminIpAllowlistProperties(List.of("10.0.0.0/8")), errorWriter, ipResolver);
        var payment = exchange("/api/v1/webhooks/payments/callback", null);
        var shipping = exchange("/api/v1/webhooks/shipping/event", null);

        filter.filter(payment, chain).block();
        filter.filter(shipping, chain).block();

        assertThat(payment.getResponse().getStatusCode())
                .isEqualTo(org.springframework.http.HttpStatus.OK);
        assertThat(shipping.getResponse().getStatusCode())
                .isEqualTo(org.springframework.http.HttpStatus.OK);
    }

    @Test
    void actuatorHealthBypassesAllowlist() {
        var filter = new AdminIpAllowlistFilter(
                new AdminIpAllowlistProperties(List.of("10.0.0.0/8")), errorWriter, ipResolver);
        var health = exchange("/actuator/health", null);
        var liveness = exchange("/actuator/health/liveness", null);

        filter.filter(health, chain).block();
        filter.filter(liveness, chain).block();

        assertThat(health.getResponse().getStatusCode())
                .isEqualTo(org.springframework.http.HttpStatus.OK);
        assertThat(liveness.getResponse().getStatusCode())
                .isEqualTo(org.springframework.http.HttpStatus.OK);
    }

    @Test
    void nonAllowedIpOnHealthSubPathIsStillBypassed() {
        var filter = new AdminIpAllowlistFilter(
                new AdminIpAllowlistProperties(List.of("10.0.0.0/8")), errorWriter, ipResolver);
        var prometheus = exchange("/actuator/prometheus", null);

        filter.filter(prometheus, chain).block();

        assertThat(prometheus.getResponse().getStatusCode())
                .isEqualTo(org.springframework.http.HttpStatus.FORBIDDEN);
    }

    @Test
    void singleIpWithoutPrefixIsTreatedAsHostCidr() {
        var filter = new AdminIpAllowlistFilter(
                new AdminIpAllowlistProperties(List.of("198.51.100.7")), errorWriter, ipResolver);

        var allowed = exchange("/api/v1/backoffice/products", "198.51.100.7");
        filter.filter(allowed, chain).block();
        var blocked = exchange("/api/v1/backoffice/products", "198.51.100.8");
        filter.filter(blocked, chain).block();

        assertThat(allowed.getResponse().getStatusCode())
                .isEqualTo(org.springframework.http.HttpStatus.OK);
        assertThat(blocked.getResponse().getStatusCode())
                .isEqualTo(org.springframework.http.HttpStatus.FORBIDDEN);
    }

    @Test
    void unparseableCidrFailsFastAtStartup() {
        assertThatThrownBy(() -> new AdminIpAllowlistFilter(
                new AdminIpAllowlistProperties(List.of("not-an-ip/33")), errorWriter, ipResolver))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void encodedPathIsRejectedWith400EvenForAllowlistedIp() throws Exception {
        var filter = new AdminIpAllowlistFilter(
                new AdminIpAllowlistProperties(List.of("203.0.113.0/24")), errorWriter, ipResolver);
        var exchange = encodedExchange("/api/v1/backoffice/%72atings", "203.0.113.7");

        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode())
                .isEqualTo(org.springframework.http.HttpStatus.BAD_REQUEST);
        var json = new ObjectMapper().readTree(exchange.getResponse().getBodyAsString().block());
        assertThat(json.get("success").asBoolean()).isFalse();
        assertThat(json.get("code").asText()).isEqualTo("ERR-0400");
        assertThat(json.get("path").asText()).isEqualTo("/api/v1/backoffice/%72atings");
    }

    @Test
    void doubleEncodedPathIsRejectedWith400() {
        var filter = new AdminIpAllowlistFilter(
                new AdminIpAllowlistProperties(List.of("203.0.113.0/24")), errorWriter, ipResolver);
        var exchange = encodedExchange("/api/v1/backoffice/%2572atings", "203.0.113.7");

        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode())
                .isEqualTo(org.springframework.http.HttpStatus.BAD_REQUEST);
    }

    @Test
    void encodedWebhookPathNeverReachesTheBypass() {
        var filter = new AdminIpAllowlistFilter(
                new AdminIpAllowlistProperties(List.of("10.0.0.0/8")), errorWriter, ipResolver);
        var exchange = encodedExchange("/api/v1/%77ebhooks/payments/callback", "10.1.2.3");

        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode())
                .isEqualTo(org.springframework.http.HttpStatus.BAD_REQUEST);
    }

    @Test
    void inactiveAllowlistStillPassesEncodedPathsThrough() {
        var filter = new AdminIpAllowlistFilter(
                new AdminIpAllowlistProperties(List.of()), errorWriter, ipResolver);
        var exchange = encodedExchange("/api/v1/backoffice/%72atings", "8.8.8.8");

        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode())
                .isEqualTo(org.springframework.http.HttpStatus.OK);
    }

    @Test
    void filterRunsAtHighestPrecedence() {
        var filter = new AdminIpAllowlistFilter(
                new AdminIpAllowlistProperties(List.of()), errorWriter, ipResolver);

        assertThat(filter.getOrder())
                .isEqualTo(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
                .isEqualTo(FilterOrder.ADMIN_IP_ALLOWLIST);
    }

    private MockServerWebExchange exchange(String path, String forwardedFor) {
        var request = MockServerHttpRequest.get("http://gateway.local" + path)
                .remoteAddress(new InetSocketAddress(101));
        if (forwardedFor != null) {
            request.header("X-Forwarded-For", forwardedFor);
        }
        return MockServerWebExchange.from(request.build());
    }

    private MockServerWebExchange encodedExchange(String rawPath, String forwardedFor) {
        var request = MockServerHttpRequest.method(org.springframework.http.HttpMethod.GET,
                        java.net.URI.create("http://gateway.local" + rawPath))
                .remoteAddress(new InetSocketAddress(101));
        if (forwardedFor != null) {
            request.header("X-Forwarded-For", forwardedFor);
        }
        return MockServerWebExchange.from(request.build());
    }
}
