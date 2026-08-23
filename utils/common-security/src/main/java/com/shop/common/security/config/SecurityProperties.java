package com.shop.common.security.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.util.List;

/**
 * Security configuration bound from {@code shop.security.*}.
 *
 * <p>Each service can override any field via {@code application.yml} or
 * environment variables (e.g. {@code SHOP_SECURITY_ISSUER_URI}). Defaults are
 * safe but deliberately conservative — JWT validation is on, CSRF is off, the
 * session is stateless, and CORS only allows the configured origins.</p>
 *
 * <h3>Example</h3>
 * <pre>{@code
 * shop:
 *   security:
 *     enabled: true
 *     issuer-uri: http://keycloak:8080/realms/ecommerce
 *     public-endpoints:
 *       - /api/v1/auth/login
 *       - /api/v1/auth/refresh
 *     cors:
 *       enabled: true
 *       allowed-origin-patterns: ["http://localhost:3000"]
 * }</pre>
 *
 * @param enabled            master switch — when {@code false} the autoconfiguration
 *                           backs off so a service can wire its own security stack
 * @param issuerUri          Keycloak realm issuer URI used to resolve the JWK set
 * @param csrfDisabled       whether to disable CSRF protection (recommended for
 *                           stateless JWT resource servers)
 * @param statelessSession   whether to force {@code SessionCreationPolicy.STATELESS}
 * @param publicEndpoints    service-specific allow-list merged with platform defaults
 * @param cors               CORS sub-configuration
 */
@Validated
@ConfigurationProperties(prefix = "shop.security")
public record SecurityProperties(
        @DefaultValue("true") boolean enabled,
        @NotBlank String issuerUri,
        @DefaultValue("true") boolean csrfDisabled,
        @DefaultValue("true") boolean statelessSession,
        @DefaultValue List<String> publicEndpoints,
        @Valid @DefaultValue Cors cors
) {

    /**
     * CORS sub-properties. Kept as a nested record so callers can override
     * CORS without restating the security block.
     */
    @Validated
    public record Cors(
            @DefaultValue("true") boolean enabled,
            List<@NotBlank String> allowedOriginPatterns,
            List<@NotBlank String> allowedMethods,
            List<@NotBlank String> allowedHeaders,
            List<@NotBlank String> exposedHeaders,
            @DefaultValue("false") boolean allowCredentials,
            @DefaultValue("3600") @Min(0) @Positive long maxAgeSeconds
    ) {

        /** Default methods applied when none are configured explicitly. */
        public List<String> resolvedAllowedMethods() {
            return (allowedMethods == null || allowedMethods.isEmpty())
                    ? List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                    : allowedMethods;
        }

        /** Default headers applied when none are configured explicitly. */
        public List<String> resolvedAllowedHeaders() {
            return (allowedHeaders == null || allowedHeaders.isEmpty())
                    ? List.of("*")
                    : allowedHeaders;
        }

        /** Origin patterns applied when none are configured explicitly. */
        public List<String> resolvedAllowedOriginPatterns() {
            return (allowedOriginPatterns == null || allowedOriginPatterns.isEmpty())
                    ? List.of("*")
                    : allowedOriginPatterns;
        }
    }

    /** Convenience constructor used by Spring Boot's relaxed binding. */
    public SecurityProperties {
        if (publicEndpoints == null) {
            publicEndpoints = List.of();
        }
        if (cors == null) {
            cors = new Cors(true, List.of("*"), List.of(), List.of("*"), List.of(), false, 3600L);
        }
    }

    /** Effective allow-list = service-provided paths ∪ platform defaults. */
    public List<String> resolvedPublicPaths() {
        return java.util.stream.Stream
                .concat(PlatformDefaults.PUBLIC_PATHS.stream(), publicEndpoints.stream())
                .distinct()
                .toList();
    }

    /** Issuer URI parsed as a {@link URI}; throws if malformed. */
    public URI parsedIssuerUri() {
        return URI.create(issuerUri);
    }

    /**
     * Platform-wide endpoints that are always public — actuator health/info,
     * OpenAPI documentation, and the OAuth2 handshake endpoints used by the
     * browser during the redirect dance. Keep this list narrow: any new entry
     * here is anonymous-accessible across the entire fleet.
     */
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
