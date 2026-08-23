package com.shop.common.keycloak.config;

import com.shop.common.keycloak.client.KeycloakAdminClient;
import com.shop.common.keycloak.client.KeycloakClientFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.keycloak.admin.client.Keycloak;

/**
 * Autoconfigures the Keycloak common-library for every service that adds
 * {@code common-keycloak} to its classpath.
 *
 * <h3>Activation</h3>
 * <ul>
 *   <li>{@code shop.keycloak.enabled} defaults to {@code true}</li>
 *   <li>{@code org.keycloak.admin.client.Keycloak} must be on the classpath</li>
 *   <li>Both {@code shop.keycloak.admin.username} and {@code shop.keycloak.admin.password}
 *       OR a {@code clientSecret} must be set for the admin client to start</li>
 * </ul>
 *
 * <h3>Beans</h3>
 * <ul>
 *   <li>{@link KeycloakClientFactory} — pure factory, stateless</li>
 *   <li>{@link KeycloakAdminClient} — owns the SDK instance and translates errors</li>
 *   <li>{@code UserService}, {@code RoleService}, {@code RealmService},
 *       {@code TokenService} — domain services, registered via
 *       {@code @Service} on the classpath scan</li>
 * </ul>
 */
@AutoConfiguration
@ConditionalOnClass(Keycloak.class)
@ConditionalOnProperty(prefix = "shop.keycloak", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(KeycloakProperties.class)
public class KeycloakAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public KeycloakClientFactory keycloakClientFactory() {
        return new KeycloakClientFactory();
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public KeycloakAdminClient keycloakAdminClient(KeycloakClientFactory factory,
                                                   KeycloakProperties properties) {
        return new KeycloakAdminClient(factory, properties);
    }
}
