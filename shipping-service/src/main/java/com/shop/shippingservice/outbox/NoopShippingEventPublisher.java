package com.shop.shippingservice.outbox;

import com.shop.shippingservice.entity.Shipment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class NoopShippingEventPublisher implements ShippingEventPublisher {

    @Override
    public void publishDelivered(Shipment shipment, boolean autoDelivered) {
        log.debug("No-op delivered publish for shipment {} (autoDelivered={})", shipment.getId(), autoDelivered);
    }
}
