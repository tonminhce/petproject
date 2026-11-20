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
import java.time.Duration;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@Import({JpaAuditingAutoConfiguration.class, TestLiquibaseConfig.class})
public abstract class AbstractOrderServiceIT {

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
        .withDatabaseName("order_saga_test").withUsername("test").withPassword("test");

    static final KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0")).withStartupTimeout(Duration.ofMinutes(5));

    @SuppressWarnings("resource")
    static final GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
        .withExposedPorts(6379);

    protected static final WireMockServer productServer = new WireMockServer(0);
    protected static final WireMockServer inventoryServer = new WireMockServer(0);
    protected static final WireMockServer taxServer = new WireMockServer(0);
    protected static final WireMockServer promotionServer = new WireMockServer(0);
    protected static final WireMockServer keycloakServer = new WireMockServer(0);
    protected static final WireMockServer paymentServer = new WireMockServer(0);

    static {
        postgres.start();
        kafka.start();
        redis.start();
        productServer.start();
        inventoryServer.start();
        taxServer.start();
        promotionServer.start();
        paymentServer.start();
        keycloakServer.start();
        keycloakServer.stubFor(get(urlMatching("/realms/.*/.well-known/openid-configuration"))
            .willReturn(okJson("""
                {"issuer":"http://localhost:%d/realms/test","jwks_uri":"http://localhost:%d/realms/test/protocol/openid-connect/certs"}
                """.formatted(keycloakServer.port(), keycloakServer.port()))));
        keycloakServer.stubFor(post(urlMatching("/realms/.*/protocol/openid-connect/token"))
            .willReturn(okJson("""
                {"access_token":"dummy-jwt","expires_in":3600,"token_type":"Bearer"}
                """)));
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
        r.add("shop.services.tax.enabled", () -> "true");
        r.add("shop.services.tax.url", () -> "http://localhost:" + taxServer.port());
        r.add("shop.services.promotion.enabled", () -> "true");
        r.add("shop.services.promotion.url", () -> "http://localhost:" + promotionServer.port());

        r.add("shop.services.payment.enabled", () -> "true");
        r.add("shop.services.payment.url", () -> "http://localhost:" + paymentServer.port());
        r.add("shop.security.issuer-uri", () -> "http://localhost:" + keycloakServer.port() + "/realms/test");
        r.add("shop.security.csrf-disabled", () -> "true");
        r.add("shop.services.keycloak.token-url",
            () -> "http://localhost:" + keycloakServer.port() + "/realms/test/protocol/openid-connect/token");
        r.add("shop.security.enabled", () -> "false");
    }

    @BeforeEach
    void resetDownstreamStubs() {
        productServer.resetAll();
        inventoryServer.resetAll();
        taxServer.resetAll();
        promotionServer.resetAll();
        taxServer.stubFor(post(urlEqualTo("/api/v1/backoffice/tax-rates/calculate"))
            .willReturn(okJson("""
                {"success":true,"code":"OK","data":{"taxAmount":0,"appliedRate":0}}
                """)));
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

    protected static void seedAdminPrincipal(UUID adminId) {
        Jwt jwt = Jwt.withTokenValue("it-token").header("alg", "none")
            .subject(adminId.toString()).claim("preferred_username", "it-admin").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, java.util.List.of()));
    }

    protected static void seedServicePrincipal(String clientId, String serviceAccountSub) {
        Jwt jwt = Jwt.withTokenValue("it-service-token").header("alg", "none")
            .subject(serviceAccountSub).claim("azp", clientId).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt,
            java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_SERVICE"))));
    }
}
