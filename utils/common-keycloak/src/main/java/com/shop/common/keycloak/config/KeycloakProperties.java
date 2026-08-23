package com.shop.common.keycloak.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Configuration for the Keycloak admin client and resource-server endpoints,
 * bound from {@code shop.keycloak.*}.
 *
 * <h3>Two distinct server URLs</h3>
 * <ul>
 *   <li><b>{@code server-url}</b> — used by services running inside the cluster
 *       to talk to Keycloak (e.g. {@code http://keycloak:8080}).</li>
 *   <li><b>{@code public-server-url}</b> — used to build browser-facing URLs
 *       (authorization redirects, end-session, registration). Falls back to
 *       {@code server-url} when blank.</li>
 * </ul>
 *
 * <h3>Example</h3>
 * <pre>{@code
 * shop:
 *   keycloak:
 *     server-url: http://keycloak:8080
 *     public-server-url: http://localhost:9090
 *     realm: ecommerce
 *     admin:
 *       realm: master
 *       client-id: admin-cli
 *       username: admin
 *       password: admin
 * }</pre>
 *
 * @param enabled             master switch for the entire autoconfiguration
 * @param serverUrl           internal Keycloak base URL
 * @param publicServerUrl     browser-facing Keycloak base URL (optional)
 * @param realm               target realm for user/role operations
 * @param clientId            OAuth2 client id used for end-user token exchange
 * @param clientSecret        OAuth2 client secret (optional, for confidential clients)
 * @param admin               admin credentials for the Keycloak Admin REST API
 * @param timeout             HTTP timeout for Keycloak Admin REST calls
 */
@Validated
@ConfigurationProperties(prefix = "shop.keycloak")
public record KeycloakProperties(
        @DefaultValue("true") boolean enabled,
        @NotBlank String serverUrl,
        String publicServerUrl,
        @NotBlank String realm,
        @NotBlank String clientId,
        String clientSecret,
        @Valid @DefaultValue Admin admin,
        @Valid @DefaultValue Timeout timeout
) {

    /** Admin REST credentials. Empty fields disable admin operations. */
    @Validated
    public record Admin(
            @DefaultValue("master") String realm,
            @DefaultValue("admin-cli") String clientId,
            String username,
            String password,
            String clientSecret
    ) {
    }

    /** Tunable HTTP timeout for Keycloak Admin REST calls. */
    @Validated
    public record Timeout(
            @DefaultValue("5s") Duration connect,
            @DefaultValue("30s") Duration read,
            @Min(1) @DefaultValue("100") int poolMaxTotal,
            @Min(1) @DefaultValue("20") int poolMaxPerRoute
    ) {
    }

    /** Convenience constructor used by Spring Boot relaxed binding. */
    public KeycloakProperties {
        if (admin == null) {
            admin = new Admin("master", "admin-cli", null, null, null);
        }
        if (timeout == null) {
            timeout = new Timeout(Duration.ofSeconds(5), Duration.ofSeconds(30), 100, 20);
        }
        if (publicServerUrl == null || publicServerUrl.isBlank()) {
            publicServerUrl = serverUrl;
        }
    }

    /** Effective public-facing URL (used for browser redirects). */
    public String publicServerUrl() {
        return (publicServerUrl == null || publicServerUrl.isBlank()) ? serverUrl : publicServerUrl;
    }

    /** Whether admin operations are configured (username and password both set). */
    public boolean hasAdminCredentials() {
        return admin != null
                && notBlank(admin.username())
                && notBlank(admin.password());
    }

    /**
     * OIDC issuer URI for the configured realm — what Keycloak's
     * {@code /.well-known/openid-configuration} is rooted at and what the
     * resource server should pin its JWT decoder to.
     */
    public String issuerUri() {
        return stripTrailingSlash(serverUrl) + "/realms/" + realm;
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
