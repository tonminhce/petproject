package com.shop.common.keycloak.service;

import com.shop.common.keycloak.client.KeycloakAdminClient;
import com.shop.common.keycloak.exception.KeycloakOperationException;
import com.shop.common.keycloak.model.KeycloakRole;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import org.keycloak.representations.idm.RoleRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * CRUD operations for Keycloak realm roles inside the configured realm.
 *
 * <p>Wraps {@code RolesResource} / {@code RoleResource} and exposes
 * the domain-shaped {@link KeycloakRole} record. All operations target
 * the realm configured on {@link KeycloakAdminClient} — cross-realm
 * work requires a separate admin client.</p>
 */
@Service
public class RoleService {

    private static final Logger log = LoggerFactory.getLogger(RoleService.class);

    private final KeycloakAdminClient adminClient;

    public RoleService(KeycloakAdminClient adminClient) {
        this.adminClient = adminClient;
    }

    /** Look up a realm role by name. */
    public Optional<KeycloakRole> findByName(String name) {
        Objects.requireNonNull(name, "name");
        try {
            RoleRepresentation rep = adminClient.keycloak().realm(adminClient.realm())
                    .roles().get(name).toRepresentation();
            return Optional.of(toModel(rep));
        } catch (NotFoundException ex) {
            return Optional.empty();
        }
    }

    /** List all realm roles. Keycloak caps this at ~1000 roles per realm. */
    public List<KeycloakRole> list() {
        List<RoleRepresentation> raw = adminClient.keycloak().realm(adminClient.realm())
                .roles().list();
        return raw.stream().map(RoleService::toModel).toList();
    }

    /** Idempotent role creation — returns the existing role when one already exists. */
    public KeycloakRole createIfAbsent(String name, String description) {
        Objects.requireNonNull(name, "name");
        Optional<KeycloakRole> existing = findByName(name);
        if (existing.isPresent()) {
            return existing.get();
        }
        RoleRepresentation rep = new RoleRepresentation(name, description, false);
        try {
            adminClient.keycloak().realm(adminClient.realm()).roles().create(rep);
        } catch (RuntimeException ex) {
            // Raced with another caller creating the same role.
            Optional<KeycloakRole> raced = findByName(name);
            if (raced.isEmpty()) {
                throw new KeycloakOperationException("createRole",
                        "Failed to create role '%s': %s".formatted(name, ex.getMessage()), ex);
            }
            return raced.get();
        }
        log.info("Created Keycloak realm role '{}'", name);
        return findByName(name).orElseThrow(() ->
                new KeycloakOperationException("createRole",
                        "Role '%s' missing immediately after creation".formatted(name)));
    }

    /** Update the mutable fields of an existing role. */
    public void update(String name, String newName, String description) {
        Objects.requireNonNull(name, "name");
        RoleRepresentation patch = new RoleRepresentation();
        if (newName != null) {
            patch.setName(newName);
        }
        if (description != null) {
            patch.setDescription(description);
        }
        adminClient.keycloak().realm(adminClient.realm()).roles().get(name).update(patch);
        log.info("Updated Keycloak realm role '{}'", name);
    }

    /** Delete a realm role. No-op when the role does not exist. */
    public void delete(String name) {
        Objects.requireNonNull(name, "name");
        try {
            adminClient.keycloak().realm(adminClient.realm()).roles().deleteRole(name);
            log.info("Deleted Keycloak realm role '{}'", name);
        } catch (NotFoundException ex) {
            log.debug("delete('{}') — role already absent", name);
        }
    }

    // ------------------------------------------------------------------
    // Mapping helpers
    // ------------------------------------------------------------------

    private static KeycloakRole toModel(RoleRepresentation rep) {
        return new KeycloakRole(
                rep.getId(),
                rep.getName(),
                rep.getDescription(),
                rep.isComposite() ? Boolean.TRUE : null,
                rep.getClientRole(),
                rep.getContainerId()
        );
    }
}
