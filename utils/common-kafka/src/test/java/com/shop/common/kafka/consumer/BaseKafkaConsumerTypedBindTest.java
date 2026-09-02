package com.shop.common.kafka.consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.MessageHeaders;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * H-1 typed-bind contract at the {@link BaseKafkaConsumer} boundary: the
 * raw-wire {@code processMessage} overload unwraps-once and binds the typed
 * event (ISO-8601 instants included, via jsr310) before the handler runs.
 * Decode failures are contained ack-skips — the handler is never invoked and
 * the call never throws (fleet containment: no DLT, no container crash).
 */
class BaseKafkaConsumerTypedBindTest {

    /** Mirror of a fleet lifecycle DTO: UUID identity + ISO instant fields. */
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
    @DisplayName("the sanctioned double-encoded fleet wire token binds the typed event (incl. ISO instant)")
    void doubleEncodedTokenBindsTypedEvent() {
        String token = "\"{\\\"eventId\\\":\\\"e-1\\\",\\\"eventType\\\":\\\"shipping.delivered.v1\\\","
            + "\\\"occurredAt\\\":\\\"2026-09-01T10:00:00Z\\\",\\\"orderId\\\":\\\"" + ORDER_ID + "\\\"}\"";

        consumer.onMessage(token, new MessageHeaders(new HashMap<>()));

        assertThat(consumer.handled).hasSize(1);
        ShippedAtEvent event = consumer.handled.get(0);
        assertThat(event.eventId()).isEqualTo("e-1");
        assertThat(event.eventType()).isEqualTo("shipping.delivered.v1");
        assertThat(event.occurredAt()).isEqualTo(Instant.parse("2026-09-01T10:00:00Z"));
        assertThat(event.orderId()).isEqualTo(ORDER_ID);
    }

    @Test
    @DisplayName("a future single-encoded relay payload also binds (unwrap-once tolerance)")
    void singleEncodedJsonAlsoBinds() {
        String json = "{\"eventId\":\"e-2\",\"eventType\":\"shipping.delivered.v1\","
            + "\"occurredAt\":\"2026-09-01T10:00:00Z\",\"orderId\":\"" + ORDER_ID + "\"}";

        consumer.onMessage(json, new MessageHeaders(new HashMap<>()));

        assertThat(consumer.handled).hasSize(1);
        assertThat(consumer.handled.get(0).eventId()).isEqualTo("e-2");
    }

    @Test
    @DisplayName("malformed payload is a contained ack-skip: no handler call, no throw")
    void malformedPayloadIsContainedAckSkip() {
        assertThatCode(() -> consumer.onMessage("{ this is not json }", new MessageHeaders(new HashMap<>())))
            .doesNotThrowAnyException();
        assertThatCode(() -> consumer.onMessage("", new MessageHeaders(new HashMap<>())))
            .doesNotThrowAnyException();

        assertThat(consumer.handled).isEmpty();
    }

    @Test
    @DisplayName("type-incompatible payload is a contained ack-skip (conversion failure, not handler domain)")
    void typeIncompatiblePayloadIsContainedAckSkip() {
        String json = "{\"eventId\":\"e-3\",\"eventType\":\"shipping.delivered.v1\","
            + "\"occurredAt\":\"definitely-not-a-date\",\"orderId\":\"" + ORDER_ID + "\"}";

        assertThatCode(() -> consumer.onMessage(json, new MessageHeaders(new HashMap<>())))
            .doesNotThrowAnyException();

        assertThat(consumer.handled).isEmpty();
    }

    @Test
    @DisplayName("tombstone record (null value) is a contained ack-skip")
    void tombstoneRecordIsContainedAckSkip() {
        assertThatCode(() -> consumer.onMessage(null, new MessageHeaders(new HashMap<>())))
            .doesNotThrowAnyException();

        assertThat(consumer.handled).isEmpty();
    }
}
