package com.shop.common.kafka.consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.MessageHeaders;
import tools.jackson.databind.DeserializationFeature;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BaseKafkaConsumerUnknownFieldTest {

    record ShippedAtEvent(String eventId, String eventType, Instant occurredAt, UUID orderId) {}

    static final class TestConsumer extends BaseKafkaConsumer<String, ShippedAtEvent> {

        final List<ShippedAtEvent> handled = new CopyOnWriteArrayList<>();

        void onMessage(String rawValue, MessageHeaders headers) {
            processMessage(rawValue, headers, ShippedAtEvent.class, handled::add);
        }
    }

    private static final UUID ORDER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private TestConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new TestConsumer();
    }

    @Test
    @DisplayName("the wire mapper is configured like the application mapper - unknown properties are tolerated")
    void wireMapperToleratesUnknownProperties() {
        assertThat(KafkaWireMapper.mapper().isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES))
                .as("the Kafka wire mapper must not reject an additive change to an event contract")
                .isFalse();
    }

    @Test
    @DisplayName("a record with an extra unknown field binds and reaches the handler")
    void recordWithAnExtraUnknownFieldIsHandled() {
        String json = "{\"eventId\":\"e-1\",\"eventType\":\"shipping.delivered.v1\","
                + "\"occurredAt\":\"2026-09-01T10:00:00Z\",\"orderId\":\"" + ORDER_ID + "\","
                + "\"addedByTheNextProducerVersion\":42}";

        consumer.onMessage(json, new MessageHeaders(new HashMap<>()));

        assertThat(consumer.handled).hasSize(1);
        ShippedAtEvent event = consumer.handled.get(0);
        assertThat(event.eventId()).isEqualTo("e-1");
        assertThat(event.occurredAt()).isEqualTo(Instant.parse("2026-09-01T10:00:00Z"));
        assertThat(event.orderId()).isEqualTo(ORDER_ID);
    }

    @Test
    @DisplayName("a record with an extra unknown field binds through the legacy double-encoded wire token")
    void doubleEncodedRecordWithAnExtraUnknownFieldIsHandled() {
        String token = "\"{\\\"eventId\\\":\\\"e-2\\\",\\\"eventType\\\":\\\"shipping.delivered.v1\\\","
                + "\\\"occurredAt\\\":\\\"2026-09-01T10:00:00Z\\\",\\\"orderId\\\":\\\"" + ORDER_ID + "\\\","
                + "\\\"addedByTheNextProducerVersion\\\":42}\"";

        consumer.onMessage(token, new MessageHeaders(new HashMap<>()));

        assertThat(consumer.handled).hasSize(1);
        assertThat(consumer.handled.get(0).eventId()).isEqualTo("e-2");
    }

    @Test
    @DisplayName("a genuinely malformed record is still rejected so it reaches the dead-letter path")
    void malformedRecordIsRejected() {
        assertThatThrownBy(() -> consumer.onMessage("{ this is not json }", new MessageHeaders(new HashMap<>())))
                .isInstanceOf(PoisonRecordException.class);
        assertThatThrownBy(() -> consumer.onMessage("", new MessageHeaders(new HashMap<>())))
                .isInstanceOf(PoisonRecordException.class);

        assertThat(consumer.handled).isEmpty();
    }

    @Test
    @DisplayName("a record whose known field has an incompatible type is still rejected")
    void typeIncompatibleRecordIsRejected() {
        String json = "{\"eventId\":\"e-3\",\"eventType\":\"shipping.delivered.v1\","
                + "\"occurredAt\":\"definitely-not-a-date\",\"orderId\":\"" + ORDER_ID + "\"}";

        assertThatThrownBy(() -> consumer.onMessage(json, new MessageHeaders(new HashMap<>())))
                .isInstanceOf(PoisonRecordException.class);

        assertThat(consumer.handled).isEmpty();
    }
}
