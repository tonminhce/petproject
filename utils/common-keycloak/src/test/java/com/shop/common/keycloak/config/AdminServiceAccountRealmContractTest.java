package com.shop.common.keycloak.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AdminServiceAccountRealmContractTest {

    private static final Path REALM_FILE = realmFile();

    private static final List<String> REQUIRED_MANAGEMENT_ROLES =
            List.of("manage-users", "view-realm", "view-clients");

    private static Path realmFile() {
        Path module = Path.of("").toAbsolutePath();
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

    private JsonNode realm() throws IOException {
        return new ObjectMapper().readTree(Files.readString(REALM_FILE));
    }

    @Test
    @DisplayName("the realm ships the confidential admin client with a secret placeholder")
    void theRealmShipsTheConfidentialAdminClient() throws IOException {
        JsonNode client = null;
        for (JsonNode candidate : realm().path("clients")) {
            if ("ecommerce-admin".equals(candidate.path("clientId").asText())) {
                client = candidate;
            }
        }

        assertThat(client).as("the realm must ship the ecommerce-admin client").isNotNull();
        assertThat(client.path("publicClient").asBoolean()).isFalse();
        assertThat(client.path("serviceAccountsEnabled").asBoolean()).isTrue();
        assertThat(client.path("directAccessGrantsEnabled").asBoolean())
                .as("the admin client must not allow the password grant").isFalse();
        assertThat(client.path("secret").asText())
                .as("the secret must be a placeholder, never a shipped default")
                .isEqualTo("${ADMIN_SERVICE_CLIENT_SECRET}");
    }

    @Test
    @DisplayName("the admin client carries the full scope so the realm-management roles are effective")
    void theAdminClientCarriesTheFullScope() throws IOException {
        JsonNode client = null;
        for (JsonNode candidate : realm().path("clients")) {
            if ("ecommerce-admin".equals(candidate.path("clientId").asText())) {
                client = candidate;
            }
        }

        assertThat(client).as("the realm must ship the ecommerce-admin client").isNotNull();
        assertThat(client.path("fullScopeAllowed").asBoolean())
                .as("the realm-management roles of the service account are only effective with the full scope")
                .isTrue();
    }

    @Test
    @DisplayName("the service account carries exactly the realm management roles the admin path needs")
    void theServiceAccountCarriesTheRealmManagementRoles() throws IOException {
        JsonNode serviceAccount = null;
        for (JsonNode user : realm().path("users")) {
            if ("service-account-ecommerce-admin".equals(user.path("username").asText())) {
                serviceAccount = user;
            }
        }

        assertThat(serviceAccount).as("the realm must ship the admin service account").isNotNull();
        assertThat(serviceAccount.path("serviceAccountClientId").asText()).isEqualTo("ecommerce-admin");

        List<String> realmRoles = new ArrayList<>();
        for (JsonNode role : serviceAccount.path("realmRoles")) {
            realmRoles.add(role.asText());
        }
        assertThat(realmRoles)
                .as("the SERVICE role is a super-role across the fleet (F-041)")
                .doesNotContain("SERVICE");

        List<String> managementRoles = new ArrayList<>();
        for (JsonNode role : serviceAccount.path("clientRoles").path("realm-management")) {
            managementRoles.add(role.asText());
        }
        assertThat(managementRoles)
                .as("the service account must be able to manage the users, read a realm role and read a client")
                .containsAll(REQUIRED_MANAGEMENT_ROLES);
        assertThat(managementRoles)
                .as("manage-realm would let the service account reconfigure the realm - the admin path does not need it")
                .doesNotContain("manage-realm");
        assertThat(managementRoles)
                .as("realm-admin is the super-role of the whole installation - the admin path does not need it")
                .doesNotContain("realm-admin");
    }

    @Test
    @DisplayName("the default client id names a client the provisioned realm contains")
    void theDefaultClientIdNamesAProvisionedClient() throws IOException {
        String defaultClientId = new KeycloakProperties().getClientId();

        List<String> provisioned = new ArrayList<>();
        for (JsonNode client : realm().path("clients")) {
            provisioned.add(client.path("clientId").asText());
        }

        assertThat(defaultClientId)
                .as("the default client id must be a client of the provisioned realm")
                .isIn(provisioned);
    }

    @Test
    @DisplayName("the shared application.yml defaults to the provisioned public client")
    void theSharedApplicationYmlDefaultsToTheProvisionedPublicClient() throws IOException {
        Path module = Path.of("").toAbsolutePath();
        Path current = module;
        Path sharedYml = null;
        while (current != null) {
            Path probe = current.resolve("utils/common-spring/src/main/resources/application.yml");
            if (Files.exists(probe)) {
                sharedYml = probe;
                break;
            }
            current = current.getParent();
        }
        assertThat(sharedYml).as("the shared application.yml was not found").isNotNull();

        String yaml = Files.readString(sharedYml);
        assertThat(yaml)
                .as("the shared default must name the provisioned public client")
                .contains("client-id: ${KEYCLOAK_CLIENT_ID:ecommerce-client}");
        assertThat(yaml)
                .as("a client id that is never provisioned makes the posture measurement meaningless")
                .doesNotContain("shop-backend");
    }
}
