package com.shop.productservice.kafka;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

/**
 * Flattened media lifecycle envelope — the consumer's input contract is the
 * media epic spec D4 payload verbatim (7 fields; FULL snapshot-carry
 * precedent). Product-service only needs the identity + type pair to clear
 * the reference; the remaining fields are tolerated and ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MediaLifecycleEvent(
    String eventType,
    UUID mediaId
) {}
