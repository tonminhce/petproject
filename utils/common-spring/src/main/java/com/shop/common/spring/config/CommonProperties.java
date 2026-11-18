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

    @Validated
    public record Service(
            @NotBlank String name,
            @NotBlank String description,
            @NotBlank String version
    ) {
    }

    @Validated
    public record Security(
            @DefaultValue("true") boolean enabled
    ) {
    }

    @Validated
    public record Keycloak(
            @DefaultValue("true") boolean enabled
    ) {
    }

    @Validated
    public record Logging(
            @DefaultValue("true") boolean correlationId,
            @NotBlank @DefaultValue("INFO") String level,
            @PositiveOrZero @DefaultValue("50") long performanceThresholdMs
    ) {
    }

    @Validated
    public record Kafka(
            @DefaultValue("false") boolean enabled
    ) {
    }

    @Validated
    public record Storage(
            @DefaultValue("false") boolean enabled
    ) {
    }

    @Validated
    public record Defaults(
            @Min(1) @Max(65535) @DefaultValue("8080") int serverPort,
            @DefaultValue("30s") Duration shutdownGracePeriod,
            @Min(1024) @DefaultValue("8192") int maxHttpHeaderSize
    ) {
    }
}
