package com.shop.searchservice.search;

import com.shop.searchservice.kafka.ProductLifecycleEvent;

import java.time.Instant;
import java.time.format.DateTimeParseException;

public final class ProductEventVersion {

    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final long MAX_EPOCH_SECOND = 9_000_000_000L;

    private ProductEventVersion() {
    }

    public static Long forEvent(ProductLifecycleEvent event) {
        Long version = forTimestamp(event.occurredAt());
        return version != null ? version : forTimestamp(event.updatedAt());
    }

    public static Long forTimestamp(String timestamp) {
        if (timestamp == null || timestamp.isBlank()) {
            return null;
        }
        Instant instant;
        try {
            instant = Instant.parse(timestamp);
        } catch (DateTimeParseException ex) {
            return null;
        }
        return forInstant(instant);
    }

    public static Long forInstant(Instant instant) {
        long epochSecond = instant.getEpochSecond();
        if (epochSecond < 1 || epochSecond > MAX_EPOCH_SECOND) {
            return null;
        }
        long version = epochSecond * NANOS_PER_SECOND + instant.getNano();
        return version < 1 ? null : version;
    }
}
