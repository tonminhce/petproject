package com.shop.common.keycloak.service;

import com.shop.common.keycloak.client.KeycloakAdminClient;
import com.shop.common.keycloak.config.KeycloakProperties;
import org.keycloak.representations.idm.RealmRepresentation;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Optional;

/**
 * Tenant-level operations: query realm metadata, list available realms on the
 * server, and switch the active realm for tenant-aware services.
 *
 * <p>Read-only by design — creating or deleting realms is an administrative
 * operation that should be scripted outside the application.</p>
 */
@Service
public class RealmService {

    private final KeycloakAdminClient adminClient;
    private final KeycloakProperties properties;

    public RealmService(KeycloakAdminClient adminClient, KeycloakProperties properties) {
        this.adminClient = adminClient;
        this.properties = properties;
    }

    /** Metadata of the currently configured realm. Empty when the realm no longer exists. */
    public Optional<RealmRepresentation> current() {
        try {
            return Optional.of(adminClient.keycloak().realm(adminClient.realm()).toRepresentation());
        } catch (jakarta.ws.rs.NotFoundException ex) {
            return Optional.empty();
        }
    }

    /** Names of every realm on the Keycloak server (requires realm-view permission). */
    public java.util.List<String> listAllRealms() {
        return adminClient.keycloak().realms().findAll().stream()
                .map(RealmRepresentation::getRealm)
                .filter(Objects::nonNull)
                .toList();
    }

    /** Effective tenant id used for user / role operations. */
    public String currentRealm() {
        return properties.realm();
    }

    /** True when the configured realm is reachable and {@link #current()} returned metadata. */
    public boolean isCurrentRealmAccessible() {
        return current().isPresent();
    }
}
