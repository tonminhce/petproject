package com.shop.common.logging.audit;

import java.time.Instant;
import java.util.Objects;

public record AuditEvent(
        Instant timestamp,
        String actorId,
        String actorType,
        String action,
        String resourceType,
        String resourceId,
        String outcome,
        String correlationId,
        String traceId
) {

    public static final String OUTCOME_SUCCESS = "success";
    public static final String OUTCOME_FAIL = "fail";
    public static final String ACTOR_TYPE_USER = "user";
    public static final String ACTOR_TYPE_SERVICE = "service";

    public AuditEvent {
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(resourceType, "resourceType");
        Objects.requireNonNull(outcome, "outcome");
    }

    public String toJson() {
        return new StringBuilder(256)
                .append('{')
                .append("\"timestamp\":\"").append(timestamp.toString()).append("\",")
                .append("\"actor\":{\"id\":").append(json(actorId))
                .append(",\"type\":").append(json(actorType)).append("},")
                .append("\"action\":").append(json(action)).append(',')
                .append("\"resourceType\":").append(json(resourceType)).append(',')
                .append("\"resourceId\":").append(json(resourceId)).append(',')
                .append("\"outcome\":").append(json(outcome)).append(',')
                .append("\"correlationId\":").append(json(correlationId)).append(',')
                .append("\"traceId\":").append(json(traceId))
                .append('}')
                .toString();
    }

    private static String json(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(value.length() + 8).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}
