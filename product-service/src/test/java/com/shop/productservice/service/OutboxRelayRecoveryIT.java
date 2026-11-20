package com.shop.productservice.service;

import com.shop.common.core.constants.OutboxStatus;
import com.shop.productservice.entity.OutboxEvent;
import com.shop.productservice.repository.OutboxEventRepository;
import com.shop.productservice.support.AbstractIntegrationTest;
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

class OutboxRelayRecoveryIT extends AbstractIntegrationTest {

    @DynamicPropertySource
    static void quiesceScheduledRelay(DynamicPropertyRegistry registry) {
        registry.add("product.outbox.poll-interval-ms", () -> "3600000");
    }

    @Autowired
    OutboxEventRepository outboxRepo;

    @Autowired
    EntityManager entityManager;

    @Autowired
    PlatformTransactionManager txManager;

    @Autowired
    OutboxRelay relay;

    @Value("${shop.kafka.bootstrap-servers}")
    String bootstrapServers;

    @BeforeEach
    void setUp() {
        outboxRepo.deleteAll();
    }

    @Test
    void aRowStrandedInSending_isRecoveredAndPublishedByTheRelay() {
        String topic = uniqueTopic();
        OutboxEvent stranded = seed(topic, "{\"product\":\"stranded\"}", OutboxStatus.SENDING);
        ageClaim(stranded, Instant.now().minusSeconds(3600));

        relay.relay();

        assertThat(consumed(topic, 1))
                .as("the stranded product event must be reclaimed and published, not lost")
                .containsExactly("{\"product\":\"stranded\"}");

        OutboxEvent recovered = outboxRepo.findById(stranded.getId()).orElseThrow();
        assertThat(recovered.getStatus())
                .as("the recovered row must end up published")
                .isEqualTo(OutboxStatus.SENT);
        assertThat(recovered.getSentAt()).isNotNull();
    }

    @Test
    void aFreshSendingRow_isLeftAloneBecauseItsPublishMayStillBeInFlight() {
        String topic = uniqueTopic();
        OutboxEvent inFlight = seed(topic, "{\"product\":\"in-flight\"}", OutboxStatus.SENDING);
        ageClaim(inFlight, Instant.now());

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
        OutboxEvent pending = seed(topic, "{\"product\":\"pending\"}", OutboxStatus.PENDING);

        relay.relay();

        assertThat(consumed(topic, 1)).containsExactly("{\"product\":\"pending\"}");
        assertThat(outboxRepo.findById(pending.getId()).orElseThrow().getStatus())
                .isEqualTo(OutboxStatus.SENT);
    }

    @Test
    void eventsOfOneProduct_arePublishedInProductionOrder() {
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

    @Test
    void aSendingRowWithoutAClaimTimestamp_isRecoveredAndPublished() {
        String topic = uniqueTopic();
        OutboxEvent stranded = seed(topic, "{\"product\":\"stranded-without-lease\"}", OutboxStatus.SENDING);

        assertThat(stranded.getClaimedAt())
                .as("the row must enter SENDING without a lease timestamp")
                .isNull();

        relay.relay();

        assertThat(consumed(topic, 1))
                .as("a SENDING row without a lease timestamp must be re-armed, not stranded forever")
                .containsExactly("{\"product\":\"stranded-without-lease\"}");
        assertThat(outboxRepo.findById(stranded.getId()).orElseThrow().getStatus())
                .isEqualTo(OutboxStatus.SENT);
    }

    @Test
    void releaseOwnClaims_neverHandsBackTheLeaseOfAPublishedEvent() {
        OutboxEvent published = seed(uniqueTopic(), "{\"product\":\"published\"}", OutboxStatus.SENDING);
        OutboxEvent unpublished = seed(uniqueTopic(), "{\"product\":\"unpublished\"}", OutboxStatus.SENDING);
        lease(published, "owner-a");
        lease(unpublished, "owner-a");

        int released = new TransactionTemplate(txManager).execute(status -> outboxRepo.releaseOwnedClaims(
                OutboxStatus.SENDING, OutboxStatus.PENDING, "owner-a", List.of(published.getId())));

        assertThat(released).isEqualTo(1);
        assertThat(outboxRepo.findById(published.getId()).orElseThrow().getStatus())
                .as("a lease whose event was already published must not go back to PENDING")
                .isEqualTo(OutboxStatus.SENDING);
        assertThat(outboxRepo.findById(unpublished.getId()).orElseThrow().getStatus())
                .isEqualTo(OutboxStatus.PENDING);
    }

    private void lease(OutboxEvent event, String owner) {
        event.setClaimedAt(Instant.now());
        event.setLeaseOwner(owner);
        outboxRepo.saveAndFlush(event);
    }

    private String uniqueTopic() {
        return "shop.product.relaytest." + UUID.randomUUID().toString().replace("-", "");
    }

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
                .aggregateType("Product")
                .aggregateId(aggregate)
                .eventType("product.created.v1")
                .topic(topic)
                .payload(payload)
                .status(status)
                .retryCount(0)
                .build();
        outboxRepo.saveAndFlush(event);
        return event;
    }

    private void ageClaim(OutboxEvent event, Instant when) {
        event.setClaimedAt(when);
        outboxRepo.saveAndFlush(event);
    }
}
