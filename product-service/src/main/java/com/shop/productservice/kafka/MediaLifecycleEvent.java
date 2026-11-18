package com.shop.productservice.kafka;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MediaLifecycleEvent(
    String eventType,
    UUID mediaId
) {}
