package com.shop.common.logging.audit;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D6 binding: the payload is EXACT — {@code timestamp, actor, action,
 * resourceType, resourceId, outcome, correlationId, traceId} in that order,
 * one compact JSON line, no other fields ever.
 */
class AuditEventTest {

    private final AuditEvent event = new AuditEvent(
            Instant.parse("2026-09-01T10:15:30Z"),
            "3f6d2f4e-8e7a-4a1b-9c0d-123456789abc",
            AuditEvent.ACTOR_TYPE_USER,
            "create",
            "product",
            "11111111-2222-3333-4444-555555555555",
            AuditEvent.OUTCOME_SUCCESS,
            "corr-1",
            "trace-1");

    @Test
    void serializesExactD6PayloadInOrder() {
        assertThat(event.toJson()).isEqualTo(
                "{\"timestamp\":\"2026-09-01T10:15:30Z\","
                        + "\"actor\":{\"id\":\"3f6d2f4e-8e7a-4a1b-9c0d-123456789abc\",\"type\":\"user\"},"
                        + "\"action\":\"create\","
                        + "\"resourceType\":\"product\","
                        + "\"resourceId\":\"11111111-2222-3333-4444-555555555555\","
                        + "\"outcome\":\"success\","
                        + "\"correlationId\":\"corr-1\","
                        + "\"traceId\":\"trace-1\"}");
    }

    @Test
    void missingResourceIdStaysPresentAsNull() {
        AuditEvent withoutResource = new AuditEvent(event.timestamp(), event.actorId(), event.actorType(),
                event.action(), event.resourceType(), null, AuditEvent.OUTCOME_FAIL,
                event.correlationId(), event.traceId());

        assertThat(withoutResource.toJson())
                .contains("\"resourceId\":null")
                .contains("\"outcome\":\"fail\"");
    }

    @Test
    void escapesJsonSpecialCharacters() {
        AuditEvent hostile = new AuditEvent(event.timestamp(), event.actorId(), event.actorType(),
                "create\"x\\y\nz", event.resourceType(), event.resourceId(),
                event.outcome(), event.correlationId(), event.traceId());

        assertThat(hostile.toJson())
                .contains("\"action\":\"create\\\"x\\\\y\\nz\"")
                .doesNotContain("create\"x");
    }

    @Test
    void serviceActorIsTyped() {
        AuditEvent service = new AuditEvent(event.timestamp(), "order-service",
                AuditEvent.ACTOR_TYPE_SERVICE, event.action(), event.resourceType(),
                event.resourceId(), event.outcome(), event.correlationId(), event.traceId());

        assertThat(service.toJson())
                .contains("\"actor\":{\"id\":\"order-service\",\"type\":\"service\"}");
    }
}
