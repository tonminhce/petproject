package com.shop.paymentservice.outbox;

import com.shop.common.core.constants.OutboxStatus;
import com.shop.common.kafka.producer.KafkaMessagePublisher;
import com.shop.paymentservice.support.AbstractIntegrationTest;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

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
 * <p>These tests drive {@code PaymentOutboxRelay.relay()} — the real entry
 * point — so a regression that un-wires the sweep fails here rather than in
 * production. The broker side is the real Kafka container, so the ordering
 * assertion is measured against the real producer configuration
 * ({@code acks=all}, idempotence, {@code max.in.flight=1}) rather than against
 * a mock's behaviour.</p>
 */
class PaymentOutboxRelayRecoveryIT extends AbstractIntegrationTest {

    @DynamicPropertySource
    static void quiesceScheduledRelay(DynamicPropertyRegistry registry) {
        registry.add("shop.payment.outbox.poll-millis", () -> "3600000");
    }

    @Autowired
    OutboxEventRepository outboxRepo;

    @Autowired
    EntityManager entityManager;

    @Autowired
    PlatformTransactionManager txManager;

    @Autowired
    KafkaMessagePublisher kafkaPublisher;

    @Value("${shop.kafka.bootstrap-servers}")
    String bootstrapServers;

    private PaymentOutboxRelay relay;

    @BeforeEach
    void setUp() {
        outboxRepo.deleteAll();
        relay = new PaymentOutboxRelay(outboxRepo, kafkaPublisher, txManager);
        ReflectionTestUtils.setField(relay, "batchSize", 100);
        ReflectionTestUtils.setField(relay, "maxRetries", 10);
        ReflectionTestUtils.setField(relay, "publishTimeoutMs", 10_000L);
        ReflectionTestUtils.setField(relay, "claimLeaseMs", 60_000L);
    }

    @Test
    void aRowStrandedInSending_isRecoveredAndPublishedByTheRelay() {
        String topic = uniqueTopic();
        OutboxEvent stranded = seed(topic, "{\"payment\":\"captured\"}", OutboxStatus.SENDING);
        // The crash happened a long time ago: the claim lease has expired.
        ageClaim(stranded.getId(), Instant.now().minusSeconds(3600));

        relay.relay();

        // The relay must have recovered the row AND published it.
        assertThat(consumed(topic, 1))
                .as("the stranded event must be reclaimed and published, not lost")
                .containsExactly("{\"payment\":\"captured\"}");

        OutboxEvent recovered = outboxRepo.findById(stranded.getId()).orElseThrow();
        assertThat(recovered.getStatus())
                .as("the recovered row must end up published")
                .isEqualTo(OutboxStatus.SENT);
        assertThat(recovered.getSentAt()).isNotNull();
    }

    @Test
    void aFreshSendingRow_isLeftAloneBecauseItsPublishMayStillBeInFlight() {
        String topic = uniqueTopic();
        OutboxEvent inFlight = seed(topic, "{\"payment\":\"in-flight\"}", OutboxStatus.SENDING);

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
        OutboxEvent pending = seed(topic, "{\"payment\":\"pending\"}", OutboxStatus.PENDING);

        relay.relay();

        assertThat(consumed(topic, 1)).containsExactly("{\"payment\":\"pending\"}");
        assertThat(outboxRepo.findById(pending.getId()).orElseThrow().getStatus())
                .isEqualTo(OutboxStatus.SENT);
    }

    @Test
    void eventsOfOneAggregate_arePublishedInProductionOrder() {
        // Same aggregate, three events: the consumer must see them in the
        // order they were produced (id order), never in the arbitrary order
        // the database happened to return the rows in.
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
        return "shop.payment.relaytest." + UUID.randomUUID().toString().replace("-", "");
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
                .aggregateType("payment")
                .aggregateId(aggregate)
                .eventType("payment.test.v1")
                .topic(topic)
                .payload(payload)
                .status(status)
                .retryCount(0)
                .build();
        outboxRepo.saveAndFlush(event);
        return event;
    }

    /** Age the claim past the lease; {@code @UpdateTimestamp} would overwrite it on save. */
    private void ageClaim(Long id, Instant when) {
        new org.springframework.transaction.support.TransactionTemplate(txManager)
                .executeWithoutResult(tx -> entityManager.createNativeQuery(
                        "UPDATE outbox_events SET updated_at = ?1 WHERE id = ?2")
                        .setParameter(1, when)
                        .setParameter(2, id)
                        .executeUpdate());
        entityManager.clear();
    }
}
