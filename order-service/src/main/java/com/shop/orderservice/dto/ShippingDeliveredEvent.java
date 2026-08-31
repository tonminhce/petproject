package com.shop.orderservice.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ShippingDeliveredEvent {

    private String eventId;
    private String eventType;
    private String occurredAt;
    private UUID orderId;
    private UUID shipmentId;
    private String carrier;
    private String trackingNumber;
    private Boolean autoDelivered;
}
