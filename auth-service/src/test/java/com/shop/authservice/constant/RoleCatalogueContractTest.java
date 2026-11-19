package com.shop.authservice.constant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-056: the local role catalogue and the Keycloak realm roles stay in step.
 *
 * <p>The realm import is the source of truth: the realm ships the roles
 * ADMIN, USER, MANAGER and SERVICE. The local catalogue used to be USER, PM
 * and ADMIN - {@code PM} does not exist in the realm (granting it changed
 * nothing at runtime) while MANAGER and SERVICE could not be granted at all
 * (the admin role API answered 400).</p>
 *
 * <p>This test reads the provisioned realm file and compares it with the
 * enum, so a realm change that is not mirrored in the catalogue fails here
 * instead of silently at the API boundary.</p>
 */
class RoleCatalogueContractTest {

    private static final Path REALM_FILE = realmFile();

    private static Path realmFile() {
        Path module = Path.of("").toAbsolutePath();
        Path candidate = module.resolve("..").resolve("docker/keycloak/import/ecommerce-realm.json");
        if (Files.exists(candidate)) {
            return candidate.normalize();
        }
        // fall back to walking up to the repository root
        Path current = module;
        while (current.getParent() != null) {
            Path probe = current.resolve("docker/keycloak/import/ecommerce-realm.json");
            if (Files.exists(probe)) {
                return probe.normalize();
            }
            current = current.getParent();
        }
        throw new IllegalStateException("the realm import file was not found");
    }

    @Test
    @DisplayName("the local catalogue contains exactly the realm roles")
    void theLocalCatalogueContainsExactlyTheRealmRoles() throws IOException {
        Set<String> realmRoles = realmRoles();

        Set<String> catalogue = new LinkedHashSet<>();
        for (RoleName name : RoleName.values()) {
            catalogue.add(name.name());
        }

        assertThat(catalogue)
            .as("the local role catalogue must match the realm roles exactly")
            .containsExactlyInAnyOrderElementsOf(realmRoles);
        assertThat(catalogue)
            .as("PM is not a realm role - granting it changed nothing at runtime")
            .doesNotContain("PM");
    }

    @Test
    @DisplayName("every role used by the provisioned users exists in the catalogue")
    void everyRoleUsedByTheProvisionedUsersExistsInTheCatalogue() throws IOException {
        JsonNode realm = readRealm();
        Set<String> catalogue = new LinkedHashSet<>();
        for (RoleName name : RoleName.values()) {
            catalogue.add(name.name());
        }

        List<String> unknown = new ArrayList<>();
        for (JsonNode user : realm.path("users")) {
            for (JsonNode role : user.path("realmRoles")) {
                String roleName = role.asText();
                if (!catalogue.contains(roleName)) {
                    unknown.add(user.path("username").asText() + " -> " + roleName);
                }
            }
        }

        assertThat(unknown)
            .as("a user provisioned with a role the catalogue does not know")
            .isEmpty();
    }

    private Set<String> realmRoles() throws IOException {
        Set<String> roles = new LinkedHashSet<>();
        for (JsonNode role : readRealm().path("roles").path("realm")) {
            roles.add(role.path("name").asText());
        }
        return roles;
    }

    private JsonNode readRealm() throws IOException {
        try (Stream<String> ignored = Files.lines(REALM_FILE)) {
            return new ObjectMapper().readTree(Files.readString(REALM_FILE));
        }
    }
}
