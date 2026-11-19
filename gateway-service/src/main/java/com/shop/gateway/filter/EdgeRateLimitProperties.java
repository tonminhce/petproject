package com.shop.gateway.filter;

import jakarta.validation.constraints.Positive;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * <ul>
 *   <li>Backoffice prefixes ({@code /api/v1/backoffice/**}): 10 req/min/IP</li>
 *   <li>Search prefix ({@code /api/v1/search/**}): 60 req/min/IP</li>
 *   <li>Everything else: unlimited</li>
 * </ul>
 *
 * <p>F-034: the budget is fleet-wide — the bucket lives in Redis, so the
 * numbers above are the limits of the whole gateway fleet and not of a single
 * replica.</p>
 */
@Validated
@ConfigurationProperties(prefix = "gateway.edge-rate-limit")
public record EdgeRateLimitProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("10") @Positive int backofficeRequestsPerMinute,
        @DefaultValue("60") @Positive int searchRequestsPerMinute
) {
    @ConstructorBinding
    public EdgeRateLimitProperties {
    }

}
