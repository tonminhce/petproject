package com.shop.common.keycloak.config;

import com.shop.common.keycloak.client.KeycloakAdminClient;
import com.shop.common.keycloak.client.KeycloakTokenClient;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapSetter;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

/**
 * Auto-configuration for Keycloak clients.
 */
@AutoConfiguration
@ConditionalOnClass(RestClient.class)
@EnableConfigurationProperties(KeycloakProperties.class)
public class KeycloakAutoConfiguration {

    private static final TextMapSetter<HttpHeaders> TRACE_HEADERS_SETTER = HttpHeaders::set;

    /**
     * D3 — W3C traceparent propagation. common-keycloak cannot depend on
     * common-spring (cycle: common-spring depends on common-keycloak), so the
     * injection mirrors {@code TraceparentInterceptor} inline instead of
     * reusing the shared class.
     */
    private static RestClient.Builder traceAware(RestClient.Builder builder) {
        return builder.requestInitializer(req -> {
            Context current = Context.current();
            if (Span.fromContext(current).getSpanContext().isValid()) {
                W3CTraceContextPropagator.getInstance().inject(current, req.getHeaders(), TRACE_HEADERS_SETTER);
            }
        });
    }

    /**
     * Token client for login, refresh, logout operations.
     * Always created when Keycloak is configured.
     */
    @Bean
    @ConditionalOnMissingBean(KeycloakTokenClient.class)
    public KeycloakTokenClient keycloakTokenClient(KeycloakProperties properties) {
        return new KeycloakTokenClient(
                traceAware(RestClient.builder()),
                properties.tokenEndpoint(),
                properties.logoutEndpoint(),
                properties.getClientId(),
                properties.getClientSecret()
        );
    }

    /**
     * Admin client for user management operations.
     * Only created when admin credentials are configured.
     */
    @Bean
    @ConditionalOnMissingBean(KeycloakAdminClient.class)
    @ConditionalOnProperty(prefix = "shop.keycloak", name = "admin-username")
    public KeycloakAdminClient keycloakAdminClient(KeycloakProperties properties) {
        return new KeycloakAdminClient(
                traceAware(RestClient.builder()),
                properties.adminTokenEndpoint(),
                properties.usersEndpoint(),
                properties.rolesEndpoint(),
                properties.getAdminClientId(),
                properties.getAdminUsername(),
                properties.getAdminPassword()
        );
    }
}
