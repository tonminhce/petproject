package com.shop.common.keycloak.config;

import com.shop.common.keycloak.client.KeycloakAdminClient;
import com.shop.common.keycloak.client.KeycloakTokenClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;

/**
 * Auto-configuration for Keycloak clients.
 */
@AutoConfiguration
@ConditionalOnClass(RestClient.class)
@EnableConfigurationProperties(KeycloakProperties.class)
public class KeycloakAutoConfiguration {

    /**
     * Token client for login, refresh, logout operations.
     * Always created when Keycloak is configured.
     */
    @Bean
    @ConditionalOnMissingBean(KeycloakTokenClient.class)
    public KeycloakTokenClient keycloakTokenClient(KeycloakProperties properties) {
        return new KeycloakTokenClient(
                RestClient.builder(),
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
                RestClient.builder(),
                properties.adminTokenEndpoint(),
                properties.usersEndpoint(),
                properties.rolesEndpoint(),
                properties.getAdminClientId(),
                properties.getAdminUsername(),
                properties.getAdminPassword()
        );
    }
}
