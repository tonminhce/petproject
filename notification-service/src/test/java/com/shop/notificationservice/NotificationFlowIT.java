package com.shop.notificationservice;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.common.kafka.producer.KafkaMessagePublisher;
import com.shop.notificationservice.constant.NotificationChannel;
import com.shop.notificationservice.constant.NotificationStatus;
import com.shop.notificationservice.dto.OrderLifecycleEvent;
import com.shop.notificationservice.entity.Notification;
import com.shop.notificationservice.repository.NotificationRepository;
import com.shop.notificationservice.service.sender.NotificationSender;
import com.shop.notificationservice.support.AbstractIntegrationTest;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

/**
 * Notification flow over the REAL fleet inbound wire (R1 + H-1): order
 * lifecycle records are produced through the SAME path production uses —
 * {@link KafkaMessagePublisher} over a StringSerializer ×2 template (the
 * {@code stringKafkaTemplate} semantics of order-service's
 * {@code OrderOutboxRelay}) — so the payload String lands SINGLE-ENCODED
 * UTF-8 JSON on the wire. The consumer unwraps-once at the
 * {@code BaseKafkaConsumer} boundary and also tolerates the legacy
 * double-encoded shape (a JSON string token wrapping the event JSON) that
 * pre-R1 in-flight records may still carry — pinned by test 8. The helper
 * previously used a {@code JsonKafkaSerializer} double-encoding template —
 * a wire production no longer emits.
 */
class NotificationFlowIT extends AbstractIntegrationTest {

    private static final String TOPIC = "shop.order.lifecycle.v1";
    private static final Duration AWAIT = Duration.ofSeconds(20);

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoSpyBean("primary")
    private NotificationSender sender;

    @Value("${shop.kafka.bootstrap-servers}")
    private String bootstrapServers;

    private KafkaTemplate<String, String> producerTemplate;
    private KafkaMessagePublisher kafkaMessagePublisher;

    @BeforeEach
    void initProducer() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        // The R1 production path: KafkaMessagePublisher over StringSerializer
        // ×2 (stringKafkaTemplate semantics) — the payload String is forwarded
        // as raw UTF-8 bytes, single-encoded JSON on the wire.
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        producerTemplate = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));
        kafkaMessagePublisher = new KafkaMessagePublisher(producerTemplate);
    }

    @AfterEach
    void closeProducer() {
        producerTemplate.destroy();
    }

    @Test
    @DisplayName("1. order.created.v1 → SENT/LOG row, subject 'Order <id> created', user_id set")
    void createdEventProducesSentLogRowWithSubjectAndUserId() {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        OrderLifecycleEvent event = createdEvent(orderId, userId,
                List.of(Map.of("sku", "SKU-1", "quantity", 2), Map.of("sku", "SKU-2", "quantity", 1)));
        send(event);

        Notification row = awaitRow(event.eventId());
        assertThat(row.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(row.getChannel()).isEqualTo(NotificationChannel.LOG);
        assertThat(row.getSubject()).isEqualTo("Order " + orderId + " created");
        assertThat(row.getUserId()).isEqualTo(userId);
        assertThat(row.getOrderId()).isEqualTo(orderId);
    }

    @Test
    @DisplayName("2. order.updated.v1 → SENT row with user_id NULL")
    void updatedEventProducesSentRowWithNullUserId() {
        OrderLifecycleEvent event = createdEvent(UUID.randomUUID(), null, null,
                "order.updated.v1", "CONFIRMED", null, null, null);
        send(event);

        Notification row = awaitRow(event.eventId());
        assertThat(row.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(row.getUserId()).isNull();
        assertThat(row.getEventType()).isEqualTo("order.updated.v1");
    }

    @Test
    @DisplayName("3. order.cancelled.v1 → SENT row whose body contains refunded=false")
    void cancelledEventProducesSentRowWithRefundedFalseBody() {
        OrderLifecycleEvent event = createdEvent(UUID.randomUUID(), UUID.randomUUID(), null,
                "order.cancelled.v1", "CANCELLED", null, Instant.now(), Boolean.FALSE);
        send(event);

        Notification row = awaitRow(event.eventId());
        assertThat(row.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(row.getBody()).contains("refunded=false");
    }

    @Test
    @DisplayName("4. duplicate eventId re-send → still exactly 1 row")
    void duplicateEventIdProducesExactlyOneRow() {
        UUID orderId = UUID.randomUUID();
        OrderLifecycleEvent event = createdEvent(orderId, UUID.randomUUID(), null);
        send(event);
        awaitRow(event.eventId());

        send(event);
        OrderLifecycleEvent marker = createdEvent(orderId, UUID.randomUUID(), null);
        send(marker);
        awaitRow(marker.eventId());

        await().atMost(AWAIT).untilAsserted(() ->
            assertThat(rowsFor(event.eventId())).hasSize(1));
    }

    @Test
    @DisplayName("5. unknown eventType → SKIPPED row")
    void unknownEventTypeProducesSkippedRow() {
        OrderLifecycleEvent event = createdEvent(UUID.randomUUID(), UUID.randomUUID(), null,
                "order.teleported.v9", "NEW", null, null, null);
        send(event);

        Notification row = awaitRow(event.eventId());
        assertThat(row.getStatus()).isEqualTo(NotificationStatus.SKIPPED);
        assertThat(row.getSubject()).isEqualTo("[skipped] order.teleported.v9");
    }

    @Test
    @DisplayName("6. C12/C17: sender throws → FAILED_RETRYABLE row (retryCount=1), then a new event → SENT row (partition survives)")
    void senderFailureMarksRowFailedAndPartitionSurvives() {
        doThrow(new RuntimeException("smtp-down")).doCallRealMethod().when(sender).send(any());

        OrderLifecycleEvent failing = createdEvent(UUID.randomUUID(), UUID.randomUUID(), null);
        send(failing);
        Notification failedRow = awaitRow(failing.eventId());
        // C12: the row never claims SENT; C17: the failure is retryable with bookkeeping.
        assertThat(failedRow.getStatus()).isEqualTo(NotificationStatus.FAILED_RETRYABLE);
        assertThat(failedRow.getRetryCount()).isEqualTo(1);
        assertThat(failedRow.getNextRetryAt()).isNotNull();
        assertThat(failedRow.getLastError()).contains("smtp-down");

        OrderLifecycleEvent following = createdEvent(UUID.randomUUID(), UUID.randomUUID(), null);
        send(following);
        Notification sentRow = awaitRow(following.eventId());
        assertThat(sentRow.getStatus()).isEqualTo(NotificationStatus.SENT);
    }

    @Test
    @DisplayName("7. raw garbage payload → contained ack-skip, next valid event still processed")
    void poisonedRecordSkippedAndListenerSurvives() {
        sendRaw("{ this is not json }");

        OrderLifecycleEvent valid = createdEvent(UUID.randomUUID(), UUID.randomUUID(), null);
        send(valid);
        Notification row = awaitRow(valid.eventId());
        assertThat(row.getStatus()).isEqualTo(NotificationStatus.SENT);
    }

    @Test
    @DisplayName("8. H-1 pin: a LEGACY double-encoded token (pre-R1 in-flight shape) is unwrapped and ingested")
    void legacyDoubleEncodedTokenIsAccepted() throws Exception {
        OrderLifecycleEvent event = createdEvent(UUID.randomUUID(), UUID.randomUUID(), null);
        sendLegacyDoubleEncoded(event);
        Notification row = awaitRow(event.eventId());
        assertThat(row.getStatus()).isEqualTo(NotificationStatus.SENT);

        // Wire proof on the real topic: the record value is a JSON string
        // token wrapping the event JSON — the pre-R1 wire that in-flight
        // records may still carry; the unwrap-once boundary accepts it.
        ConsumerRecord<String, String> record = awaitRecord(TOPIC, event.orderId().toString());
        assertThat(record).as("record on %s with key=orderId", TOPIC).isNotNull();
        JsonNode token = objectMapper.readTree(record.value());
        assertThat(token.isTextual()).as("legacy wire shape is a JSON string token").isTrue();
        JsonNode unwrapped = objectMapper.readTree(token.textValue());
        assertThat(unwrapped.get("eventType").textValue()).isEqualTo("order.created.v1");
        assertThat(unwrapped.get("orderId").textValue()).isEqualTo(event.orderId().toString());
    }

    /** H26 — OrderLifecycleEvent is a Java record; construction goes through the canonical ctor. */
    private OrderLifecycleEvent createdEvent(UUID orderId, UUID userId, List<Map<String, Object>> items) {
        return createdEvent(orderId, userId, items, "order.created.v1", "NEW", null, null, null);
    }

    /** Variant for tests that need to override eventType/status/transitionedAt/cancelledAt/refunded. */
    private OrderLifecycleEvent createdEvent(UUID orderId, UUID userId, List<Map<String, Object>> items,
                                             String eventType, String status,
                                             Instant transitionedAt, Instant cancelledAt, Boolean refunded) {
        return new OrderLifecycleEvent(
                UUID.randomUUID().toString(),
                eventType,
                Instant.now().toString(),
                orderId,
                userId,
                status,
                new BigDecimal("100.00"),
                new BigDecimal("8.00"),
                new BigDecimal("0.00"),
                new BigDecimal("108.00"),
                transitionedAt,
                cancelledAt,
                refunded,
                items);
    }

    /** Publishes through the R1 production path: single-encoded JSON on the wire. */
    private void send(OrderLifecycleEvent event) {
        try {
            sendRaw(objectMapper.writeValueAsString(event), event.orderId().toString());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Publishes the pre-R1 legacy wire: a JSON string token wrapping the event
     * JSON (H-1 tolerance pin for in-flight records from pre-R1 producers).
     */
    private void sendLegacyDoubleEncoded(OrderLifecycleEvent event) {
        try {
            String payloadJson = objectMapper.writeValueAsString(event);
            kafkaMessagePublisher.publish(TOPIC, event.orderId().toString(),
                objectMapper.writeValueAsString(payloadJson));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private void sendRaw(String json) {
        sendRaw(json, null);
    }

    private void sendRaw(String json, String key) {
        try {
            kafkaMessagePublisher.publish(TOPIC, key, json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private List<Notification> rowsFor(String eventId) {
        return notificationRepository.findAll().stream()
            .filter(n -> eventId.equals(n.getEventId().toString()))
            .toList();
    }

    private Notification awaitRow(String eventId) {
        List<Notification> found = new ArrayList<>();
        await().atMost(AWAIT).untilAsserted(() -> {
            found.clear();
            found.addAll(rowsFor(eventId));
            assertThat(found).hasSize(1);
        });
        return found.get(0);
    }

    /** Raw readback (StringDeserializer) to prove the actual wire shape. */
    private ConsumerRecord<String, String> awaitRecord(String topic, String key) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "notification-wire-it-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(topic));
            long deadline = System.currentTimeMillis() + 20_000;
            while (System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (key.equals(record.key())) {
                        return record;
                    }
                }
            }
        }
        return null;
    }
}
