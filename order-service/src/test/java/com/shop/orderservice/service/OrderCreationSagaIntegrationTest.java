package com.shop.orderservice.service;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.shop.common.spring.autoconfigure.JpaAuditingAutoConfiguration;
import com.shop.orderservice.config.TestLiquibaseConfig;
import com.shop.orderservice.dto.request.OrderCreateRequest;
import com.shop.orderservice.dto.response.OrderResponse;
import com.shop.orderservice.constant.OrderStatus;
import com.shop.common.core.constants.OutboxStatus;
import com.shop.orderservice.repository.CartItemRepository;
import com.shop.orderservice.repository.CartRepository;
import com.shop.orderservice.repository.OrderItemRepository;
import com.shop.orderservice.repository.OrderRepository;
import com.shop.orderservice.repository.OutboxEventRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
// Kafka comes from the KafkaContainer below (bound via shop.kafka.bootstrap-servers);
// @EmbeddedKafka would spin up a second, unused broker.
@Import({JpaAuditingAutoConfiguration.class, TestLiquibaseConfig.class})
class OrderCreationSagaIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
        .withDatabaseName("order_saga_test").withUsername("test").withPassword("test");

    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
        .withExposedPorts(6379);

    static WireMockServer productServer;
    static WireMockServer inventoryServer;
    static WireMockServer keycloakServer;

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
        r.add("shop.services.tax.enabled", () -> "false");
        r.add("shop.services.promotion.enabled", () -> "false");
        // ⚠️ P0-8 — point Keycloak issuer to WireMock to avoid connection refused at boot
        r.add("shop.security.issuer-uri", () -> "http://localhost:" + keycloakServer.port() + "/realms/test");
        r.add("shop.security.csrf-disabled", () -> "true");
        // P0-7 — ServiceTokenProvider fetches its token from WireMock (the stub
        // returns a dummy JWT from the @BeforeAll stubbing below).
        r.add("shop.services.keycloak.token-url",
            () -> "http://localhost:" + keycloakServer.port() + "/realms/test/protocol/openid-connect/token");
        // Tests call OrderService directly (not via HTTP), so the JWT/security stack is disabled.
        r.add("shop.security.enabled", () -> "false");
    }

    @BeforeAll
    static void startWireMock() {
        productServer = new WireMockServer(0);
        productServer.start();
        inventoryServer = new WireMockServer(0);
        inventoryServer.start();
        // P0-8 — stub Keycloak's OIDC discovery + token endpoints so Boot's JwtDecoder
        // gets a resolvable issuer at boot AND ServiceTokenProvider can exchange for a
        // service token (shop.services.keycloak.token-url points at this WireMock).
        keycloakServer = new WireMockServer(0);
        keycloakServer.start();
        keycloakServer.stubFor(get(urlMatching("/realms/.*/.well-known/openid-configuration"))
            .willReturn(okJson("""
                {"issuer":"http://localhost:%d/realms/test","jwks_uri":"http://localhost:%d/realms/test/protocol/openid-connect/certs"}
                """.formatted(keycloakServer.port(), keycloakServer.port()))));
        keycloakServer.stubFor(post(urlMatching("/realms/.*/protocol/openid-connect/token"))
            .willReturn(okJson("""
                {"access_token":"dummy-jwt","expires_in":3600,"token_type":"Bearer"}
                """)));
    }

    @AfterAll
    static void stopWireMock() {
        productServer.stop();
        inventoryServer.stop();
        keycloakServer.stop();
    }

    @Autowired private OrderService orderService;
    @Autowired private CartRepository cartRepository;
    @Autowired private CartItemRepository cartItemRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private OrderItemRepository orderItemRepository;
    @Autowired private OutboxEventRepository outboxEventRepository;
    @Autowired private com.shop.orderservice.client.ProductServiceClient productServiceClient;

    private UUID userId;
    private UUID productId;

    @BeforeEach
    void setup() {
        userId = UUID.randomUUID();
        productId = UUID.randomUUID();
        // Seed cart with 1 item
        var cart = cartRepository.save(com.shop.orderservice.entity.Cart.builder()
            .userId(userId).subtotal(BigDecimal.ZERO).build());
        cartItemRepository.save(com.shop.orderservice.entity.CartItem.builder()
            .cartId(cart.getId()).productId(productId)
            .productTitle("Test Product").unitPrice(new BigDecimal("100.00")).quantity(2).build());

        productServer.resetAll();
        inventoryServer.resetAll();
    }

    @Test
    void happyPath_createsOrderAndPublishesEvent() {
        // Wire product-service (wrapped in ApiResponse<ProductSnapshot>)
        productServer.stubFor(get(urlEqualTo("/api/v1/products/" + productId))
            .willReturn(okJson("""
                {"success":true,"code":"OK","data":{"id":"%s","title":"Test Product","priceUnit":100.00}}
                """.formatted(productId))));

        // Wire inventory-service (wrapped in ApiResponse<ReservationResponse>)
        UUID reservationId = UUID.randomUUID();
        inventoryServer.stubFor(post(urlEqualTo("/api/v1/inventory/" + productId + "/reserve"))
            .willReturn(okJson("""
                {"success":true,"code":"OK","data":{"reservationId":"%s","productId":"%s","quantity":2}}
                """.formatted(reservationId, productId))));

        // Execute
        OrderResponse response = orderService.createOrder(userId,
            new OrderCreateRequest(null, null), "test-key-1");

        // Verify
        assertThat(response.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(response.total()).isEqualByComparingTo(new BigDecimal("200.00"));
        assertThat(orderRepository.findById(response.id())).isPresent();
        assertThat(cartRepository.findByUserIdAndDeletedFalse(userId)).isEmpty();  // cart cleared

        // Outbox assertion — same TX as order insert; must have ≥1 PENDING event for relay.
        var pendingEvents = outboxEventRepository.findByStatusOrderByIdAsc(
            OutboxStatus.PENDING, PageRequest.of(0, 10));
        assertThat(pendingEvents).isNotEmpty();
        assertThat(pendingEvents.get(0).getEventType()).isEqualTo("order.created.v1");
    }

    @Test
    void reservationFailure_releasesNothingAndThrows() {
        // 2 cart items, only 1 reserves successfully — second fails
        UUID productId2 = UUID.randomUUID();
        var cart = cartRepository.findByUserIdAndDeletedFalse(userId).orElseThrow();
        cartItemRepository.save(com.shop.orderservice.entity.CartItem.builder()
            .cartId(cart.getId()).productId(productId2)
            .productTitle("Test 2").unitPrice(new BigDecimal("50.00")).quantity(1).build());

        productServer.stubFor(get(urlMatching("/api/v1/products/.*"))
            .willReturn(okJson("""
                {"success":true,"code":"OK","data":{"id":"abc","title":"X","priceUnit":10}}
                """)));
        inventoryServer.stubFor(post(urlMatching("/api/v1/inventory/.*/reserve"))
            .willReturn(aResponse().withStatus(409)));

        // Execute + verify
        assertThatThrownBy(() -> orderService.createOrder(userId,
            new OrderCreateRequest(null, null), "test-key-2"))
            .isInstanceOf(com.shop.common.core.exception.BusinessException.class)
            .hasMessageContaining("Failed to reserve stock");

        // No Order created for THIS user (TX rollback)
        assertThat(orderRepository.findAll()
            .stream().filter(o -> o.getUserId().equals(userId)).toList()).isEmpty();
    }

    @Test
    void partialReservationFailure_releasesReservedItems() {
        // 2 cart items — item 1 reserves successfully, item 2 fails. The saga now
        // processes items in productId order (deterministic), so we pin fixed UUIDs:
        // productA sorts first (succeeds), productB second (409).
        // Spec §9 requires the release endpoint to be called for each successful
        // reservation once one fails (compensation invariant).
        UUID productA = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
        UUID productB = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
        var cart = cartRepository.findByUserIdAndDeletedFalse(userId).orElseThrow();
        // Replace the random-product item from @BeforeEach with the two fixed ones.
        cartItemRepository.deleteAll(cartItemRepository.findByCartId(cart.getId()));
        cartItemRepository.save(com.shop.orderservice.entity.CartItem.builder()
            .cartId(cart.getId()).productId(productA)
            .productTitle("Test A").unitPrice(new BigDecimal("100.00")).quantity(2).build());
        cartItemRepository.save(com.shop.orderservice.entity.CartItem.builder()
            .cartId(cart.getId()).productId(productB)
            .productTitle("Test 2").unitPrice(new BigDecimal("50.00")).quantity(1).build());

        productServer.stubFor(get(urlMatching("/api/v1/products/.*"))
            .willReturn(okJson("""
                {"success":true,"code":"OK","data":{"id":"abc","title":"X","priceUnit":10}}
                """)));

        UUID item1ReservationId = UUID.randomUUID();
        inventoryServer.stubFor(post(urlEqualTo("/api/v1/inventory/" + productA + "/reserve"))
            .willReturn(okJson("""
                {"success":true,"code":"OK","data":{"reservationId":"%s","productId":"%s","quantity":2}}
                """.formatted(item1ReservationId, productA))));
        inventoryServer.stubFor(post(urlEqualTo("/api/v1/inventory/" + productB + "/reserve"))
            .willReturn(aResponse().withStatus(409)));
        // The compensation release must reach the inventory API as a real success —
        // the client swallows release failures, so an unstubbed 404 would hide a
        // broken call while the request journal below would still be empty.
        inventoryServer.stubFor(post(urlMatching("/api/v1/inventory/reservations/.*/release"))
            .willReturn(aResponse().withStatus(200)));

        // Execute + verify saga throws
        assertThatThrownBy(() -> orderService.createOrder(userId,
            new OrderCreateRequest(null, null), "partial-fail-key"))
            .isInstanceOf(com.shop.common.core.exception.BusinessException.class)
            .hasMessageContaining("Failed to reserve stock");

        // COMPENSATION VERIFIED — the successful reservation was released after the
        // second one failed. With deterministic ordering, exactly one release of
        // item 1's reservation must have been issued.
        var releaseRequests = inventoryServer.findAll(postRequestedFor(
            urlMatching("/api/v1/inventory/reservations/.*/release")));
        assertThat(releaseRequests).hasSize(1);
        assertThat(releaseRequests.get(0).getUrl())
            .contains(item1ReservationId.toString());

        // No Order persisted (TX rollback)
        assertThat(orderRepository.findAll()
            .stream().filter(o -> o.getUserId().equals(userId)).toList()).isEmpty();
    }

    @Test
    void idempotencyReplay_returnsCachedResponse() {
        productServer.stubFor(get(urlEqualTo("/api/v1/products/" + productId))
            .willReturn(okJson("""
                {"success":true,"code":"OK","data":{"id":"%s","title":"Test","priceUnit":100}}
                """.formatted(productId))));
        inventoryServer.stubFor(post(urlEqualTo("/api/v1/inventory/" + productId + "/reserve"))
            .willReturn(okJson("""
                {"success":true,"code":"OK","data":{"reservationId":"%s","productId":"%s","quantity":2}}
                """.formatted(UUID.randomUUID(), productId))));

        // First call
        OrderResponse first = orderService.createOrder(userId,
            new OrderCreateRequest(null, null), "idem-key-1");

        // Second call with same key — should return cached, NOT re-run saga
        OrderResponse second = orderService.createOrder(userId,
            new OrderCreateRequest(null, null), "idem-key-1");

        assertThat(second.id()).isEqualTo(first.id());
        // Verify product-service called only ONCE (not twice)
        productServer.verify(1, getRequestedFor(urlEqualTo("/api/v1/products/" + productId)));
    }

    @Test
    void productPriceCacheHit_returnsProductSnapshot() {
        // End-to-end guard for review C1: the productPrice cache entry must come
        // back as a ProductSnapshot record. With the pre-fix serializer (no
        // polymorphic typing) the second call below blew up with a
        // ClassCastException on a LinkedHashMap.
        productServer.stubFor(get(urlEqualTo("/api/v1/products/" + productId))
            .willReturn(okJson("""
                {"success":true,"code":"OK","data":{"id":"%s","title":"Cached","priceUnit":42.50}}
                """.formatted(productId))));

        var first = productServiceClient.getProduct(productId);
        var second = productServiceClient.getProduct(productId);  // served from Redis

        assertThat(first.title()).isEqualTo("Cached");
        assertThat(second).isEqualTo(first);
        productServer.verify(1, getRequestedFor(urlEqualTo("/api/v1/products/" + productId)));
    }
}
