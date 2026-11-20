package com.shop.common.keycloak.config;

import com.shop.common.keycloak.client.KeycloakAdminClient;
import com.shop.common.keycloak.client.KeycloakTokenClient;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapSetter;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
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
    private static final Duration STANDALONE_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration STANDALONE_READ_TIMEOUT = Duration.ofSeconds(5);

    @Bean
    @ConditionalOnMissingBean(KeycloakTokenClient.class)
    public KeycloakTokenClient keycloakTokenClient(KeycloakProperties properties,
                                                   ObjectProvider<RestClient.Builder> platformBuilders) {
        return new KeycloakTokenClient(
                platformRestClient(platformBuilders.getIfAvailable()),
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
    public KeycloakAdminClient keycloakAdminClient(KeycloakProperties properties,
                                                   ObjectProvider<RestClient.Builder> platformBuilders) {
        return new KeycloakAdminClient(
                platformRestClient(platformBuilders.getIfAvailable()),
                properties.adminTokenEndpoint(),
                properties.usersEndpoint(),
                properties.rolesEndpoint(),
                properties.getAdminClientId(),
                properties.getServiceAccountClientId(),
                properties.getAdminUsername(),
                properties.getAdminPassword(),
                properties.getAdminClientSecret(),
                properties.serviceAccountTokenEndpoint(),
                properties.clientsEndpoint()
        );
    }

    private static RestClient.Builder platformRestClient(RestClient.Builder platformBuilder) {
        if (platformBuilder == null) {
            return traceAware(standaloneRestClientBuilder());
        }
        return traceAware(platformBuilder.clone());
    }

    private static RestClient.Builder standaloneRestClientBuilder() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(STANDALONE_CONNECT_TIMEOUT);
        requestFactory.setReadTimeout(STANDALONE_READ_TIMEOUT);
        return RestClient.builder().requestFactory(requestFactory);
    }

    private static RestClient.Builder traceAware(RestClient.Builder builder) {
        return builder.requestInitializer(req -> {
            Context current = Context.current();
            if (Span.fromContext(current).getSpanContext().isValid()) {
                W3CTraceContextPropagator.getInstance().inject(current, req.getHeaders(), TRACE_HEADERS_SETTER);
            }
        });
    }
}
