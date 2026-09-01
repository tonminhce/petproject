package com.shop.gateway.filter;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * D4 — bucket4j edge rate-limit scopes. Buckets are in-process
 * (single-instance V1; Redis-backed buckets when the gateway scales).
 *
 * <ul>
 *   <li>Backoffice prefixes ({@code /api/v1/backoffice/**}): 10 req/min/IP</li>
 *   <li>Search prefix ({@code /api/v1/search/**}): 60 req/min/IP</li>
 *   <li>Everything else: unlimited</li>
 * </ul>
 */
@Validated
@ConfigurationProperties(prefix = "gateway.edge-rate-limit")
public record EdgeRateLimitProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("10") @Positive int backofficeRequestsPerMinute,
        @DefaultValue("60") @Positive int searchRequestsPerMinute
) {
}
