package com.shop.common.keycloak.client;

import com.shop.common.keycloak.config.KeycloakProperties;
import com.shop.common.keycloak.exception.KeycloakOperationException;
import org.keycloak.OAuth2Constants;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Builds {@link Keycloak} admin-client instances from {@link KeycloakProperties}.
 *
 * <p>Two grant modes are supported:</p>
 * <ul>
 *   <li><b>Password</b> — username + password against the configured admin realm.
 *       Default and the most ergonomic during development.</li>
 *   <li><b>Client credentials</b> — service-account style using {@code clientId} +
 *       {@code clientSecret}. Preferred in production because it never embeds
 *       a personal password in the configuration.</li>
 * </ul>
 *
 * <p>The factory prefers client-credentials when both a username <em>and</em>
 * a client secret are configured. Otherwise it falls back to the password
 * grant. The {@code client_credentials} flag in {@link KeycloakProperties}
 * forces the latter path explicitly when both are available.</p>
 *
 * <p>This class is stateless; {@link #create(KeycloakProperties)} may be
 * called multiple times for different realms in a multi-tenant service.</p>
 */
@Component
public class KeycloakClientFactory {

    private static final Logger log = LoggerFactory.getLogger(KeycloakClientFactory.class);

    /**
     * Build a {@link Keycloak} client for the {@code properties.realm()} configured
     * in the given properties. Throws {@link KeycloakOperationException} when
     * credentials are missing.
     */
    public Keycloak create(KeycloakProperties properties) {
        var admin = properties.admin();
        KeycloakBuilder builder = KeycloakBuilder.builder()
                .serverUrl(properties.serverUrl())
                .realm(admin.realm())
                .clientId(admin.clientId());

        boolean hasSecret = StringUtils.hasText(admin.clientSecret());
        boolean hasPassword = StringUtils.hasText(admin.password())
                && StringUtils.hasText(admin.username());

        if (hasSecret && hasPassword) {
            log.warn("KeycloakClientFactory: both client-secret and admin password are set; "
                    + "preferring client-credentials grant");
        }

        if (hasSecret) {
            builder.grantType(OAuth2Constants.CLIENT_CREDENTIALS)
                    .clientSecret(admin.clientSecret());
        } else if (hasPassword) {
            builder.grantType(OAuth2Constants.PASSWORD)
                    .username(admin.username())
                    .password(admin.password());
        } else {
            throw new KeycloakOperationException("createKeycloak",
                    "Keycloak admin client requires either client-secret or username+password");
        }
        return builder.build();
    }

    /**
     * Build a {@link Keycloak} client pinned to a specific realm — used by
     * tenant-aware services that manage users in realms other than the
     * configured default.
     */
    public Keycloak createForRealm(KeycloakProperties properties, String realm) {
        return KeycloakBuilder.builder()
                .serverUrl(properties.serverUrl())
                .realm(realm)
                .clientId(properties.admin().clientId())
                .clientSecret(properties.admin().clientSecret())
                .grantType(OAuth2Constants.CLIENT_CREDENTIALS)
                .build();
    }
}
