package com.shop.common.spring.config;

import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.util.List;

@Validated
@ConfigurationProperties(prefix = "shop.web.trusted-proxy")
public record TrustedProxyProperties(
        @DefaultValue List<String> cidrs,
        @DefaultValue("0") @PositiveOrZero int hops
) {

    public TrustedProxyProperties {
        cidrs = cidrs == null
                ? List.of()
                : cidrs.stream()
                        .filter(cidr -> cidr != null && !cidr.isBlank())
                        .map(String::trim)
                        .toList();
        if (hops < 0) {
            throw new IllegalArgumentException("hops must be >= 0");
        }
    }

    public boolean trustsForwardedHeaders() {
        return hops > 0 && !cidrs.isEmpty();
    }
}
