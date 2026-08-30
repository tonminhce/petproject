package com.shop.notificationservice;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.notificationservice.constant.NotificationChannel;
import com.shop.notificationservice.constant.NotificationStatus;
import com.shop.notificationservice.dto.OrderLifecycleEvent;
import com.shop.notificationservice.entity.Notification;
import com.shop.notificationservice.repository.NotificationRepository;
import com.shop.notificationservice.service.sender.NotificationSender;
import com.shop.notificationservice.support.AbstractIntegrationTest;
import org.apache.kafka.clients.producer.ProducerConfig;
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
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

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

    private KafkaTemplate<String, String> kafkaTemplate;

    @BeforeEach
    void initProducer() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        kafkaTemplate = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));
    }

    @AfterEach
    void closeProducer() {
        kafkaTemplate.destroy();
    }

    @Test
    @DisplayName("1. order.created.v1 → SENT/LOG row, subject 'Order <id> created', user_id set")
    void createdEventProducesSentLogRowWithSubjectAndUserId() {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        OrderLifecycleEvent event = createdEvent(orderId, userId);
        event.setItems(List.of(Map.of("sku", "SKU-1", "quantity", 2), Map.of("sku", "SKU-2", "quantity", 1)));
        send(event);

        Notification row = awaitRow(event.getEventId());
        assertThat(row.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(row.getChannel()).isEqualTo(NotificationChannel.LOG);
        assertThat(row.getSubject()).isEqualTo("Order " + orderId + " created");
        assertThat(row.getUserId()).isEqualTo(userId);
        assertThat(row.getOrderId()).isEqualTo(orderId);
    }

    @Test
    @DisplayName("2. order.updated.v1 → SENT row with user_id NULL")
    void updatedEventProducesSentRowWithNullUserId() {
        OrderLifecycleEvent event = createdEvent(UUID.randomUUID(), null);
        event.setEventType("order.updated.v1");
        event.setStatus("CONFIRMED");
        event.setTransitionedAt(Instant.now());
        send(event);

        Notification row = awaitRow(event.getEventId());
        assertThat(row.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(row.getUserId()).isNull();
        assertThat(row.getEventType()).isEqualTo("order.updated.v1");
    }

    @Test
    @DisplayName("3. order.cancelled.v1 → SENT row whose body contains refunded=false")
    void cancelledEventProducesSentRowWithRefundedFalseBody() {
        OrderLifecycleEvent event = createdEvent(UUID.randomUUID(), UUID.randomUUID());
        event.setEventType("order.cancelled.v1");
        event.setStatus("CANCELLED");
        event.setCancelledAt(Instant.now());
        event.setRefunded(false);
        send(event);

        Notification row = awaitRow(event.getEventId());
        assertThat(row.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(row.getBody()).contains("refunded=false");
    }

    @Test
    @DisplayName("4. duplicate eventId re-send → still exactly 1 row")
    void duplicateEventIdProducesExactlyOneRow() {
        UUID orderId = UUID.randomUUID();
        OrderLifecycleEvent event = createdEvent(orderId, UUID.randomUUID());
        send(event);
        awaitRow(event.getEventId());

        send(event);
        OrderLifecycleEvent marker = createdEvent(orderId, UUID.randomUUID());
        send(marker);
        awaitRow(marker.getEventId());

        await().atMost(AWAIT).untilAsserted(() ->
            assertThat(rowsFor(event.getEventId())).hasSize(1));
    }

    @Test
    @DisplayName("5. unknown eventType → SKIPPED row")
    void unknownEventTypeProducesSkippedRow() {
        OrderLifecycleEvent event = createdEvent(UUID.randomUUID(), UUID.randomUUID());
        event.setEventType("order.teleported.v9");
        send(event);

        Notification row = awaitRow(event.getEventId());
        assertThat(row.getStatus()).isEqualTo(NotificationStatus.SKIPPED);
        assertThat(row.getSubject()).isEqualTo("[skipped] order.teleported.v9");
    }

    @Test
    @DisplayName("6. sender throws → FAILED row, then a new event → SENT row (partition survives)")
    void senderFailureMarksRowFailedAndPartitionSurvives() {
        doThrow(new RuntimeException("smtp-down")).doCallRealMethod().when(sender).send(any());

        OrderLifecycleEvent failing = createdEvent(UUID.randomUUID(), UUID.randomUUID());
        send(failing);
        Notification failedRow = awaitRow(failing.getEventId());
        assertThat(failedRow.getStatus()).isEqualTo(NotificationStatus.FAILED);

        OrderLifecycleEvent following = createdEvent(UUID.randomUUID(), UUID.randomUUID());
        send(following);
        Notification sentRow = awaitRow(following.getEventId());
        assertThat(sentRow.getStatus()).isEqualTo(NotificationStatus.SENT);
    }

    @Test
    @DisplayName("7. malformed raw JSON → skipped at deserializer layer, next valid event still processed")
    void poisonedRecordSkippedAndListenerSurvives() {
        sendRaw("{ this is not json }");

        OrderLifecycleEvent valid = createdEvent(UUID.randomUUID(), UUID.randomUUID());
        send(valid);
        Notification row = awaitRow(valid.getEventId());
        assertThat(row.getStatus()).isEqualTo(NotificationStatus.SENT);
    }

    private OrderLifecycleEvent createdEvent(UUID orderId, UUID userId) {
        OrderLifecycleEvent event = new OrderLifecycleEvent();
        event.setEventId(UUID.randomUUID().toString());
        event.setEventType("order.created.v1");
        event.setOccurredAt(Instant.now().toString());
        event.setOrderId(orderId);
        event.setUserId(userId);
        event.setStatus("NEW");
        event.setSubtotal(new BigDecimal("100.00"));
        event.setTaxAmount(new BigDecimal("8.00"));
        event.setDiscountAmount(new BigDecimal("0.00"));
        event.setTotal(new BigDecimal("108.00"));
        return event;
    }

    private void send(OrderLifecycleEvent event) {
        try {
            sendRaw(objectMapper.writeValueAsString(event), event.getOrderId().toString());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private void sendRaw(String json) {
        sendRaw(json, null);
    }

    private void sendRaw(String json, String key) {
        try {
            kafkaTemplate.send(TOPIC, key, json).get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
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
}
