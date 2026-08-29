package com.shop.orderservice.service;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.shop.common.spring.autoconfigure.JpaAuditingAutoConfiguration;
import com.shop.orderservice.config.TestLiquibaseConfig;
import com.shop.orderservice.dto.request.OrderCreateRequest;
import com.shop.orderservice.dto.response.OrderResponse;
import com.shop.orderservice.entity.OrderStatus;
import com.shop.orderservice.entity.OutboxStatus;
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
import org.springframework.kafka.test.context.EmbeddedKafka;
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
@EmbeddedKafka
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
        // P0-7 — ServiceTokenProvider lấy token từ WireMock (stub trả dummy-jwt ở @BeforeAll)
        r.add("shop.services.keycloak.token-url",
            () -> "http://localhost:" + keycloakServer.port() + "/realms/test/protocol/openid-connect/token");
        // ponytail: tests call OrderService directly (not via HTTP), so disable JWT/security stack entirely
        r.add("shop.security.enabled", () -> "false");
    }

    @BeforeAll
    static void startWireMock() {
        productServer = new WireMockServer(0);
        productServer.start();
        inventoryServer = new WireMockServer(0);
        inventoryServer.start();
        // ⚠️ P0-8 — Stub Keycloak OIDC endpoints so Boot's JwtDecoder doesn't
        // connection-refuse at startup. Đồng thời cấp token cho ServiceTokenProvider
        // (Task 2): shop.services.keycloak.token-url trỏ về keycloakServer (props bên dưới).
        // but BaseSecurityConfig still tries to resolve issuer at boot.
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
        // 2 cart items — item 1 reserves successfully, item 2 fails.
        // Spec §9 requires release endpoint called for each successful reservation
        // when 1 fails (compensation invariant).
        UUID productId2 = UUID.randomUUID();
        var cart = cartRepository.findByUserIdAndDeletedFalse(userId).orElseThrow();
        cartItemRepository.save(com.shop.orderservice.entity.CartItem.builder()
            .cartId(cart.getId()).productId(productId2)
            .productTitle("Test 2").unitPrice(new BigDecimal("50.00")).quantity(1).build());

        productServer.stubFor(get(urlMatching("/api/v1/products/.*"))
            .willReturn(okJson("""
                {"success":true,"code":"OK","data":{"id":"abc","title":"X","priceUnit":10}}
                """)));

        UUID item1ReservationId = UUID.randomUUID();
        inventoryServer.stubFor(post(urlEqualTo("/api/v1/inventory/" + productId + "/reserve"))
            .willReturn(okJson("""
                {"success":true,"code":"OK","data":{"reservationId":"%s","productId":"%s","quantity":2}}
                """.formatted(item1ReservationId, productId))));
        inventoryServer.stubFor(post(urlEqualTo("/api/v1/inventory/" + productId2 + "/reserve"))
            .willReturn(aResponse().withStatus(409)));

        // Execute + verify saga throws
        assertThatThrownBy(() -> orderService.createOrder(userId,
            new OrderCreateRequest(null, null), "partial-fail-key"))
            .isInstanceOf(com.shop.common.core.exception.BusinessException.class)
            .hasMessageContaining("Failed to reserve stock");

        // COMPENSATION VERIFIED — the successful reservation was released after the
        // second one failed. findByCartId has no ORDER BY, so item processing order is
        // DB-determined; both stubs succeed/fail regardless of which item is processed
        // first. We assert the released reservationId matches the one that returned 200.
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
}
