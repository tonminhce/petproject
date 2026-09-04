package com.shop.paymentservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.common.spring.autoconfigure.JpaAuditingAutoConfiguration;
import com.shop.common.spring.test.TestSecurityConfig;
import com.shop.common.storage.exception.StorageException;
import com.shop.common.storage.service.ObjectStorageService;
import com.shop.common.storage.service.StorageObject;
import com.shop.paymentservice.config.TestLiquibaseConfig;
import com.shop.paymentservice.constant.PaymentStatus;
import com.shop.paymentservice.dto.CreatePaymentRequest;
import com.shop.paymentservice.dto.PaymentResponse;
import com.shop.paymentservice.entity.Payment;
import com.shop.paymentservice.entity.PaymentEvent;
import com.shop.paymentservice.provider.PaymentProvider;
import com.shop.paymentservice.repository.PaymentEventRepository;
import com.shop.paymentservice.repository.PaymentRepository;
import com.shop.paymentservice.service.PaymentService;
import com.stripe.model.PaymentIntent;
import com.stripe.net.Webhook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;

/**
 * C5 Task 5 — full-context Stripe webhook + idempotency IT. DELIBERATELY
 * standalone (own containers, own property set): the shared
 * {@code AbstractIntegrationTest} pins {@code shop.payment.provider=mock} via
 * {@code @DynamicPropertySource}, and the base class's registration would
 * override any subclass re-registration of the same key. This suite needs the
 * REAL provider wiring ({@code provider=stripe} → StripeProvider +
 * payment-stripe health contributor beans present), so it owns its context.
 *
 * <p>NO real Stripe keys, NO network: the Stripe-Signature headers are
 * generated locally with stripe-java's own {@link Webhook.Util} HMAC helper
 * (the exact scheme {@code Webhook.constructEvent} verifies), and the SDK's
 * static entry points are statically mocked where the plan asks for
 * SDK-call counting.</p>
 *
 * <p>SINGLETON container lifecycle mirrors {@code AbstractIntegrationTest}:
 * plain static fields started once, never stopped by the framework — the
 * surefire fork's exit (Ryuk) reaps them.</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({JpaAuditingAutoConfiguration.class, TestSecurityConfig.class, TestLiquibaseConfig.class,
    StripeWebhookIT.FakeStorageConfig.class})
class StripeWebhookIT {

    static final String STRIPE_WHSEC = "whsec_it_stripe_test";
    private static final Duration AWAIT = Duration.ofSeconds(20);

    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("payment_stripe_test")
            .withUsername("test")
            .withPassword("test");

    @SuppressWarnings("resource")
    static final KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

    static {
        postgres.start();
        kafka.start();
    }

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.liquibase.change-log", () -> "classpath:db/changelog/db.changelog-master.yaml");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("shop.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("shop.kafka.consumer.auto-offset-reset", () -> "earliest");
        registry.add("shop.security.issuer-uri", () -> "http://localhost:0/realms/test");
        registry.add("shop.security.csrf-disabled", () -> "true");
        registry.add("shop.security.stateless-session", () -> "true");
        // THE point of this suite: the REAL stripe provider wiring.
        registry.add("shop.payment.provider", () -> "stripe");
        registry.add("shop.payment.webhook.secret", () -> "it-webhook-secret");
        registry.add("shop.payment.stripe.secret-key", () -> "sk_test_it_no_network");
        registry.add("shop.payment.stripe.webhook-secret", () -> STRIPE_WHSEC);
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private PaymentEventRepository eventRepository;

    @Autowired
    private ApplicationContext applicationContext;

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeStorageConfig {

        @Bean
        ObjectStorageService objectStorageService() {
            return new InMemoryObjectStorageService();
        }
    }

    /** Same posture as AbstractIntegrationTest — no S3/RustFS is ever contacted. */
    static final class InMemoryObjectStorageService implements ObjectStorageService {

        private final Map<String, byte[]> objects = new ConcurrentHashMap<>();

        private static String qualifiedKey(String bucket, String key) {
            return bucket + "/" + key;
        }

        @Override
        public String defaultBucket() {
            return "payment-stripe-test";
        }

        @Override
        public void ensureBucketExists(String bucket) {
        }

        @Override
        public String upload(String key, byte[] content, String contentType) {
            objects.put(qualifiedKey(defaultBucket(), key), content);
            return key;
        }

        @Override
        public String upload(String bucket, String key, InputStream content, long contentLength, String contentType) {
            try (InputStream in = content) {
                objects.put(qualifiedKey(bucket, key), in.readAllBytes());
            } catch (IOException e) {
                throw new StorageException("fake upload failed for " + key, e);
            }
            return key;
        }

        @Override
        public StorageObject download(String key) {
            return download(defaultBucket(), key);
        }

        @Override
        public StorageObject download(String bucket, String key) {
            byte[] content = objects.get(qualifiedKey(bucket, key));
            if (content == null) {
                throw new StorageException("object not found: " + key);
            }
            return StorageObject.of(new ByteArrayInputStream(content), key, null, content.length);
        }

        @Override
        public boolean exists(String key) {
            return objects.containsKey(qualifiedKey(defaultBucket(), key));
        }

        @Override
        public void delete(String key) {
            objects.remove(qualifiedKey(defaultBucket(), key));
        }

        @Override
        public void delete(String bucket, String key) {
            objects.remove(qualifiedKey(bucket, key));
        }

        @Override
        public URL presignedGetUrl(String key, Duration ttl) {
            return presignedUrl(key);
        }

        @Override
        public URL presignedGetUrl(String bucket, String key, Duration ttl) {
            return presignedUrl(key);
        }

        @Override
        public URL presignedPutUrl(String key, String contentType, Duration ttl) {
            return presignedUrl(key);
        }

        private static URL presignedUrl(String key) {
            try {
                return new URL("http://object-storage.test/" + key);
            } catch (IOException e) {
                throw new StorageException("fake presign failed for " + key, e);
            }
        }
    }

    private Payment createPendingPayment() {
        PaymentResponse response = paymentService.create(new CreatePaymentRequest(
                UUID.randomUUID(), new BigDecimal("98.00"), "USD", "it-stripe-" + UUID.randomUUID()));
        return paymentRepository.findById(response.id()).orElseThrow();
    }

    private byte[] stripeSucceededBody(String eventId, String paymentId) {
        return """
                {
                  "id": "%s",
                  "object": "event",
                  "type": "payment_intent.succeeded",
                  "data": {
                    "object": {
                      "object": "payment_intent",
                      "id": "pi_it_1",
                      "amount": %s,
                      "currency": "usd",
                      "status": "succeeded",
                      "metadata": {"payment_id": "%s"}
                    }
                  }
                }
                """.formatted(eventId, "9800", paymentId).getBytes(StandardCharsets.UTF_8);
    }

    private String signedStripeHeader(byte[] rawBody, String secret) {
        long t = Webhook.Util.getTimeNow();
        String signedPayload = t + "." + new String(rawBody, StandardCharsets.UTF_8);
        try {
            return "t=" + t + ",v1=" + Webhook.Util.computeHmacSha256(secret, signedPayload);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private ResponseEntity<String> postStripeWebhook(byte[] rawBody, String signatureHeader) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (signatureHeader != null) {
            headers.set("Stripe-Signature", signatureHeader);
        }
        return restTemplate.postForEntity("/api/v1/webhooks/payments/stripe",
                new HttpEntity<>(new String(rawBody, StandardCharsets.UTF_8), headers), String.class);
    }

    private Payment reload(UUID id) {
        return paymentRepository.findById(id).orElseThrow();
    }

    private void awaitStatus(UUID id, PaymentStatus status) {
        await().atMost(AWAIT).untilAsserted(() ->
                assertThat(reload(id).getStatus()).isEqualTo(status));
    }

    @Test
    @Order(1)
    @DisplayName("1. signed payment_intent.succeeded → 200, payment CAPTURED, event PROCESSED")
    void signedStripeWebhookCapturesPayment() {
        Payment payment = createPendingPayment();
        byte[] raw = stripeSucceededBody("evt_it_succeeded", payment.getId().toString());

        ResponseEntity<String> response = postStripeWebhook(raw, signedStripeHeader(raw, STRIPE_WHSEC));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        awaitStatus(payment.getId(), PaymentStatus.CAPTURED);
        PaymentEvent stored = eventRepository
                .findFirstByProviderAndProviderEventId("stripe", "evt_it_succeeded")
                .orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(PaymentEvent.STATUS_PROCESSED);
        assertThat(stored.getType()).isEqualTo("payment_intent.succeeded");
    }

    @Test
    @Order(2)
    @DisplayName("2. tampered payload (JSON-corrupting) under valid-looking signature → 401 PAY-5005, no state change")
    void tamperedPayloadIsRejectedBeforeAnyRowIsWritten() {
        Payment payment = createPendingPayment();
        byte[] signed = stripeSucceededBody("evt_it_tamper", payment.getId().toString());
        byte[] tampered = stripeSucceededBody("evt_it_tamper", payment.getId().toString());
        tampered[tampered.length - 4] = '9'; // corrupts the amount AND the JSON structure

        ResponseEntity<String> response = postStripeWebhook(tampered, signedStripeHeader(signed, STRIPE_WHSEC));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).contains("PAY-5005");
        assertThat(reload(payment.getId()).getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(eventRepository.findFirstByProviderAndProviderEventId("stripe", "evt_it_tamper"))
                .isEmpty();
    }

    @Test
    @Order(3)
    @DisplayName("3. replay of the same Stripe event id → 200 ack no-op (dedupe)")
    void replayedEventIsDedupedNoOp() {
        Payment payment = createPendingPayment();
        byte[] raw = stripeSucceededBody("evt_it_replay", payment.getId().toString());

        ResponseEntity<String> first = postStripeWebhook(raw, signedStripeHeader(raw, STRIPE_WHSEC));
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        awaitStatus(payment.getId(), PaymentStatus.CAPTURED);

        ResponseEntity<String> replay = postStripeWebhook(raw, signedStripeHeader(raw, STRIPE_WHSEC));

        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(eventRepository.findAll()).filteredOn(
                e -> "stripe".equals(e.getProvider()) && "evt_it_replay".equals(e.getProviderEventId()))
                .hasSize(1);
        assertThat(reload(payment.getId()).getStatus()).isEqualTo(PaymentStatus.CAPTURED);
    }

    @Test
    @Order(4)
    @DisplayName("4. idempotent create: duplicate key returns the SAME row; Stripe SDK sees no extra calls")
    void duplicateIdempotencyKeyReturnsSameRowWithoutExtraSdkCalls() {
        String idempotencyKey = "it-stripe-dup-" + UUID.randomUUID();

        try (MockedStatic<PaymentIntent> paymentIntentStatic = mockStatic(PaymentIntent.class)) {
            PaymentIntent intent = new PaymentIntent();
            intent.setId("pi_it_dup");
            intent.setStatus("succeeded");
            paymentIntentStatic.when(() -> PaymentIntent.create(
                    any(com.stripe.param.PaymentIntentCreateParams.class), any(com.stripe.net.RequestOptions.class)))
                    .thenReturn(intent);

            // Duplicate idempotency-key create — same contract as POST
            // /api/v1/payments, both funnel into PaymentServiceImpl.create
            // (fleet ITs are service-layer and skip the filter chain).
            CreatePaymentRequest request = new CreatePaymentRequest(
                    UUID.randomUUID(), new BigDecimal("98.00"), "USD", idempotencyKey);
            PaymentResponse first = paymentService.create(request);
            PaymentResponse second = paymentService.create(request);

            assertThat(second.id()).isEqualTo(first.id());
            assertThat(paymentRepository.findAll()).filteredOn(
                    p -> idempotencyKey.equals(p.getIdempotencyKey())).hasSize(1);
            // Create never calls the provider — zero SDK calls so far.
            paymentIntentStatic.verify(() -> PaymentIntent.create(
                    any(com.stripe.param.PaymentIntentCreateParams.class),
                    any(com.stripe.net.RequestOptions.class)), times(0));

            // One capture → exactly ONE PaymentIntent.create carrying the
            // payments.idempotency_key (spec D2).
            paymentService.capture(first.id());
            paymentIntentStatic.verify(() -> PaymentIntent.create(
                    any(com.stripe.param.PaymentIntentCreateParams.class),
                    any(com.stripe.net.RequestOptions.class)), times(1));
        }
    }

    @Test
    @Order(5)
    @DisplayName("5. provider=stripe context wires StripeProvider + payment-stripe health contributor")
    void stripeWiringIsPresentInContext() {
        assertThat(applicationContext.getBean("stripeProvider")).isInstanceOf(PaymentProvider.class);
        assertThat(applicationContext.containsBean("payment-stripeHealthIndicator")).isTrue();
        assertThat(applicationContext.getBean(PaymentProvider.class).name()).isEqualTo("stripe");
    }
}
