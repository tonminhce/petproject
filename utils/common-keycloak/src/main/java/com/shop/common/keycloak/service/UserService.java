package com.shop.common.keycloak.service;

import com.shop.common.keycloak.client.KeycloakAdminClient;
import com.shop.common.keycloak.exception.KeycloakOperationException;
import com.shop.common.keycloak.model.KeycloakCredential;
import com.shop.common.keycloak.model.KeycloakUser;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * High-level operations for Keycloak users inside the configured realm.
 *
 * <p>Wraps {@code org.keycloak.admin.client.resource.UsersResource} with
 * domain-shaped signatures: returns the project's {@link KeycloakUser} record
 * instead of Keycloak's internal {@link UserRepresentation}, and translates
 * JAX-RS responses into {@link KeycloakOperationException}.</p>
 *
 * <p>Tenant isolation: every method targets the realm configured on the
 * underlying {@link KeycloakAdminClient} — cross-realm calls require a
 * separate admin client built via {@code KeycloakClientFactory.createForRealm}.</p>
 */
@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final KeycloakAdminClient adminClient;

    public UserService(KeycloakAdminClient adminClient) {
        this.adminClient = adminClient;
    }

    /** Look up a user by Keycloak id. Empty when the user no longer exists. */
    public Optional<KeycloakUser> findById(String userId) {
        Objects.requireNonNull(userId, "userId");
        try {
            UserRepresentation rep = adminClient.keycloak().realm(adminClient.realm())
                    .users().get(userId).toRepresentation();
            return Optional.of(toModel(rep));
        } catch (NotFoundException ex) {
            return Optional.empty();
        }
    }

    /** Look up a user by username. Returns at most one result (Keycloak enforces uniqueness). */
    public Optional<KeycloakUser> findByUsername(String username) {
        Objects.requireNonNull(username, "username");
        List<UserRepresentation> matches = adminClient.keycloak().realm(adminClient.realm())
                .users().searchByUsername(username, true);
        if (matches.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(toModel(matches.get(0)));
    }

    /** Look up a user by email address. */
    public Optional<KeycloakUser> findByEmail(String email) {
        Objects.requireNonNull(email, "email");
        List<UserRepresentation> matches = adminClient.keycloak().realm(adminClient.realm())
                .users().searchByEmail(email, true);
        if (matches.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(toModel(matches.get(0)));
    }

    /** Paginated user listing — {@code first} is zero-based. */
    public List<KeycloakUser> list(int first, int max) {
        if (first < 0) {
            throw new IllegalArgumentException("first must be >= 0");
        }
        if (max <= 0 || max > 1000) {
            throw new IllegalArgumentException("max must be between 1 and 1000");
        }
        List<UserRepresentation> raw = adminClient.keycloak().realm(adminClient.realm())
                .users().list(first, max);
        return raw.stream().map(UserService::toModel).toList();
    }

    /**
     * Create a new user and return its Keycloak id. Password is required because
     * Keycloak rejects user creation without a credential when the realm policy
     * forbids empty passwords (the default).
     *
     * @return the Keycloak user id (UUID-shaped)
     */
    public String create(String username, String email, String firstName, String lastName,
                         String password, boolean emailVerified) {
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(email, "email");
        Objects.requireNonNull(password, "password");

        UserRepresentation rep = new UserRepresentation();
        rep.setUsername(username);
        rep.setEmail(email);
        rep.setFirstName(orEmpty(firstName));
        rep.setLastName(orEmpty(lastName));
        rep.setEnabled(true);
        rep.setEmailVerified(emailVerified);
        rep.setCredentials(List.of(toCredential(KeycloakCredential.password(password))));

        try (Response response = adminClient.keycloak().realm(adminClient.realm())
                .users().create(rep)) {
            int status = response.getStatus();
            if (status == 201) {
                URI location = response.getLocation();
                if (location == null) {
                    throw new KeycloakOperationException("createUser",
                            "Keycloak returned 201 without a Location header");
                }
                String path = location.getPath();
                String id = path.substring(path.lastIndexOf('/') + 1);
                log.info("Created Keycloak user '{}' (id={})", username, id);
                return id;
            }
            String body = response.readEntity(String.class);
            throw new KeycloakOperationException("createUser",
                    KeycloakAdminClient.toHttpStatus(status),
                    "Create user failed: HTTP %d — %s".formatted(status, body));
        }
    }

    /** Replace the user's mutable fields. Passwords must be set via {@link #resetPassword}. */
    public void update(String userId, String firstName, String lastName, String email,
                       Boolean enabled, Boolean emailVerified) {
        Objects.requireNonNull(userId, "userId");

        UserRepresentation patch = new UserRepresentation();
        if (firstName != null) {
            patch.setFirstName(firstName);
        }
        if (lastName != null) {
            patch.setLastName(lastName);
        }
        if (email != null) {
            patch.setEmail(email);
        }
        if (enabled != null) {
            patch.setEnabled(enabled);
        }
        if (emailVerified != null) {
            patch.setEmailVerified(emailVerified);
        }

        adminClient.keycloak().realm(adminClient.realm()).users().get(userId).update(patch);
        log.debug("Updated Keycloak user {}", userId);
    }

    /** Reset the user's password. Use {@link KeycloakCredential#temporaryPassword}
     * to force a change on next login. */
    public void resetPassword(String userId, KeycloakCredential credential) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(credential, "credential");
        CredentialRepresentation rep = toCredential(credential);
        adminClient.keycloak().realm(adminClient.realm()).users().get(userId)
                .resetPassword(rep);
        log.info("Reset password for Keycloak user {}", userId);
    }

    /** Delete a user permanently. */
    public void delete(String userId) {
        Objects.requireNonNull(userId, "userId");
        try (Response response = adminClient.keycloak().realm(adminClient.realm())
                .users().delete(userId)) {
            int status = response.getStatus();
            if (status != 204 && status != 404) {
                String body = response.readEntity(String.class);
                throw new KeycloakOperationException("deleteUser",
                        KeycloakAdminClient.toHttpStatus(status),
                        "Delete user failed: HTTP %d — %s".formatted(status, body));
            }
        }
        log.info("Deleted Keycloak user {}", userId);
    }

    /** Replace the user's realm roles with the given set. Pass an empty list to remove all. */
    public void assignRealmRoles(String userId, List<String> roleNames) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(roleNames, "roleNames");

        List<RoleRepresentation> desired = new ArrayList<>(roleNames.size());
        for (String name : roleNames) {
            Objects.requireNonNull(name, "roleName");
            RoleRepresentation role = adminClient.keycloak().realm(adminClient.realm())
                    .roles().get(name).toRepresentation();
            desired.add(role);
        }

        var roleScope = adminClient.keycloak().realm(adminClient.realm())
                .users().get(userId).roles().realmLevel();
        List<RoleRepresentation> current = roleScope.listAll();

        List<RoleRepresentation> toRemove = new ArrayList<>(current);
        toRemove.removeAll(desired);
        List<RoleRepresentation> toAdd = new ArrayList<>(desired);
        toAdd.removeAll(current);

        if (!toRemove.isEmpty()) {
            roleScope.remove(toRemove);
        }
        if (!toAdd.isEmpty()) {
            roleScope.add(toAdd);
        }
        log.debug("Assigned {} realm roles to user {} (added={}, removed={})",
                roleNames.size(), userId, toAdd.size(), toRemove.size());
    }

    /** Snapshot of the realm roles currently assigned to the user. */
    public List<String> realmRoles(String userId) {
        Objects.requireNonNull(userId, "userId");
        List<RoleRepresentation> roles = adminClient.keycloak().realm(adminClient.realm())
                .users().get(userId).roles().realmLevel().listAll();
        return roles.stream().map(RoleRepresentation::getName).toList();
    }

    // ------------------------------------------------------------------
    // Mapping helpers
    // ------------------------------------------------------------------

    private static KeycloakUser toModel(UserRepresentation rep) {
        List<String> roles = Optional.ofNullable(rep.getRealmRoles())
                .orElse(Collections.emptyList());
        return new KeycloakUser(
                rep.getId(),
                rep.getUsername(),
                rep.getEmail(),
                rep.getFirstName(),
                rep.getLastName(),
                rep.isEnabled(),
                rep.isEmailVerified(),
                rep.getCreatedTimestamp(),
                List.copyOf(roles)
        );
    }

    private static CredentialRepresentation toCredential(KeycloakCredential credential) {
        CredentialRepresentation rep = new CredentialRepresentation();
        rep.setType(credential.type());
        rep.setValue(credential.value());
        if (credential.temporary() != null) {
            rep.setTemporary(credential.temporary());
        }
        return rep;
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
