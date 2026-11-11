package com.shop.shippingservice.outbox;

import com.shop.common.core.constants.OutboxStatus;
import com.shop.shippingservice.outbox.OutboxEvent;
import com.shop.shippingservice.outbox.OutboxEventRepository;
import com.shop.shippingservice.support.AbstractIntegrationTest;
import jakarta.persistence.EntityManager;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The stranded-claim recovery must be part of the RELAY, not just a
 * repository method that happens to exist.
 *
 * <p>These tests drive {@code ShippingOutboxRelay.relay()} - the real entry
 * point - so a regression that un-wires the sweep fails here rather than in
 * production. The broker side is the real Kafka container, so the ordering
 * assertion is measured against the real producer configuration
 * ({@code acks=all}, idempotence, {@code max.in.flight=1}).</p>
 */
class ShippingOutboxRelayRecoveryIT extends AbstractIntegrationTest {

    @DynamicPropertySource
    static void quiesceScheduledRelay(DynamicPropertyRegistry registry) {
        registry.add("shop.shipping.outbox.poll-millis", () -> "3600000");
    }

    @Autowired
    OutboxEventRepository outboxRepo;

    @Autowired
    EntityManager entityManager;

    @Autowired
    PlatformTransactionManager txManager;

    @Autowired
    ShippingOutboxRelay relay;

    @Value("${shop.kafka.bootstrap-servers}")
    String bootstrapServers;

    @BeforeEach
    void setUp() {
        outboxRepo.deleteAll();
    }

    @Test
    void aRowStrandedInSending_isRecoveredAndPublishedByTheRelay() {
        String topic = uniqueTopic();
        OutboxEvent stranded = seed(topic, "{\"seq\":\"stranded\"}", OutboxStatus.SENDING);
        // The crash happened a long time ago: the claim lease has expired.
        ageClaim(stranded.getId(), Instant.now().minusSeconds(3600));

        relay.relay();

        assertThat(consumed(topic, 1))
                .as("the stranded event must be reclaimed and published, not lost")
                .containsExactly("{\"seq\":\"stranded\"}");

        OutboxEvent recovered = outboxRepo.findById(stranded.getId()).orElseThrow();
        assertThat(recovered.getStatus())
                .as("the recovered row must end up published")
                .isEqualTo(OutboxStatus.SENT);
        assertThat(recovered.getSentAt()).isNotNull();
    }

    @Test
    void aFreshSendingRow_isLeftAloneBecauseItsPublishMayStillBeInFlight() {
        String topic = uniqueTopic();
        OutboxEvent inFlight = seed(topic, "{\"seq\":\"in-flight\"}", OutboxStatus.SENDING);

        relay.relay();

        assertThat(consumed(topic, 1))
                .as("a claim whose lease is still valid must not be re-published")
                .isEmpty();
        assertThat(outboxRepo.findById(inFlight.getId()).orElseThrow().getStatus())
                .isEqualTo(OutboxStatus.SENDING);
    }

    @Test
    void aPendingRow_isPublishedExactlyOnceAndMarkedSent() {
        String topic = uniqueTopic();
        OutboxEvent pending = seed(topic, "{\"seq\":\"pending\"}", OutboxStatus.PENDING);

        relay.relay();

        assertThat(consumed(topic, 1)).containsExactly("{\"seq\":\"pending\"}");
        assertThat(outboxRepo.findById(pending.getId()).orElseThrow().getStatus())
                .isEqualTo(OutboxStatus.SENT);
    }

    @Test
    void eventsOfOneProduct_arePublishedInProductionOrder() {
        // Same aggregate, three events: the consumer must see them in the
        // order they were produced (id order) - the ordering the consumers depend on.
        String topic = uniqueTopic();
        UUID aggregate = UUID.randomUUID();
        seedWithAggregate(topic, aggregate, "{\"seq\":1}");
        seedWithAggregate(topic, aggregate, "{\"seq\":2}");
        seedWithAggregate(topic, aggregate, "{\"seq\":3}");

        relay.relay();

        assertThat(consumed(topic, 3))
                .as("same-aggregate events must reach the consumer in production order")
                .containsExactly("{\"seq\":1}", "{\"seq\":2}", "{\"seq\":3}");
    }

    private String uniqueTopic() {
        return "shop.shipping.relaytest." + UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * Read {@code expected} records from the topic, in the order the broker
     * delivered them. A fresh consumer group with {@code earliest} sees every
     * record the relay published for this topic.
     */
    private List<String> consumed(String topic, int expected) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "relay-recovery-it-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        List<String> values = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(topic));
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (values.size() < expected && System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    values.add(record.value());
                }
            }
        }
        return values;
    }

    private OutboxEvent seed(String topic, String payload, OutboxStatus status) {
        return seedWithAggregate(topic, UUID.randomUUID(), payload, status);
    }

    private OutboxEvent seedWithAggregate(String topic, UUID aggregate, String payload) {
        return seedWithAggregate(topic, aggregate, payload, OutboxStatus.PENDING);
    }

    private OutboxEvent seedWithAggregate(String topic, UUID aggregate, String payload, OutboxStatus status) {
        OutboxEvent event = OutboxEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .aggregateType("shipment")
                .aggregateId(aggregate)
                .eventType("shipment.created.v1")
                .topic(topic)
                .payload(payload)
                .status(status)
                .retryCount(0)
                .build();
        outboxRepo.saveAndFlush(event);
        return event;
    }

    /** Age the claim past the lease; the claim timestamp is set by the relay. */
    private void ageClaim(Long id, Instant when) {
        new TransactionTemplate(txManager)
                .executeWithoutResult(tx -> entityManager.createNativeQuery(
                        "UPDATE outbox_events SET claimed_at = ?1 WHERE id = ?2")
                        .setParameter(1, when)
                        .setParameter(2, id)
                        .executeUpdate());
        entityManager.clear();
    }
}
