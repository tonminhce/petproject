package com.shop.shippingservice.service;

import com.shop.shippingservice.constant.ShipmentStatus;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

@Component
public class ShippingMetrics {

    private final MeterRegistry registry;
    private final AtomicInteger staleInflightCount = new AtomicInteger(0);

    public ShippingMetrics(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder("shipping.stale.inflight", staleInflightCount, AtomicInteger::get)
                .register(registry);
    }

    public void recordDelivered(boolean autoDelivered) {
        registry.counter("shipping.delivered.count", "auto", Boolean.toString(autoDelivered)).increment();
    }

    public void recordFailed() {
        registry.counter("shipping.failed.count").increment();
    }

    public void recordAdvance(ShipmentStatus from, ShipmentStatus to) {
        registry.counter("shipping.advance.count", "from", from.name(), "to", to.name()).increment();
    }

    public void setStaleInflightCount(int count) {
        staleInflightCount.set(count);
    }
}
