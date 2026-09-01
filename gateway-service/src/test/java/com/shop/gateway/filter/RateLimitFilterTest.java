package com.shop.gateway.filter;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RateLimitFilterTest {

    private final GatewayFilterChain chain = mock(GatewayFilterChain.class);
    private final GatewayErrorResponseWriter errorWriter = new GatewayErrorResponseWriter(new ObjectMapper());
    private final ClientIpResolver ipResolver = new ClientIpResolver();

    @Test
    void backofficeBurstExceedingLimitYields429EnvelopeAndZeroRemainingHeader() throws Exception {
        when(chain.filter(any())).thenAnswer(invocation -> {
            var exchange = invocation.getArgument(0, ServerWebExchange.class);
            exchange.getResponse().setStatusCode(HttpStatus.OK);
            return Mono.empty();
        });
        var filter = new RateLimitFilter(
                new EdgeRateLimitProperties(true, 3, 60), errorWriter, ipResolver);

        MockServerWebExchange last = null;
        for (int i = 0; i < 4; i++) {
            last = exchange("/api/v1/backoffice/products", "203.0.113.7");
            filter.filter(last, chain).block();
        }

        assertThat(last.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(last.getResponse().getHeaders().getFirst(RateLimitFilter.REMAINING_HEADER)).isEqualTo("0");
        var json = new ObjectMapper().readTree(last.getResponse().getBodyAsString().block());
        assertThat(json.get("success").asBoolean()).isFalse();
        assertThat(json.get("code").asText()).isEqualTo("ERR-0429");
        assertThat(json.get("message").asText()).isEqualTo("Too many requests. Please slow down.");
        assertThat(json.get("path").asText()).isEqualTo("/api/v1/backoffice/products");
    }

    @Test
    void passedRequestsCarryRemainingHeader() {
        stubChainOk();
        var filter = new RateLimitFilter(
                new EdgeRateLimitProperties(true, 3, 60), errorWriter, ipResolver);

        var first = exchange("/api/v1/backoffice/products", "203.0.113.7");
        filter.filter(first, chain).block();

        assertThat(first.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getResponse().getHeaders().getFirst(RateLimitFilter.REMAINING_HEADER)).isEqualTo("2");
    }

    @Test
    void searchScopeHasItsOwnBudget() {
        stubChainOk();
        var filter = new RateLimitFilter(
                new EdgeRateLimitProperties(true, 1, 2), errorWriter, ipResolver);

        var first = exchange("/api/v1/search?q=shoes", "203.0.113.7");
        filter.filter(first, chain).block();
        var second = exchange("/api/v1/search?q=shoes", "203.0.113.7");
        filter.filter(second, chain).block();
        var third = exchange("/api/v1/search?q=shoes", "203.0.113.7");
        filter.filter(third, chain).block();

        assertThat(first.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(third.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void nonScopedPathsAreUnlimited() {
        stubChainOk();
        var filter = new RateLimitFilter(
                new EdgeRateLimitProperties(true, 1, 1), errorWriter, ipResolver);

        for (int i = 0; i < 10; i++) {
            var exchange = exchange("/api/v1/products", "203.0.113.7");
            filter.filter(exchange, chain).block();
            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    @Test
    void bucketsAreIsolatedPerIp() {
        stubChainOk();
        var filter = new RateLimitFilter(
                new EdgeRateLimitProperties(true, 1, 1), errorWriter, ipResolver);

        var firstIp = exchange("/api/v1/backoffice/products", "203.0.113.7");
        filter.filter(firstIp, chain).block();
        var secondIp = exchange("/api/v1/backoffice/products", "203.0.113.8");
        filter.filter(secondIp, chain).block();

        assertThat(firstIp.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(secondIp.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void disabledFilterNeverLimits() {
        stubChainOk();
        var filter = new RateLimitFilter(
                new EdgeRateLimitProperties(false, 1, 1), errorWriter, ipResolver);

        for (int i = 0; i < 5; i++) {
            var exchange = exchange("/api/v1/backoffice/products", "203.0.113.7");
            filter.filter(exchange, chain).block();
            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    @Test
    void singleCharEncodedBackofficePathIsMeteredThenRejectedWith400() {
        stubChainOk();
        var filter = new RateLimitFilter(
                new EdgeRateLimitProperties(true, 2, 60), errorWriter, ipResolver);

        var first = encodedExchange("/api/v1/backoffice/%72atings", "203.0.113.7");
        filter.filter(first, chain).block();
        var second = encodedExchange("/api/v1/backoffice/%72atings", "203.0.113.7");
        filter.filter(second, chain).block();
        var third = encodedExchange("/api/v1/backoffice/%72atings", "203.0.113.7");
        filter.filter(third, chain).block();

        assertThat(first.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(second.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(third.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void matrixVariableBackofficePathIsRejectedWith400() throws Exception {
        stubChainOk();
        var filter = new RateLimitFilter(
                new EdgeRateLimitProperties(true, 10, 60), errorWriter, ipResolver);

        var exchange = encodedExchange("/api/v1/backoffice;r=1/ratings", "203.0.113.7");
        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        var json = new ObjectMapper().readTree(exchange.getResponse().getBodyAsString().block());
        assertThat(json.get("success").asBoolean()).isFalse();
        assertThat(json.get("code").asText()).isEqualTo("ERR-0400");
        assertThat(json.get("path").asText()).isEqualTo("/api/v1/backoffice;r=1/ratings");
    }

    @Test
    void doubleEncodedBackofficePathIsRejectedWith400Envelope() throws Exception {
        stubChainOk();
        var filter = new RateLimitFilter(
                new EdgeRateLimitProperties(true, 10, 60), errorWriter, ipResolver);

        var exchange = encodedExchange("/api/v1/backoffice/%2572atings", "203.0.113.7");
        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        var json = new ObjectMapper().readTree(exchange.getResponse().getBodyAsString().block());
        assertThat(json.get("success").asBoolean()).isFalse();
        assertThat(json.get("code").asText()).isEqualTo("ERR-0400");
        assertThat(json.get("path").asText()).isEqualTo("/api/v1/backoffice/%2572atings");
    }

    @Test
    void encodedSearchPathIsMeteredThenRejectedWith400() {
        stubChainOk();
        var filter = new RateLimitFilter(
                new EdgeRateLimitProperties(true, 10, 1), errorWriter, ipResolver);

        var first = encodedExchange("/api/v1/%73earch?q=shoes", "203.0.113.7");
        filter.filter(first, chain).block();
        var second = encodedExchange("/api/v1/%73earch?q=shoes", "203.0.113.7");
        filter.filter(second, chain).block();

        assertThat(first.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(second.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void filterOrderFollowsBindingConstant() {
        var filter = new RateLimitFilter(
                new EdgeRateLimitProperties(true, 10, 60), errorWriter, ipResolver);

        assertThat(filter.getOrder())
                .isEqualTo(FilterOrder.RATE_LIMIT)
                .isEqualTo(org.springframework.core.Ordered.HIGHEST_PRECEDENCE + 10);
    }

    @Test
    void defaultsMirrorBindingTenPerMinuteBackofficeAndSixtySearch() {
        var properties = new EdgeRateLimitProperties(true, 10, 60);

        assertThat(properties.enabled()).isTrue();
        assertThat(properties.backofficeRequestsPerMinute()).isEqualTo(10);
        assertThat(properties.searchRequestsPerMinute()).isEqualTo(60);
    }

    private void stubChainOk() {
        when(chain.filter(any())).thenAnswer(invocation -> {
            var exchange = invocation.getArgument(0, ServerWebExchange.class);
            exchange.getResponse().setStatusCode(HttpStatus.OK);
            return Mono.empty();
        });
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
