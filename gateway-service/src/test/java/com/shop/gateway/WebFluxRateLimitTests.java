package com.shop.gateway;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.nimbusds.jose.jwk.RSAKey;
import com.shop.gateway.support.TestKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;

/**
 * D4 edge rate-limit matrix against the running gateway: backoffice scope
 * 3/min and search scope 5/min (small limits for a fast burst), other paths
 * unlimited, per-IP buckets, X-RateLimit-Remaining headers, 429 envelope.
 */
@SpringBootTest(
        classes = {GatewayServiceApplication.class, WebFluxRateLimitTests.WireMockProps.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebFluxRateLimitTests {

    private static final RSAKey JWK = TestKeys.rsaKey();

    static final WireMockServer WIRE_MOCK = startWireMock();

    private static WireMockServer startWireMock() {
        final WireMockServer server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        return server;
    }
    private static final String ISSUER = WIRE_MOCK.baseUrl() + "/realms/test";
    private static final String ADMIN_TOKEN = "Bearer " + TestKeys.signedToken(JWK, ISSUER, List.of("ADMIN"));

    static {
        WIRE_MOCK.stubFor(any(urlMatching("/.*")).willReturn(ok("upstream-ok")));
        WIRE_MOCK.stubFor(get(urlEqualTo("/realms/test/.well-known/openid-configuration"))
                .willReturn(okJson(TestKeys.oidcConfigurationJson(ISSUER, WIRE_MOCK.baseUrl() + TestKeys.JWKS_PATH))));
        WIRE_MOCK.stubFor(get(urlEqualTo(TestKeys.JWKS_PATH))
                .willReturn(okJson(TestKeys.jwksJson(JWK))));
    }

    @LocalServerPort
    int port;

    WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Test
    void fourthBackofficeRequestWithinMinuteIs429WithEnvelopeAndZeroRemaining() {
        for (int i = 1; i <= 3; i++) {
            client.get().uri("/api/v1/backoffice/payments")
                    .header(HttpHeaders.AUTHORIZATION, ADMIN_TOKEN)
                    .header("X-Forwarded-For", "203.0.113.7")
                    .exchange()
                    .expectStatus().isOk();
        }

        client.get().uri("/api/v1/backoffice/payments")
                .header(HttpHeaders.AUTHORIZATION, ADMIN_TOKEN)
                .header("X-Forwarded-For", "203.0.113.7")
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectHeader().valueEquals("X-RateLimit-Remaining", "0")
                .expectBody()
                .jsonPath("$.success").isEqualTo(false)
                .jsonPath("$.code").isEqualTo("ERR-0429")
                .jsonPath("$.message").isEqualTo("Too many requests. Please slow down.")
                .jsonPath("$.path").isEqualTo("/api/v1/backoffice/payments");
    }

    @Test
    void passedBackofficeRequestCarriesRemainingHeader() {
        client.get().uri("/api/v1/backoffice/payments")
                .header(HttpHeaders.AUTHORIZATION, ADMIN_TOKEN)
                .header("X-Forwarded-For", "203.0.113.9")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-RateLimit-Remaining", "2");
    }

    @Test
    void searchScopeBurstIsLimitedAtFive() {
        for (int i = 1; i <= 5; i++) {
            client.get().uri("/api/v1/search?q=shoes")
                    .header(HttpHeaders.AUTHORIZATION, ADMIN_TOKEN)
                    .header("X-Forwarded-For", "203.0.113.7")
                    .exchange()
                    .expectStatus().isOk();
        }

        client.get().uri("/api/v1/search?q=shoes")
                .header(HttpHeaders.AUTHORIZATION, ADMIN_TOKEN)
                .header("X-Forwarded-For", "203.0.113.7")
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectHeader().valueEquals("X-RateLimit-Remaining", "0")
                .expectBody()
                .jsonPath("$.code").isEqualTo("ERR-0429");
    }

    @Test
    void nonScopedPathIsUnlimited() {
        for (int i = 1; i <= 6; i++) {
            client.get().uri("/api/v1/orders")
                    .header(HttpHeaders.AUTHORIZATION, ADMIN_TOKEN)
                    .header("X-Forwarded-For", "203.0.113.7")
                    .exchange()
                    .expectStatus().isOk();
        }
    }

    @Test
    void bucketsAreIsolatedPerClientIp() {
        client.get().uri("/api/v1/backoffice/products")
                .header(HttpHeaders.AUTHORIZATION, ADMIN_TOKEN)
                .header("X-Forwarded-For", "203.0.113.11")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-RateLimit-Remaining", "2");

        client.get().uri("/api/v1/backoffice/products")
                .header(HttpHeaders.AUTHORIZATION, ADMIN_TOKEN)
                .header("X-Forwarded-For", "203.0.113.11")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-RateLimit-Remaining", "1");

        // Fresh IP -> its own bucket with a full budget
        client.get().uri("/api/v1/backoffice/products")
                .header(HttpHeaders.AUTHORIZATION, ADMIN_TOKEN)
                .header("X-Forwarded-For", "203.0.113.12")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-RateLimit-Remaining", "2");
    }

    @TestConfiguration
    static class WireMockProps {

        @Bean
        DynamicPropertyRegistrar wireMockProperties() {
            return registry -> {
                registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> ISSUER);
                registry.add("gateway.keycloak-issuer-uri", () -> ISSUER);
                registry.add("gateway.rate-limit.enabled", () -> "false");
                registry.add("gateway.rate-limit.global.enabled", () -> "false");
                registry.add("gateway.edge-rate-limit.backoffice-requests-per-minute", () -> "3");
                registry.add("gateway.edge-rate-limit.search-requests-per-minute", () -> "5");
                Map.<String, java.util.function.Supplier<Object>>of(
                                "gateway.route-targets.overrides.rating-service", WIRE_MOCK::baseUrl,
                                "gateway.route-targets.overrides.product-service", WIRE_MOCK::baseUrl,
                                "gateway.route-targets.overrides.search-service", WIRE_MOCK::baseUrl,
                                "gateway.route-targets.overrides.promotion-service", WIRE_MOCK::baseUrl,
                                "gateway.route-targets.overrides.tax-service", WIRE_MOCK::baseUrl,
                                "gateway.route-targets.overrides.notification-service", WIRE_MOCK::baseUrl,
                                "gateway.route-targets.overrides.payment-service", WIRE_MOCK::baseUrl,
                                "gateway.route-targets.overrides.shipping-service", WIRE_MOCK::baseUrl,
                                "gateway.route-targets.overrides.order-service", WIRE_MOCK::baseUrl)
                        .forEach(registry::add);
            };
        }
    }
}
