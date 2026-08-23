package com.shop.common.spring.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Aggregator configuration bound from {@code shop.common.*}.
 *
 * <p>This record is the single-import handle for the most-used cross-cutting
 * settings. Each sub-record mirrors a platform module so a service can read
 * or override its top-level toggles from one bean. Detailed module-level
 * configuration still lives on the per-module records
 * ({@code shop.security.*}, {@code shop.keycloak.*}, {@code shop.kafka.*},
 * {@code shop.storage.*}, {@code shop.web.logging.performance.*}) — the
 * sub-records here only carry aggregator-level defaults that complement,
 * never duplicate, those.</p>
 *
 * <h3>Example</h3>
 * <pre>{@code
 * shop:
 *   common:
 *     service:
 *       name: product-service
 *     security:
 *       enabled: true
 *     keycloak:
 *       enabled: true
 *     logging:
 *       correlation-id: true
 *       level: INFO
 *     kafka:
 *       enabled: false
 *     storage:
 *       enabled: false
 *     defaults:
 *       server-port: 8080
 *       shutdown-grace-period: 30s
 * }</pre>
 *
 * @param service   service-level metadata (name, description)
 * @param security  platform security toggle
 * @param keycloak  platform keycloak toggle
 * @param logging   logging defaults (correlation ID, level)
 * @param kafka     platform kafka toggle
 * @param storage   platform storage toggle
 * @param defaults  infrastructure defaults (port, shutdown grace period)
 */
@Validated
@ConfigurationProperties(prefix = "shop.common")
public record CommonProperties(
        @Valid @DefaultValue Service service,
        @Valid @DefaultValue Security security,
        @Valid @DefaultValue Keycloak keycloak,
        @Valid @DefaultValue Logging logging,
        @Valid @DefaultValue Kafka kafka,
        @Valid @DefaultValue Storage storage,
        @Valid @DefaultValue Defaults defaults
) {

    /** Convenience constructor used by Spring Boot relaxed binding. */
    public CommonProperties {
        if (service == null) {
            service = new Service("shop-service", "Common library starter service", "1.0.0");
        }
        if (security == null) {
            security = new Security(true);
        }
        if (keycloak == null) {
            keycloak = new Keycloak(true);
        }
        if (logging == null) {
            logging = new Logging(true, "INFO", 50L);
        }
        if (kafka == null) {
            kafka = new Kafka(false);
        }
        if (storage == null) {
            storage = new Storage(false);
        }
        if (defaults == null) {
            defaults = new Defaults(8080, Duration.ofSeconds(30), 8 * 1024);
        }
    }

    /**
     * Service-level metadata used by health endpoints and logging.
     *
     * @param name        logical service name (overrides {@code spring.application.name})
     * @param description human-readable description surfaced via actuator
     * @param version     semver of the deployed service
     */
    @Validated
    public record Service(
            @NotBlank String name,
            @NotBlank String description,
            @NotBlank String version
    ) {
    }

    /**
     * Platform security toggle. Independent from {@code shop.security.enabled}
     * which controls the per-module security auto-configuration; this one
     * exists so operators can flip a single switch that all services honour.
     *
     * @param enabled whether the security stack is wired in for this service
     */
    @Validated
    public record Security(
            @DefaultValue("true") boolean enabled
    ) {
    }

    /**
     * Platform Keycloak toggle. Complements {@code shop.keycloak.enabled}.
     *
     * @param enabled whether the Keycloak admin client is wired in
     */
    @Validated
    public record Keycloak(
            @DefaultValue("true") boolean enabled
    ) {
    }

    /**
     * Logging defaults for the platform.
     *
     * @param correlationId        whether the correlation ID filter is active
     * @param level                default root log level (e.g. {@code INFO}, {@code DEBUG})
     * @param performanceThresholdMs minimum execution time (ms) before the AOP
     *                               performance aspect logs a call
     */
    @Validated
    public record Logging(
            @DefaultValue("true") boolean correlationId,
            @NotBlank @DefaultValue("INFO") String level,
            @PositiveOrZero @DefaultValue("50") long performanceThresholdMs
    ) {
    }

    /**
     * Platform Kafka toggle. Kafka is opt-in because not every service
     * produces or consumes events.
     *
     * @param enabled whether the Kafka producer / consumer beans are wired in
     */
    @Validated
    public record Kafka(
            @DefaultValue("false") boolean enabled
    ) {
    }

    /**
     * Platform object-storage toggle. Off by default so services that never
     * touch the bucket don't pull the S3 SDK at startup.
     *
     * @param enabled whether the {@code ObjectStorageService} is wired in
     */
    @Validated
    public record Storage(
            @DefaultValue("false") boolean enabled
    ) {
    }

    /**
     * Infrastructure defaults applied to every service.
     *
     * @param serverPort          HTTP port the embedded server binds to
     * @param shutdownGracePeriod how long graceful shutdown waits for in-flight
     *                            requests to drain before forcing termination
     * @param maxHttpHeaderSize   maximum HTTP request/response header size in bytes
     */
    @Validated
    public record Defaults(
            @Min(1) @Max(65535) @DefaultValue("8080") int serverPort,
            @DefaultValue("30s") Duration shutdownGracePeriod,
            @Min(1024) @DefaultValue("8192") int maxHttpHeaderSize
    ) {
    }
}
