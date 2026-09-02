package com.shop.paymentservice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.common.storage.service.ObjectStorageService;
import com.shop.paymentservice.constant.PaymentStatus;
import com.shop.paymentservice.dto.CreatePaymentRequest;
import com.shop.paymentservice.entity.Payment;
import com.shop.paymentservice.entity.PaymentEvent;
import com.shop.paymentservice.repository.PaymentEventRepository;
import com.shop.paymentservice.repository.PaymentRepository;
import com.shop.paymentservice.service.PaymentService;
import com.shop.paymentservice.support.AbstractIntegrationTest;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.context.annotation.Bean;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureTestRestTemplate
class PaymentFlowIT extends AbstractIntegrationTest {

    private static final String SECRET = "it-webhook-secret";
    private static final String PROVIDER = "mock";
    private static final String TOPIC = "shop.payment.lifecycle.v1";
    private static final String RECORDED_EVENT_CAPTURED = "payment.captured.v1";
    private static final String RECORDED_EVENT_REFUNDED = "payment.refunded.v1";
    private static final java.time.Duration AWAIT = java.time.Duration.ofSeconds(20);

    static final List<String> RECORDED = new CopyOnWriteArrayList<>();

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private PaymentEventRepository eventRepository;

    @Autowired
    private ObjectStorageService objectStorage;

    @Autowired
    private ObjectMapper objectMapper;

    @TestConfiguration(proxyBeanMethods = false)
    @EnableKafka
    static class RecorderConfig {

        @Bean
        ConcurrentKafkaListenerContainerFactory<String, String> paymentItRecorderFactory(
                @Value("${shop.kafka.bootstrap-servers}") String bootstrapServers) {
            Map<String, Object> props = new HashMap<>();
            props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
            props.put(ConsumerConfig.GROUP_ID_CONFIG, "payment-it-recorder");
            props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
            props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
            props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
            ConcurrentKafkaListenerContainerFactory<String, String> factory =
                    new ConcurrentKafkaListenerContainerFactory<>();
            factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(props));
            return factory;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RecorderListener {

        @KafkaListener(topics = TOPIC, groupId = "payment-it-recorder",
                containerFactory = "paymentItRecorderFactory")
        void record(String payload) {
            RECORDED.add(payload);
        }
    }

    @Test
    @Order(1)
    @DisplayName("1. create → capture → signed CAPTURED webhook → CAPTURED, previousStatus PENDING, receipt_key set")
    void capturedWebhookDrivesStateAndReceipt() {
        Payment payment = createCapturedPendingPayment();

        ResponseEntity<String> response = postSignedWebhook(capturedBody(payment));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        await().atMost(AWAIT).untilAsserted(() -> {
            Payment row = reload(payment.getId());
            assertThat(row.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
            assertThat(row.getPreviousStatus()).isEqualTo(PaymentStatus.PENDING);
            assertThat(row.getReceiptKey()).isEqualTo("receipts/" + payment.getId() + ".json");
        });
        assertThat(objectStorage.exists("receipts/" + payment.getId() + ".json")).isTrue();
    }

    @Test
    @Order(2)
    @DisplayName("2. replay same webhook → still 1 event row, state unchanged")
    void replayedWebhookIsDeduplicated() {
        Payment payment = createCapturedPendingPayment();
        String body = capturedBody(payment);
        postSignedWebhook(body);
        awaitStatus(payment.getId(), PaymentStatus.CAPTURED);
        assertThat(eventsFor(payment.getId())).hasSize(1);

        ResponseEntity<String> replay = postSignedWebhook(body);

        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(eventsFor(payment.getId())).hasSize(1);
        Payment row = reload(payment.getId());
        assertThat(row.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(row.getPreviousStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    @Order(3)
    @DisplayName("3. bad signature → 401, state unchanged, zero new event rows")
    void invalidSignatureIsRejectedBeforeAnyRowIsWritten() {
        Payment payment = createCapturedPendingPayment();
        String body = capturedBody(payment);

        ResponseEntity<String> response = postWebhook(body, sign("not-the-secret", body));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).contains("PAY-5005");
        assertThat(eventsFor(payment.getId())).isEmpty();
        assertThat(reload(payment.getId()).getStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    @Order(4)
    @DisplayName("4. REFUNDED webhook on CAPTURED → REFUNDED + payment.refunded.v1 recorded")
    void refundedWebhookTransitionsCapturedToRefunded() {
        Payment payment = createCapturedPendingPayment();
        postSignedWebhook(capturedBody(payment));
        awaitStatus(payment.getId(), PaymentStatus.CAPTURED);

        ResponseEntity<String> response = postSignedWebhook(refundedBody(payment));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        awaitStatus(payment.getId(), PaymentStatus.REFUNDED);
        await().atMost(AWAIT).untilAsserted(() -> assertThat(
                        recordedPayloadsFor(payment.getId(), RECORDED_EVENT_REFUNDED))
                .hasSize(1));
        assertThat(reload(payment.getId()).getPreviousStatus()).isEqualTo(PaymentStatus.CAPTURED);
    }

    @Test
    @Order(5)
    @DisplayName("5. amount-mismatch webhook → state unchanged, event row FAILED_RETRYABLE (C3)")
    void amountMismatchLeavesStateAndMarksEventFailed() {
        Payment payment = createCapturedPendingPayment();
        String body = webhookBody(payment, "CAPTURED", new BigDecimal("99.00"), UUID.randomUUID().toString());

        ResponseEntity<String> response = postSignedWebhook(body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        await().atMost(AWAIT).untilAsserted(() -> {
            List<PaymentEvent> events = eventsFor(payment.getId());
            assertThat(events).hasSize(1);
            assertThat(events.get(0).getStatus()).isEqualTo(PaymentEvent.STATUS_FAILED_RETRYABLE);
        });
        Payment row = reload(payment.getId());
        assertThat(row.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(row.getPreviousStatus()).isNull();
    }

    @Test
    @Order(6)
    @DisplayName("6. recorder sees payment.captured.v1 then payment.refunded.v1, eventId UUID, previousStatus non-null")
    void recorderObservesCapturedThenRefundedInOrder() throws Exception {
        Payment payment = createCapturedPendingPayment();
        postSignedWebhook(capturedBody(payment));
        awaitStatus(payment.getId(), PaymentStatus.CAPTURED);
        postSignedWebhook(refundedBody(payment));
        awaitStatus(payment.getId(), PaymentStatus.REFUNDED);

        await().atMost(AWAIT).untilAsserted(() -> {
            assertThat(recordedPayloadsFor(payment.getId(), RECORDED_EVENT_CAPTURED)).hasSize(1);
            assertThat(recordedPayloadsFor(payment.getId(), RECORDED_EVENT_REFUNDED)).hasSize(1);
        });
        int capturedAt = recordedIndex(payment.getId(), RECORDED_EVENT_CAPTURED);
        int refundedAt = recordedIndex(payment.getId(), RECORDED_EVENT_REFUNDED);
        assertThat(capturedAt).isLessThan(refundedAt);

        JsonNode captured = parseRecordedPayload(RECORDED.get(capturedAt));
        JsonNode refunded = parseRecordedPayload(RECORDED.get(refundedAt));
        for (JsonNode node : List.of(captured, refunded)) {
            String eventId = node.get("eventId").asText();
            assertThatCode(() -> UUID.fromString(eventId)).doesNotThrowAnyException();
            assertThat(node.hasNonNull("previousStatus")).isTrue();
            assertThat(node.get("previousStatus").asText()).isNotBlank();
        }
        assertThat(captured.get("previousStatus").asText()).isEqualTo("PENDING");
        assertThat(refunded.get("previousStatus").asText()).isEqualTo("CAPTURED");
    }

    @Test
    @Order(7)
    @DisplayName("7. garbage JSON with VALID signature → 200 ack + FAILED_RETRYABLE event, endpoint still processes")
    void poisonedPayloadIsAckedAndEndpointSurvives() {
        String garbage = "{ this is not json ";
        ResponseEntity<String> poisoned = postSignedWebhook(garbage);

        assertThat(poisoned.getStatusCode()).isEqualTo(HttpStatus.OK);
        await().atMost(AWAIT).untilAsserted(() -> {
            List<PaymentEvent> failed = eventRepository.findAll().stream()
                    .filter(e -> garbage.equals(e.getPayload()))
                    .toList();
            assertThat(failed).hasSize(1);
            assertThat(failed.get(0).getStatus()).isEqualTo(PaymentEvent.STATUS_FAILED_RETRYABLE);
            assertThat(failed.get(0).getProviderEventId()).startsWith("unparseable-");
        });

        Payment payment = createCapturedPendingPayment();
        ResponseEntity<String> after = postSignedWebhook(capturedBody(payment));

        assertThat(after.getStatusCode()).isEqualTo(HttpStatus.OK);
        awaitStatus(payment.getId(), PaymentStatus.CAPTURED);
    }

    private Payment createCapturedPendingPayment() {
        Payment payment = paymentService.create(new CreatePaymentRequest(
                UUID.randomUUID(), new BigDecimal("100.00"), "USD", UUID.randomUUID().toString()));
        paymentService.capture(payment.getId());
        return paymentRepository.findById(payment.getId()).orElseThrow();
    }

    private String capturedBody(Payment payment) {
        return webhookBody(payment, "CAPTURED", payment.getAmount(), UUID.randomUUID().toString());
    }

    private String refundedBody(Payment payment) {
        return webhookBody(payment, "REFUNDED", payment.getAmount(), UUID.randomUUID().toString());
    }

    private String webhookBody(Payment payment, String status, BigDecimal amount, String providerEventId) {
        Map<String, Object> body = new HashMap<>();
        body.put("eventId", UUID.randomUUID().toString());
        body.put("eventType", "provider." + status.toLowerCase());
        body.put("paymentId", payment.getId().toString());
        body.put("orderId", payment.getOrderId().toString());
        body.put("amount", amount);
        body.put("currency", payment.getCurrency());
        body.put("status", status);
        body.put("providerEventId", providerEventId);
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private ResponseEntity<String> postSignedWebhook(String body) {
        return postWebhook(body, sign(SECRET, body));
    }

    private ResponseEntity<String> postWebhook(String body, String signature) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Webhook-Signature", signature);
        return restTemplate.postForEntity(
                "/api/v1/webhooks/payments/" + PROVIDER,
                new HttpEntity<>(body.getBytes(StandardCharsets.UTF_8), headers),
                String.class);
    }

    private static String sign(String secret, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }

    private Payment reload(UUID paymentId) {
        return paymentRepository.findById(paymentId).orElseThrow();
    }

    private void awaitStatus(UUID paymentId, PaymentStatus status) {
        await().atMost(AWAIT).untilAsserted(() ->
                assertThat(reload(paymentId).getStatus()).isEqualTo(status));
    }

    private List<PaymentEvent> eventsFor(UUID paymentId) {
        return eventRepository.findAll().stream()
                .filter(e -> paymentId.equals(e.getPaymentId()))
                .toList();
    }

    private List<String> recordedPayloadsFor(UUID paymentId, String eventType) throws Exception {
        return RECORDED.stream()
                .filter(payload -> matchesPaymentEvent(payload, paymentId, eventType))
                .toList();
    }

    private int recordedIndex(UUID paymentId, String eventType) throws Exception {
        for (int i = 0; i < RECORDED.size(); i++) {
            if (matchesPaymentEvent(RECORDED.get(i), paymentId, eventType)) {
                return i;
            }
        }
        throw new AssertionError("no recorded " + eventType + " payload for payment " + paymentId);
    }

    private boolean matchesPaymentEvent(String payload, UUID paymentId, String eventType) {
        try {
            JsonNode node = parseRecordedPayload(payload);
            return eventType.equals(node.path("eventType").asText())
                    && paymentId.toString().equals(node.path("paymentId").asText());
        } catch (Exception e) {
            return false;
        }
    }

    private JsonNode parseRecordedPayload(String payload) throws Exception {
        JsonNode node = objectMapper.readTree(payload);
        if (node.isTextual()) {
            node = objectMapper.readTree(node.asText());
        }
        return node;
    }
}
