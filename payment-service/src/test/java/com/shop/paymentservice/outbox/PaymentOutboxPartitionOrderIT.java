package com.shop.paymentservice.outbox;

import com.shop.common.core.constants.OutboxStatus;
import com.shop.paymentservice.support.AbstractIntegrationTest;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.errors.TopicExistsException;
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

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentOutboxPartitionOrderIT extends AbstractIntegrationTest {

    @DynamicPropertySource
    static void quiesceScheduledRelay(DynamicPropertyRegistry registry) {
        registry.add("shop.payment.outbox.poll-millis", () -> "3600000");
    }

    @Autowired OutboxEventRepository outboxRepo;
    @Autowired PaymentOutboxRelay relay;

    @Value("${shop.kafka.bootstrap-servers}")
    String bootstrapServers;

    @BeforeEach
    void clearOutbox() {
        outboxRepo.deleteAll();
    }

    @Test
    void eventsOfOnePayment_keepTheirOrderAndStayOnOnePartition() throws Exception {
        String topic = "shop.payment.partitiontest." + UUID.randomUUID().toString().replace("-", "");
        createTopic(topic, 4);
        UUID payment = UUID.randomUUID();
        for (int sequence = 0; sequence < 12; sequence++) {
            seed(topic, payment, sequence);
        }
        UUID secondPayment = UUID.randomUUID();
        seed(topic, secondPayment, 100);
        seed(topic, secondPayment, 101);
        UUID thirdPayment = UUID.randomUUID();
        seed(topic, thirdPayment, 200);

        relay.relay();

        List<ConsumerRecord<String, String>> records = consume(topic, 15);

        assertThat(records).as("every seeded payment event must reach the broker").hasSize(15);

        List<ConsumerRecord<String, String>> sameAggregate = records.stream()
                .filter(record -> record.key().equals(payment.toString()))
                .toList();

        assertThat(sameAggregate).hasSize(12);
        assertThat(sameAggregate.stream().map(ConsumerRecord::partition).distinct().toList())
                .as("the events of one payment must all land on the same partition")
                .hasSize(1);

        List<Integer> sequence = sameAggregate.stream()
                .sorted(Comparator.comparingLong(ConsumerRecord::offset))
                .map(record -> Integer.parseInt(record.value()))
                .toList();

        assertThat(sequence)
                .as("the events of one payment must be published in creation order")
                .containsExactly(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11);
    }

    private void createTopic(String topic, int partitions) throws Exception {
        try (AdminClient admin = AdminClient.create(
                Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers))) {
            admin.describeCluster().nodes().get(60, TimeUnit.SECONDS);
            NewTopic newTopic = new NewTopic(topic, partitions, (short) 1);
            for (int attempt = 0; attempt < 5; attempt++) {
                try {
                    admin.createTopics(List.of(newTopic)).all().get(60, TimeUnit.SECONDS);
                    return;
                } catch (ExecutionException e) {
                    if (e.getCause() instanceof TopicExistsException) {
                        return;
                    }
                    Thread.sleep(1_000L);
                } catch (TimeoutException e) {
                    Thread.sleep(1_000L);
                }
            }
            throw new IllegalStateException("the topic " + topic + " could not be created");
        }
    }

    private List<ConsumerRecord<String, String>> consume(String topic, int expected) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "payment-partition-it-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        List<ConsumerRecord<String, String>> records = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(topic));
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (records.size() < expected && System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(500)).forEach(records::add);
            }
        }
        return records;
    }

    private void seed(String topic, UUID payment, int sequence) {
        outboxRepo.saveAndFlush(OutboxEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .aggregateType("payment")
                .aggregateId(payment)
                .eventType("payment.captured.v1")
                .topic(topic)
                .payload(String.valueOf(sequence))
                .status(OutboxStatus.PENDING)
                .retryCount(0)
                .build());
    }
}
