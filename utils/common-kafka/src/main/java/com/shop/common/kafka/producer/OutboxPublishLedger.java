package com.shop.common.kafka.producer;

import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class OutboxPublishLedger {

    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();
    private final Set<Long> published = ConcurrentHashMap.newKeySet();

    public void beginPublish(Collection<Long> eventIds) {
        if (eventIds != null) {
            inFlight.addAll(eventIds);
        }
    }

    public void confirmPublished(Collection<Long> eventIds) {
        if (eventIds != null) {
            inFlight.removeAll(eventIds);
            published.addAll(eventIds);
        }
    }

    public void settle(Collection<Long> eventIds) {
        if (eventIds != null) {
            inFlight.removeAll(eventIds);
            published.removeAll(eventIds);
        }
    }

    public Set<Long> publishedEventIds() {
        return Set.copyOf(published);
    }

    public Set<Long> inFlightEventIds() {
        return Set.copyOf(inFlight);
    }
}
