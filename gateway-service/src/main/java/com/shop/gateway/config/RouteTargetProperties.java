package com.shop.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.Map;

@ConfigurationProperties(prefix = "gateway.route-targets")
public record RouteTargetProperties(@DefaultValue Map<String, String> overrides) {

    public RouteTargetProperties {
        overrides = overrides == null ? Map.of() : Map.copyOf(overrides);
    }

    public String resolve(final String serviceName, final String defaultUri) {
        return overrides.getOrDefault(serviceName, defaultUri);
    }
}
