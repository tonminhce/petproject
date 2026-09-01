package com.shop.gateway;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.shop.gateway.support.TestKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full-stack edge matrix (no Docker): the 9 backoffice routes proxy to a
 * WireMock target; no-token -> 401, user-token -> 403 envelope, admin-token
 * -> 200. The IP allowlist env is ABSENT here — D5 semantics say INACTIVE.
 */
@SpringBootTest(
        classes = {GatewayServiceApplication.class, WebFluxRouteTests.WireMockProps.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebFluxRouteTests {

    private static final com.nimbusds.jose.jwk.RSAKey JWK = TestKeys.rsaKey();

    static final WireMockServer WIRE_MOCK = startWireMock();

    private static WireMockServer startWireMock() {
        final WireMockServer server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        return server;
    }
    private static final String ISSUER = WIRE_MOCK.baseUrl() + "/realms/test";
    private static final String ADMIN_TOKEN = "Bearer " + TestKeys.signedToken(JWK, ISSUER, List.of("ADMIN"));
    private static final String USER_TOKEN = "Bearer " + TestKeys.signedToken(JWK, ISSUER, List.of("USER"));

    static {
        WIRE_MOCK.stubFor(any(urlMatching("/.*")).willReturn(ok("upstream-ok")));
        WIRE_MOCK.stubFor(get(urlEqualTo("/realms/test/.well-known/openid-configuration"))
                .willReturn(okJson(TestKeys.oidcConfigurationJson(ISSUER, WIRE_MOCK.baseUrl() + TestKeys.JWKS_PATH))));
        WIRE_MOCK.stubFor(get(urlEqualTo(TestKeys.JWKS_PATH))
                .willReturn(okJson(TestKeys.jwksJson(JWK))));
    }

    @LocalServerPort
    int port;

    @Autowired
    List<GlobalFilter> globalFilters;

    WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(java.time.Duration.ofSeconds(10))
                .build();
        WIRE_MOCK.resetRequests();
    }

    @Test
    void noTokenOnBackofficePathIs401() {
        client.get().uri("/api/v1/backoffice/ratings")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void userTokenOnBackofficePathIs403Envelope() {
        client.get().uri("/api/v1/backoffice/products")
                .header(HttpHeaders.AUTHORIZATION, USER_TOKEN)
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.success").isEqualTo(false)
                .jsonPath("$.code").isEqualTo("ERR-0403-A")
                .jsonPath("$.message").isEqualTo("You do not have permission to access this resource.")
                .jsonPath("$.path").isEqualTo("/api/v1/backoffice/products")
                .jsonPath("$.data").doesNotExist()
                .jsonPath("$.errors").doesNotExist();
    }

    @Test
    void adminTokenProxiesToTargetService() {
        client.get().uri("/api/v1/backoffice/ratings/42")
                .header(HttpHeaders.AUTHORIZATION, ADMIN_TOKEN)
                .exchange()
                .expectStatus().isOk();

        WIRE_MOCK.verify(1, getRequestedFor(urlEqualTo("/api/v1/backoffice/ratings/42")));
    }

    @Test
    void invalidTokenIs401() {
        client.get().uri("/api/v1/backoffice/ratings")
                .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/backoffice/ratings",
            "/api/v1/backoffice/products",
            "/api/v1/backoffice/search",
            "/api/v1/backoffice/promotions",
            "/api/v1/backoffice/tax-classes",
            "/api/v1/backoffice/tax-rates",
            "/api/v1/backoffice/notifications",
            "/api/v1/backoffice/payments",
            "/api/v1/backoffice/shipments",
            "/api/v1/backoffice/medias"})
    void adminTokenIsProxiedForEveryBackofficePrefix(String prefix) {
        client.get().uri(prefix + "/list")
                .header(HttpHeaders.AUTHORIZATION, ADMIN_TOKEN)
                .exchange()
                .expectStatus().isOk();

        WIRE_MOCK.verify(1, getRequestedFor(urlEqualTo(prefix + "/list")));
    }

    @Test
    void ipAllowlistEnvAbsentMeansInactive() {
        // This context runs without ADMIN_IP_ALLOWLIST: a plain (non-forwarded,
        // loopback) request must reach the role gate and pass with ADMIN.
        client.get().uri("/api/v1/backoffice/products/1")
                .header(HttpHeaders.AUTHORIZATION, ADMIN_TOKEN)
                .exchange()
                .expectStatus().isOk();

        WIRE_MOCK.verify(1, anyRequestedFor(urlEqualTo("/api/v1/backoffice/products/1")));
    }

    @Test
    void storefrontRouteStillWorksForAdmin() {
        client.get().uri("/api/v1/orders")
                .header(HttpHeaders.AUTHORIZATION, ADMIN_TOKEN)
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void storefrontMediasRouteProxiesPluralPredicate() {
        client.get().uri("/api/v1/medias/some-media-id")
                .header(HttpHeaders.AUTHORIZATION, ADMIN_TOKEN)
                .exchange()
                .expectStatus().isOk();

        WIRE_MOCK.verify(1, getRequestedFor(urlEqualTo("/api/v1/medias/some-media-id")));
    }

    @Test
    void edgeFiltersAreRegisteredInBindingOrder() {
        var orders = globalFilters.stream()
                .map(filter -> filter.getClass().getSimpleName() + ":" + ((org.springframework.core.Ordered) filter).getOrder())
                .toList();

        var ipOrder = orderOf(orders, "AdminIpAllowlistFilter");
        var rateOrder = orderOf(orders, "RateLimitFilter");
        var roleOrder = orderOf(orders, "AdminRoleGateFilter");

        assertThat(ipOrder).isEqualTo(org.springframework.core.Ordered.HIGHEST_PRECEDENCE);
        assertThat(rateOrder).isEqualTo(org.springframework.core.Ordered.HIGHEST_PRECEDENCE + 10);
        assertThat(roleOrder).isEqualTo(org.springframework.core.Ordered.HIGHEST_PRECEDENCE + 20);
    }

    private int orderOf(List<String> filters, String simpleName) {
        return filters.stream()
                .filter(entry -> entry.startsWith(simpleName + ":"))
                .mapToInt(entry -> Integer.parseInt(entry.substring(entry.indexOf(':') + 1)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("filter missing: " + simpleName + " in " + filters));
    }

    @TestConfiguration
    static class WireMockProps {

        @Bean
        DynamicPropertyRegistrar wireMockProperties() {
            return registry -> {
                registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> ISSUER);
                registry.add("gateway.keycloak-issuer-uri", () -> ISSUER);
                // Redis-backed limiters are out of scope here (no Docker); the
                // bucket4j edge scopes under test stay enabled.
                registry.add("gateway.rate-limit.enabled", () -> "false");
                registry.add("gateway.rate-limit.global.enabled", () -> "false");
                registry.add("gateway.edge-rate-limit.backoffice-requests-per-minute", () -> "100");
                registry.add("gateway.edge-rate-limit.search-requests-per-minute", () -> "60");
                // Point the route table at the WireMock target
                Map.<String, java.util.function.Supplier<Object>>of(
                                "gateway.route-targets.overrides.rating-service", WIRE_MOCK::baseUrl,
                                "gateway.route-targets.overrides.product-service", WIRE_MOCK::baseUrl,
                                "gateway.route-targets.overrides.search-service", WIRE_MOCK::baseUrl,
                                "gateway.route-targets.overrides.promotion-service", WIRE_MOCK::baseUrl,
                                "gateway.route-targets.overrides.tax-service", WIRE_MOCK::baseUrl,
                                "gateway.route-targets.overrides.notification-service", WIRE_MOCK::baseUrl,
                                "gateway.route-targets.overrides.payment-service", WIRE_MOCK::baseUrl,
                                "gateway.route-targets.overrides.shipping-service", WIRE_MOCK::baseUrl,
                                "gateway.route-targets.overrides.order-service", WIRE_MOCK::baseUrl,
                                "gateway.route-targets.overrides.media-service", WIRE_MOCK::baseUrl)
                        .forEach(registry::add);
            };
        }
    }
}
