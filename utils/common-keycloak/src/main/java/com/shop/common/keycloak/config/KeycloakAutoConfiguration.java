package com.shop.common.keycloak.config;

import com.shop.common.keycloak.client.KeycloakAdminClient;
import com.shop.common.keycloak.client.KeycloakTokenClient;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapSetter;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@AutoConfiguration
@ConditionalOnClass(RestClient.class)
@EnableConfigurationProperties(KeycloakProperties.class)
public class KeycloakAutoConfiguration {

    private static final TextMapSetter<HttpHeaders> TRACE_HEADERS_SETTER = HttpHeaders::set;

    private static RestClient.Builder traceAware(RestClient.Builder builder) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(5));
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        return builder.requestFactory(requestFactory).requestInitializer(req -> {
            Context current = Context.current();
            if (Span.fromContext(current).getSpanContext().isValid()) {
                W3CTraceContextPropagator.getInstance().inject(current, req.getHeaders(), TRACE_HEADERS_SETTER);
            }
        });
    }

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

    @Bean
    @ConditionalOnMissingBean(KeycloakAdminClient.class)
    @ConditionalOnExpression("'${shop.keycloak.admin-username:}' != '' "
            + "|| '${shop.keycloak.admin-client-secret:}' != ''")
    public KeycloakAdminClient keycloakAdminClient(KeycloakProperties properties) {
        return new KeycloakAdminClient(
                traceAware(RestClient.builder()),
                properties.adminTokenEndpoint(),
                properties.usersEndpoint(),
                properties.rolesEndpoint(),
                properties.getAdminClientId(),
                properties.getAdminUsername(),
                properties.getAdminPassword(),
                properties.getAdminClientSecret(),
                properties.serviceAccountTokenEndpoint(),
                properties.clientsEndpoint()
        );
    }
}
