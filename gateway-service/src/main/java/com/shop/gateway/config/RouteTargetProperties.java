package com.shop.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.Map;

/**
 * Per-service target-URI overrides applied on top of the default
 * {@code ServiceRoute} URIs (service discovery by compose DNS name).
 *
 * <p>Primary purpose: test/tooling environments point the route table at a
 * local stub server instead of the compose network, e.g.
 * {@code gateway.route-targets.overrides.rating-service=http://localhost:12345}.
 * Overrides are keyed by service name; unknown names are ignored.</p>
 */
@ConfigurationProperties(prefix = "gateway.route-targets")
public record RouteTargetProperties(@DefaultValue Map<String, String> overrides) {

    public RouteTargetProperties {
        overrides = overrides == null ? Map.of() : Map.copyOf(overrides);
    }

    public String resolve(final String serviceName, final String defaultUri) {
        return overrides.getOrDefault(serviceName, defaultUri);
    }
}
