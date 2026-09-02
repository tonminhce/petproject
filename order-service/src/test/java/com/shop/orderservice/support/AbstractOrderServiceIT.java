package com.shop.orderservice.support;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.shop.common.spring.autoconfigure.JpaAuditingAutoConfiguration;
import com.shop.orderservice.client.ProductServiceClient;
import com.shop.orderservice.config.TestLiquibaseConfig;
import com.shop.orderservice.repository.CartItemRepository;
import com.shop.orderservice.repository.CartRepository;
import com.shop.orderservice.repository.IdempotencyKeyRepository;
import com.shop.orderservice.repository.OrderItemRepository;
import com.shop.orderservice.repository.OrderRepository;
import com.shop.orderservice.repository.OutboxEventRepository;
import com.shop.orderservice.service.OrderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;

/**
 * Shared harness for order-service full-context ITs (factored from
 * {@code OrderCreationSagaIntegrationTest}, task 13): Testcontainers PG + Kafka +
 * Redis, five WireMock downstreams (product, inventory, TAX, promotion, keycloak)
 * and the Keycloak token/discovery overrides.
 *
 * <p>SINGLETON LIFECYCLE — deliberate divergence from the per-class
 * {@code @Container} style this harness was factored from: every subclass has an
 * identical context-cache key (annotations + the inherited
 * {@code @DynamicPropertySource} method), so JUnit boots ONE cached Spring
 * context for all of them. Per-class container management would stop the
 * containers after the first subclass finished, leaving the cached context
 * pointing at dead mapped ports (observed in the task-13 full-suite run: second
 * IT class died with {@code HikariPool ... Connection refused}). Containers and
 * WireMock servers are therefore started once per JVM in a static initializer
 * and never stopped by the extension — the surefire fork's exit (plus Ryuk)
 * reaps them.</p>
 *
 * <p>Tax is ENABLED suite-wide via a default zero-rate stub re-applied after every
 * {@code resetAll()}: individual tests that need a different tax outcome simply
 * register their own stub afterwards (WireMock resolves stub collisions in favour
 * of the most recently added match). Tests that never create orders never reach
 * the tax client.</p>
 *
 * <p>Security is disabled ({@code shop.security.enabled=false}) so the custom
 * chain backs off — but Spring Boot's FALLBACK filter chain (form login + CSRF)
 * then becomes active and would 403 every MockMvc POST; MockMvc therefore runs
 * with {@code addFilters = false} (mirrors ConfirmOrchestrationWebMvcTest), and
 * endpoints called through it must seed the actor via {@link #seedAdminPrincipal}
 * (MockMvc runs on the calling thread, which is exactly what
 * {@code AuthenticatedUser.requireCurrent()} reads).</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
// Kafka comes from the KafkaContainer below (bound via shop.kafka.bootstrap-servers);
// @EmbeddedKafka would spin up a second, unused broker.
@Import({JpaAuditingAutoConfiguration.class, TestLiquibaseConfig.class})
public abstract class AbstractOrderServiceIT {

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
        .withDatabaseName("order_saga_test").withUsername("test").withPassword("test");

    static final KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

    @SuppressWarnings("resource")
    static final GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
        .withExposedPorts(6379);

    protected static final WireMockServer productServer = new WireMockServer(0);
    protected static final WireMockServer inventoryServer = new WireMockServer(0);
    protected static final WireMockServer taxServer = new WireMockServer(0);
    protected static final WireMockServer promotionServer = new WireMockServer(0);
    protected static final WireMockServer keycloakServer = new WireMockServer(0);

    static {
        postgres.start();
        kafka.start();
        redis.start();
        productServer.start();
        inventoryServer.start();
        taxServer.start();
        promotionServer.start();
        // P0-8 — stub Keycloak's OIDC discovery + token endpoints so Boot's JwtDecoder
        // gets a resolvable issuer at boot AND ServiceTokenProvider can exchange for a
        // service token (shop.services.keycloak.token-url points at this WireMock).
        keycloakServer.start();
        keycloakServer.stubFor(get(urlMatching("/realms/.*/.well-known/openid-configuration"))
            .willReturn(okJson("""
                {"issuer":"http://localhost:%d/realms/test","jwks_uri":"http://localhost:%d/realms/test/protocol/openid-connect/certs"}
                """.formatted(keycloakServer.port(), keycloakServer.port()))));
        keycloakServer.stubFor(post(urlMatching("/realms/.*/protocol/openid-connect/token"))
            .willReturn(okJson("""
                {"access_token":"dummy-jwt","expires_in":3600,"token_type":"Bearer"}
                """)));
        // No explicit stop: the surefire fork's JVM exit (Ryuk) reaps containers;
        // stopping per-class would break the shared cached context (see class javadoc).
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            postgres.stop();
            kafka.stop();
            redis.stop();
        }));
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("spring.liquibase.change-log", () -> "classpath:db/changelog/db.changelog-master.yaml");
        r.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        r.add("shop.kafka.bootstrap-servers", kafka::getBootstrapServers);
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        r.add("shop.services.product.url", () -> "http://localhost:" + productServer.port());
        r.add("shop.services.inventory.url", () -> "http://localhost:" + inventoryServer.port());
        // Task 13 — tax enabled suite-wide: the create-saga calls the tax client on
        // every pricing pass, and the T7 regression case needs a tax failure stub.
        // Default zero-rate stub (below) keeps totals identical to the disabled case.
        r.add("shop.services.tax.enabled", () -> "true");
        r.add("shop.services.tax.url", () -> "http://localhost:" + taxServer.port());
        // Task 7 — promotion reserve is part of the saga now: enabled + WireMock-backed.
        // Coupon-less tests never reach reserve (P1-5 only triggers with a coupon), so
        // this changes nothing for them.
        r.add("shop.services.promotion.enabled", () -> "true");
        r.add("shop.services.promotion.url", () -> "http://localhost:" + promotionServer.port());
        // ⚠️ P0-8 — point Keycloak issuer to WireMock to avoid connection refused at boot
        r.add("shop.security.issuer-uri", () -> "http://localhost:" + keycloakServer.port() + "/realms/test");
        r.add("shop.security.csrf-disabled", () -> "true");
        // P0-7 — ServiceTokenProvider fetches its token from WireMock (the stub
        // returns a dummy JWT from the static block above).
        r.add("shop.services.keycloak.token-url",
            () -> "http://localhost:" + keycloakServer.port() + "/realms/test/protocol/openid-connect/token");
        // Tests calling OrderService directly skip the JWT/security stack entirely.
        r.add("shop.security.enabled", () -> "false");
    }

    @BeforeEach
    void resetDownstreamStubs() {
        productServer.resetAll();
        inventoryServer.resetAll();
        taxServer.resetAll();
        promotionServer.resetAll();
        // Suite-wide tax default: zero-rate success. Tests re-stub tax with their own
        // (later-registered) stub when they need a failure or a non-zero rate.
        taxServer.stubFor(post(urlEqualTo("/api/v1/backoffice/tax-rates/calculate"))
            .willReturn(okJson("""
                {"success":true,"code":"OK","data":{"taxAmount":0,"appliedRate":0}}
                """)));
        // Deterministic Redis cache state per test (defense-in-depth against
        // cross-test/cross-class pollution — e.g. ConfirmOrchestrationIT reuses
        // class-fixed product ids; with immediate cache writes each clear is
        // synchronous, so it is visible before the test body runs).
        var cache = cacheManager.getCache("productPrice");
        if (cache != null) cache.clear();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Autowired protected OrderService orderService;
    @Autowired protected MockMvc mockMvc;
    @Autowired protected CartRepository cartRepository;
    @Autowired protected CartItemRepository cartItemRepository;
    @Autowired protected OrderRepository orderRepository;
    @Autowired protected OrderItemRepository orderItemRepository;
    @Autowired protected OutboxEventRepository outboxEventRepository;
    @Autowired protected IdempotencyKeyRepository idempotencyKeyRepository;
    @Autowired protected ProductServiceClient productServiceClient;
    @Autowired
    private CacheManager cacheManager;

    /**
     * Seeds a SERVICE/ADMIN-grade principal (JWT subject = adminId) for MockMvc calls —
     * with security disabled there is no filter chain to authenticate, and
     * {@code OrderStatusController} resolves the actor via
     * {@code AuthenticatedUser.requireCurrent()} on the calling thread.
     */
    protected static void seedAdminPrincipal(UUID adminId) {
        Jwt jwt = Jwt.withTokenValue("it-token").header("alg", "none")
            .subject(adminId.toString()).claim("preferred_username", "it-admin").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, java.util.List.of()));
    }

    /**
     * Seeds a KC26 machine-token-shaped principal (sub = service-account UUID,
     * {@code azp} = client id, ROLE_SERVICE authority) — the H-6 SERVICE shape:
     * {@code OrderStatusController} must resolve the actor to
     * {@code service:<azp>}, never the service-account UUID.
     */
    protected static void seedServicePrincipal(String clientId, String serviceAccountSub) {
        Jwt jwt = Jwt.withTokenValue("it-service-token").header("alg", "none")
            .subject(serviceAccountSub).claim("azp", clientId).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt,
            java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_SERVICE"))));
    }
}
