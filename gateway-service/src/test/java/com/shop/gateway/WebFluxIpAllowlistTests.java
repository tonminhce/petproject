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
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;

/**
 * D5 with ADMIN_IP_ALLOWLIST PRESENT (10.0.0.0/8,192.168.0.0/16):
 * non-matching source -> 403 envelope; webhooks and actuator health bypass;
 * first X-Forwarded-For entry is the trusted one.
 */
@SpringBootTest(
        classes = {GatewayServiceApplication.class, WebFluxIpAllowlistTests.WireMockProps.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebFluxIpAllowlistTests {

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
        WIRE_MOCK.resetRequests();
    }

    @Test
    void allowedIpWithAdminTokenProxies() {
        client.get().uri("/api/v1/backoffice/ratings")
                .header(HttpHeaders.AUTHORIZATION, ADMIN_TOKEN)
                .header("X-Forwarded-For", "10.42.0.7")
                .exchange()
                .expectStatus().isOk();

        WIRE_MOCK.verify(1, anyRequestedFor(urlEqualTo("/api/v1/backoffice/ratings")));
    }

    @Test
    void blockedIpGets403EnvelopeWithoutReachingTarget() {
        client.get().uri("/api/v1/backoffice/ratings")
                .header(HttpHeaders.AUTHORIZATION, ADMIN_TOKEN)
                .header("X-Forwarded-For", "8.8.8.8")
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.success").isEqualTo(false)
                .jsonPath("$.code").isEqualTo("ERR-0403-A")
                .jsonPath("$.message").isEqualTo("You do not have permission to access this resource.")
                .jsonPath("$.path").isEqualTo("/api/v1/backoffice/ratings");

        WIRE_MOCK.verify(0, anyRequestedFor(urlMatching("/api/v1/backoffice/.*")));
    }

    @Test
    void firstForwardedEntryIsDecisive() {
        client.get().uri("/api/v1/backoffice/products")
                .header(HttpHeaders.AUTHORIZATION, ADMIN_TOKEN)
                .header("X-Forwarded-For", "8.8.8.8, 10.42.0.7")
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void webhookPathsBypassTheAllowlist() {
        client.post().uri("/api/v1/webhooks/payments/event")
                .header("X-Forwarded-For", "8.8.8.8")
                .exchange()
                .expectStatus().isOk();

        client.post().uri("/api/v1/webhooks/shipping/event")
                .header("X-Forwarded-For", "8.8.8.8")
                .exchange()
                .expectStatus().isOk();

        WIRE_MOCK.verify(1, anyRequestedFor(urlEqualTo("/api/v1/webhooks/payments/event")));
        WIRE_MOCK.verify(1, anyRequestedFor(urlEqualTo("/api/v1/webhooks/shipping/event")));
    }

    @Test
    void actuatorHealthBypassesTheAllowlist() {
        // Liveness probes come from the orchestrator, not an office IP
        client.get().uri("/actuator/health/liveness")
                .header("X-Forwarded-For", "8.8.8.8")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void healthGroupNeverBlockedEvenWithoutForwardedHeader() {
        client.get().uri("/actuator/health")
                .header("X-Forwarded-For", "8.8.8.8")
                .exchange()
                .expectStatus().value(status -> org.assertj.core.api.Assertions.assertThat(status).isNotEqualTo(403));
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
                registry.add("gateway.edge-rate-limit.enabled", () -> "false");
                registry.add("gateway.admin-ip-allowlist.cidrs", () -> "10.0.0.0/8,192.168.0.0/16");
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
