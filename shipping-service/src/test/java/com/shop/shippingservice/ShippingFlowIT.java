package com.shop.shippingservice;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.common.core.constants.ApiPaths;
import com.shop.common.kafka.config.KafkaProperties;
import com.shop.shippingservice.constant.Carrier;
import com.shop.shippingservice.constant.ShipmentStatus;
import com.shop.shippingservice.entity.Shipment;
import com.shop.shippingservice.repository.ShipmentRepository;
import com.shop.shippingservice.scheduler.ReconciliationScheduler;
import com.shop.shippingservice.support.AbstractIntegrationTest;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.test.annotation.DirtiesContext;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@AutoConfigureTestRestTemplate
class ShippingFlowIT extends AbstractIntegrationTest {

    private static final String ORDER_TOPIC = "shop.order.lifecycle.v1";
    private static final String LIFECYCLE_TOPIC = "shop.shipping.lifecycle.v1";
    private static final String EVENT_ORDER_CONFIRMED = "order.confirmed.v1";
    private static final String EVENT_ORDER_CANCELLED = "order.cancelled.v1";
    private static final String EVENT_SHIPPING_DELIVERED = "shipping.delivered.v1";
    private static final String EVENT_CARRIER_STATUS = "carrier.status.v1";
    private static final String GHN_SECRET = "ghn-it-secret";
    private static final String ADMIN_TOKEN = "shipping-it-admin-token";
    private static final String BACKOFFICE = ApiPaths.BACKOFFICE_SHIPMENTS;
    private static final String WEBHOOK = ApiPaths.WEBHOOK_SHIPPING + "/GHN";
    private static final Duration AWAIT = Duration.ofSeconds(20);

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ShipmentRepository shipmentRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AtomicReference<Instant> testInstant;

    @Autowired
    private ReconciliationScheduler reconciliationScheduler;

    @Autowired
    private Recorder recorder;

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
    @DisplayName("1. order CONFIRMED event creates exactly one CREATED shipment, duplicate re-send included")
    void confirmedOrderCreatesExactlyOneShipmentUnderDuplicateResend() {
        UUID orderId = UUID.randomUUID();
        sendOrderEvent(orderId, EVENT_ORDER_CONFIRMED, "CONFIRMED");

        Shipment shipment = awaitStatus(orderId, ShipmentStatus.CREATED);
        assertThat(shipment.getCarrier()).isEqualTo(Carrier.MANUAL);
        assertThat(shipment.getTrackingNumber()).isNull();
        assertThat(shipment.isAutoDelivered()).isFalse();

        sendOrderEvent(orderId, EVENT_ORDER_CONFIRMED, "CONFIRMED");
        UUID markerOrderId = UUID.randomUUID();
        sendOrderEvent(markerOrderId, EVENT_ORDER_CONFIRMED, "CONFIRMED");
        awaitStatus(markerOrderId, ShipmentStatus.CREATED);

        assertThat(shipmentCountForOrder(orderId)).isEqualTo(1);
        assertThat(shipmentRepository.findByOrderId(orderId).orElseThrow().getStatus())
            .isEqualTo(ShipmentStatus.CREATED);
    }

    @Test
    @DisplayName("2. admin tracking assignment moves CREATED shipment to PICKED_UP")
    void adminTrackingAssignmentMovesShipmentToPickedUp() {
        UUID orderId = UUID.randomUUID();
        sendOrderEvent(orderId, EVENT_ORDER_CONFIRMED, "CONFIRMED");
        Shipment shipment = awaitStatus(orderId, ShipmentStatus.CREATED);
        String trackingNumber = "GHN-TRACK-" + UUID.randomUUID();
        Instant assignedAt = testInstant.get();

        JsonNode data = adminPost(BACKOFFICE + "/" + shipment.getId() + "/tracking",
            Map.of("trackingNumber", trackingNumber));

        assertThat(data.path("status").asText()).isEqualTo("PICKED_UP");
        assertThat(data.path("trackingNumber").asText()).isEqualTo(trackingNumber);
        Shipment pickedUp = reload(shipment.getId());
        assertThat(pickedUp.getStatus()).isEqualTo(ShipmentStatus.PICKED_UP);
        assertThat(pickedUp.getPreviousStatus()).isEqualTo(ShipmentStatus.CREATED);
        assertThat(pickedUp.getLastCarrierUpdate()).isEqualTo(assignedAt);
    }

    @Test
    @DisplayName("3. signed GHN webhooks walk IN_TRANSIT -> OUT_FOR_DELIVERY -> DELIVERED, last_carrier_update advances, recorder sees manual delivered event")
    void signedWebhooksWalkShipmentToDeliveredAndPublishManualEvent() {
        UUID orderId = UUID.randomUUID();
        sendOrderEvent(orderId, EVENT_ORDER_CONFIRMED, "CONFIRMED");
        Shipment shipment = awaitStatus(orderId, ShipmentStatus.CREATED);
        String trackingNumber = "GHN-WALK-" + UUID.randomUUID();
        assignTracking(shipment.getId(), trackingNumber);

        Instant inTransitAt = testInstant.get();
        postWebhook(webhookBody("wh-walk-in-" + UUID.randomUUID(), trackingNumber, "IN_TRANSIT"));
        Shipment inTransit = reload(shipment.getId());
        assertThat(inTransit.getStatus()).isEqualTo(ShipmentStatus.IN_TRANSIT);
        assertThat(inTransit.getLastCarrierUpdate()).isEqualTo(inTransitAt);

        advanceClock(Duration.ofMinutes(10));
        postWebhook(webhookBody("wh-walk-ofd-" + UUID.randomUUID(), trackingNumber, "OUT_FOR_DELIVERY"));
        Shipment outForDelivery = reload(shipment.getId());
        assertThat(outForDelivery.getStatus()).isEqualTo(ShipmentStatus.OUT_FOR_DELIVERY);
        assertThat(outForDelivery.getLastCarrierUpdate()).isAfter(inTransit.getLastCarrierUpdate());

        advanceClock(Duration.ofMinutes(10));
        Instant deliveredAt = testInstant.get();
        postWebhook(webhookBody("wh-walk-del-" + UUID.randomUUID(), trackingNumber, "DELIVERED"));
        Shipment delivered = reload(shipment.getId());
        assertThat(delivered.getStatus()).isEqualTo(ShipmentStatus.DELIVERED);
        assertThat(delivered.getLastCarrierUpdate()).isAfter(outForDelivery.getLastCarrierUpdate());
        assertThat(delivered.getDeliveredAt()).isEqualTo(deliveredAt);

        JsonNode event = awaitDeliveredEvent(shipment.getId(), false);
        assertThat(event.path("orderId").asText()).isEqualTo(orderId.toString());
        assertThat(event.path("trackingNumber").asText()).isEqualTo(trackingNumber);
    }

    @Test
    @DisplayName("4. webhook replay with the same eventId is an acked no-op — no transition, no extra rows, recorder unchanged")
    void webhookReplayWithSameEventIdChangesNothing() {
        UUID orderId = UUID.randomUUID();
        sendOrderEvent(orderId, EVENT_ORDER_CONFIRMED, "CONFIRMED");
        Shipment shipment = awaitStatus(orderId, ShipmentStatus.CREATED);
        String trackingNumber = "GHN-REPLAY-" + UUID.randomUUID();
        assignTracking(shipment.getId(), trackingNumber);
        postWebhook(webhookBody("wh-replay-in-" + UUID.randomUUID(), trackingNumber, "IN_TRANSIT"));
        postWebhook(webhookBody("wh-replay-ofd-" + UUID.randomUUID(), trackingNumber, "OUT_FOR_DELIVERY"));
        String deliveredEventId = "wh-replay-del-" + UUID.randomUUID();
        String deliveredBody = webhookBody(deliveredEventId, trackingNumber, "DELIVERED");
        postWebhook(deliveredBody);
        Shipment before = awaitStatus(orderId, ShipmentStatus.DELIVERED);
        awaitDeliveredEvent(shipment.getId(), false);

        postWebhook(deliveredBody);

        Shipment after = reload(shipment.getId());
        assertThat(after.getStatus()).isEqualTo(ShipmentStatus.DELIVERED);
        assertThat(after.getPreviousStatus()).isEqualTo(before.getPreviousStatus());
        assertThat(after.getDeliveredAt()).isEqualTo(before.getDeliveredAt());
        assertThat(after.getLastCarrierUpdate()).isEqualTo(before.getLastCarrierUpdate());
        assertThat(after.getVersion()).isEqualTo(before.getVersion());
        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from shipment_events where provider_event_id = ?",
            Integer.class, deliveredEventId)).isEqualTo(1);
        assertThat(deliveredEvents(shipment.getId())).hasSize(1);
    }

    @Test
    @DisplayName("5. DELIVERY_FAILED webhook flips status; admin retry returns the shipment to IN_TRANSIT")
    void deliveryFailedWebhookThenAdminRetryRestoresInTransit() {
        UUID orderId = UUID.randomUUID();
        sendOrderEvent(orderId, EVENT_ORDER_CONFIRMED, "CONFIRMED");
        Shipment shipment = awaitStatus(orderId, ShipmentStatus.CREATED);
        String trackingNumber = "GHN-RETRY-" + UUID.randomUUID();
        assignTracking(shipment.getId(), trackingNumber);
        postWebhook(webhookBody("wh-retry-in-" + UUID.randomUUID(), trackingNumber, "IN_TRANSIT"));
        assertThat(reload(shipment.getId()).getStatus()).isEqualTo(ShipmentStatus.IN_TRANSIT);

        postWebhook(webhookBody("wh-retry-fail-" + UUID.randomUUID(), trackingNumber, "DELIVERY_FAILED"));
        assertThat(reload(shipment.getId()).getStatus()).isEqualTo(ShipmentStatus.DELIVERY_FAILED);

        JsonNode data = adminPost(BACKOFFICE + "/" + shipment.getId() + "/retry", null);
        assertThat(data.path("status").asText()).isEqualTo("IN_TRANSIT");
        assertThat(reload(shipment.getId()).getStatus()).isEqualTo(ShipmentStatus.IN_TRANSIT);
    }

    @Test
    @DisplayName("6. in-flight shipment past auto-deliver-days is auto-delivered by the stale sweep (autoDelivered=true)")
    void staleSweepAutoDeliversInFlightShipment() {
        UUID orderId = UUID.randomUUID();
        sendOrderEvent(orderId, EVENT_ORDER_CONFIRMED, "CONFIRMED");
        Shipment shipment = awaitStatus(orderId, ShipmentStatus.CREATED);
        assignTracking(shipment.getId(), "GHN-STALE-" + UUID.randomUUID());
        Instant pickupAt = testInstant.get();

        Instant sweepAt = pickupAt.plus(Duration.ofDays(8));
        testInstant.set(sweepAt);
        reconciliationScheduler.reconcile();

        Shipment delivered = reload(shipment.getId());
        assertThat(delivered.getStatus()).isEqualTo(ShipmentStatus.DELIVERED);
        assertThat(delivered.isAutoDelivered()).isTrue();
        assertThat(delivered.getDeliveredAt()).isEqualTo(sweepAt);
        JsonNode event = awaitDeliveredEvent(shipment.getId(), true);
        assertThat(event.path("orderId").asText()).isEqualTo(orderId.toString());
    }

    @Test
    @DisplayName("7. CREATED shipment is never auto-delivered by time alone")
    void createdShipmentIsUntouchedByStaleSweep() {
        UUID orderId = UUID.randomUUID();
        sendOrderEvent(orderId, EVENT_ORDER_CONFIRMED, "CONFIRMED");
        Shipment shipment = awaitStatus(orderId, ShipmentStatus.CREATED);

        advanceClock(Duration.ofDays(365));
        reconciliationScheduler.reconcile();

        Shipment untouched = reload(shipment.getId());
        assertThat(untouched.getStatus()).isEqualTo(ShipmentStatus.CREATED);
        assertThat(untouched.isAutoDelivered()).isFalse();
        assertThat(untouched.getDeliveredAt()).isNull();
        assertThat(deliveredEvents(shipment.getId())).isEmpty();
    }

    @Test
    @DisplayName("8. order CANCELLED event cancels a CREATED shipment")
    void cancelledOrderEventCancelsCreatedShipment() {
        UUID orderId = UUID.randomUUID();
        sendOrderEvent(orderId, EVENT_ORDER_CONFIRMED, "CONFIRMED");
        Shipment shipment = awaitStatus(orderId, ShipmentStatus.CREATED);

        sendOrderEvent(orderId, EVENT_ORDER_CANCELLED, "CANCELLED");

        Shipment cancelled = awaitStatus(orderId, ShipmentStatus.CANCELLED);
        assertThat(cancelled.getPreviousStatus()).isEqualTo(ShipmentStatus.CREATED);
    }

    private void sendOrderEvent(UUID orderId, String eventType, String status) {
        Map<String, Object> event = Map.of(
            "eventId", UUID.randomUUID().toString(),
            "eventType", eventType,
            "occurredAt", Instant.now().toString(),
            "orderId", orderId.toString(),
            "status", status);
        try {
            kafkaTemplate.send(ORDER_TOPIC, orderId.toString(), objectMapper.writeValueAsString(event))
                .get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Shipment awaitStatus(UUID orderId, ShipmentStatus status) {
        await().atMost(AWAIT)
            .until(() -> shipmentRepository.findByOrderId(orderId)
                .map(s -> s.getStatus() == status)
                .orElse(false));
        return shipmentRepository.findByOrderId(orderId).orElseThrow();
    }

    private Shipment reload(UUID shipmentId) {
        return shipmentRepository.findById(shipmentId).orElseThrow();
    }

    private int shipmentCountForOrder(UUID orderId) {
        Integer count = jdbcTemplate.queryForObject(
            "select count(*) from shipments where order_id = ?", Integer.class, orderId);
        return count == null ? 0 : count;
    }

    private void assignTracking(UUID shipmentId, String trackingNumber) {
        JsonNode data = adminPost(BACKOFFICE + "/" + shipmentId + "/tracking",
            Map.of("trackingNumber", trackingNumber));
        assertThat(data.path("status").asText()).isEqualTo("PICKED_UP");
    }

    private JsonNode adminPost(String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(ADMIN_TOKEN);
        ResponseEntity<String> response =
            restTemplate.postForEntity(path, new HttpEntity<>(body, headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return parse(response.getBody()).path("data");
    }

    private String webhookBody(String eventId, String trackingNumber, String carrierStatus) {
        Map<String, Object> payload = Map.of(
            "eventId", eventId,
            "eventType", EVENT_CARRIER_STATUS,
            "trackingNumber", trackingNumber,
            "carrierStatus", carrierStatus);
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private void postWebhook(String json) {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Webhook-Signature", hmacSha256Hex(GHN_SECRET, body));
        ResponseEntity<String> response =
            restTemplate.postForEntity(WEBHOOK, new HttpEntity<>(body, headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private static String hmacSha256Hex(String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private void advanceClock(Duration by) {
        testInstant.set(testInstant.get().plus(by));
    }

    private JsonNode awaitDeliveredEvent(UUID shipmentId, boolean autoDelivered) {
        await().atMost(AWAIT).until(() -> !deliveredEvents(shipmentId).isEmpty());
        List<JsonNode> events = deliveredEvents(shipmentId);
        assertThat(events).hasSize(1);
        JsonNode event = events.get(0);
        assertThat(event.path("eventType").asText()).isEqualTo(EVENT_SHIPPING_DELIVERED);
        assertThat(event.path("autoDelivered").asBoolean()).isEqualTo(autoDelivered);
        return event;
    }

    private List<JsonNode> deliveredEvents(UUID shipmentId) {
        return recorder.snapshot().stream()
            .map(this::parse)
            .filter(node -> EVENT_SHIPPING_DELIVERED.equals(node.path("eventType").asText()))
            .filter(node -> shipmentId.toString().equals(node.path("shipmentId").asText()))
            .toList();
    }

    private JsonNode parse(String raw) {
        try {
            JsonNode node = objectMapper.readTree(raw);
            return node.isTextual() ? objectMapper.readTree(node.asText()) : node;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    static class Recorder {

        private final List<String> payloads = new CopyOnWriteArrayList<>();

        @KafkaListener(topics = LIFECYCLE_TOPIC, groupId = "shipping-it-recorder",
            containerFactory = "recorderListenerFactory")
        public void record(String rawPayload) {
            payloads.add(rawPayload);
        }

        List<String> snapshot() {
            return List.copyOf(payloads);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FlowConfig {

        @Bean
        Recorder recorder() {
            return new Recorder();
        }

        @Bean
        ConcurrentKafkaListenerContainerFactory<String, String> recorderListenerFactory(
            KafkaProperties kafkaProperties) {
            Map<String, Object> props = new HashMap<>(kafkaProperties.buildConsumerProperties());
            props.remove("group.id");
            ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
            factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(
                props, new StringDeserializer(), new StringDeserializer()));
            return factory;
        }

        @Bean
        @Primary
        JwtDecoder adminJwtDecoder() {
            return token -> {
                if (!ADMIN_TOKEN.equals(token)) {
                    throw new JwtException("Unknown test token");
                }
                Instant now = Instant.now();
                return Jwt.withTokenValue(token)
                    .header("alg", "none")
                    .subject("it-admin")
                    .issuedAt(now)
                    .expiresAt(now.plusSeconds(3600))
                    .claim("realm_access", Map.of("roles", List.of("ADMIN")))
                    .build();
            };
        }
    }
}
