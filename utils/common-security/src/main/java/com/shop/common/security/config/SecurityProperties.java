package com.shop.common.security.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.http.HttpMethod;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.util.List;

@Validated
@ConfigurationProperties(prefix = "shop.security")
public record SecurityProperties(
        @DefaultValue("true") boolean enabled,
        @NotBlank String issuerUri,
        @DefaultValue("true") boolean csrfDisabled,
        @DefaultValue("true") boolean statelessSession,
        @DefaultValue List<EndpointRule> publicPaths,
        @DefaultValue List<@NotBlank String> expectedAudiences,
        @DefaultValue List<@NotBlank String> expectedClientIds,
        @Valid @DefaultValue Cors cors
) {

    public record EndpointRule(HttpMethod method, String path) {
        public EndpointRule {
            if (path == null || path.isBlank()) {
                throw new IllegalArgumentException("EndpointRule.path must not be blank");
            }
        }
    }

    @Validated
    public record Cors(
            @DefaultValue("true") boolean enabled,
            List<@NotBlank String> allowedOriginPatterns,
            List<@NotBlank String> allowedMethods,
            List<@NotBlank String> allowedHeaders,
            List<@NotBlank String> exposedHeaders,
            @DefaultValue("false") boolean allowCredentials,

            @DefaultValue("3600") @Min(0) long maxAgeSeconds
    ) {

        public List<String> resolvedAllowedMethods() {
            return (allowedMethods == null || allowedMethods.isEmpty())
                    ? List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                    : allowedMethods;
        }

        public List<String> resolvedAllowedHeaders() {
            return (allowedHeaders == null || allowedHeaders.isEmpty())
                    ? List.of("*")
                    : allowedHeaders;
        }

        public List<String> resolvedAllowedOriginPatterns() {
            return (allowedOriginPatterns == null || allowedOriginPatterns.isEmpty())
                    ? List.of("*")
                    : allowedOriginPatterns;
        }
    }

    public SecurityProperties {
        if (publicPaths == null) {
            publicPaths = List.of();
        }
        if (expectedAudiences == null) {
            expectedAudiences = List.of();
        }
        if (cors == null) {
            cors = new Cors(true, List.of("*"), List.of(), List.of("*"), List.of(), false, 3600L);
        }
    }

    public URI parsedIssuerUri() {
        return URI.create(issuerUri);
    }

    public static final class PlatformDefaults {
        public static final List<String> PUBLIC_PATHS = List.of(
                "/actuator/health",
                "/actuator/health/**",
                "/actuator/info",
                "/actuator/prometheus",
                "/v3/api-docs",
                "/v3/api-docs/**",
                "/swagger-ui.html",
                "/swagger-ui/**",
                "/swagger-resources/**",
                "/webjars/**"
        );

        private PlatformDefaults() {
        }
    }
}
