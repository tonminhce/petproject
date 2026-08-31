package com.shop.shippingservice.webhook;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CarrierWebhookPayload {

    private String eventId;
    private String eventType;
    private String trackingNumber;
    private String carrierStatus;
}
