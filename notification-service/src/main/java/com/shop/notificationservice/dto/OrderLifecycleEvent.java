package com.shop.notificationservice.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class OrderLifecycleEvent {

    private String eventId;
    private String eventType;
    private String occurredAt;
    private UUID orderId;
    private UUID userId;
    private String status;
    private Instant transitionedAt;
    private Instant cancelledAt;
    private Boolean refunded;
    private List<Map<String, Object>> items;
}
