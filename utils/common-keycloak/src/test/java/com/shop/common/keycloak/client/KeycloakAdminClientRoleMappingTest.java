package com.shop.common.keycloak.client;

import com.shop.common.keycloak.exception.KeycloakClientException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * F-075: the realm role mappings of the Keycloak admin client.
 *
 * <p>The admin role API of auth-service used to write the local mirror only,
 * so a granted role never reached the realm - and the token of the user kept
 * the old authorities. These tests pin the two calls that actually change the
 * realm, and the strict lookup: a role that the realm does not know must
 * fail instead of being skipped silently.</p>
 */
class KeycloakAdminClientRoleMappingTest {

    private static final String TOKEN_ENDPOINT = "http://keycloak/realms/master/protocol/openid-connect/token";
    private static final String USERS_ENDPOINT = "http://keycloak/admin/realms/ecommerce/users";
    private static final String ROLES_ENDPOINT = "http://keycloak/admin/realms/ecommerce/roles";

    private MockRestServiceServer server;
    private KeycloakAdminClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new KeycloakAdminClient(builder, TOKEN_ENDPOINT, USERS_ENDPOINT, ROLES_ENDPOINT,
                "admin-cli", "admin", "admin-password");
    }

    private void expectAdminToken() {
        server.expect(requestTo(TOKEN_ENDPOINT))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"access_token\":\"admin-token\",\"expires_in\":300}",
                        MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("assigning a realm role posts the role to the user role mappings")
    void assigningARolePostsTheRoleMapping() {
        expectAdminToken();
        server.expect(requestTo(ROLES_ENDPOINT + "/MANAGER"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"id\":\"role-1\",\"name\":\"MANAGER\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(USERS_ENDPOINT + "/kc-123/role-mappings/realm"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess());

        client.assignRealmRole("kc-123", "MANAGER");

        server.verify();
    }

    @Test
    @DisplayName("removing a realm role deletes the role mapping")
    void removingARoleDeletesTheRoleMapping() {
        expectAdminToken();
        server.expect(requestTo(ROLES_ENDPOINT + "/MANAGER"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"id\":\"role-1\",\"name\":\"MANAGER\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(USERS_ENDPOINT + "/kc-123/role-mappings/realm"))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withSuccess());

        client.removeRealmRole("kc-123", "MANAGER");

        server.verify();
    }

    @Test
    @DisplayName("a role that the realm does not know fails instead of being skipped")
    void aRoleThatTheRealmDoesNotKnowFails() {
        expectAdminToken();
        server.expect(requestTo(ROLES_ENDPOINT + "/PM"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(org.springframework.http.HttpStatus.NOT_FOUND)
                        .body("{\"error\":\"Role not found\"}"));

        assertThatThrownBy(() -> client.assignRealmRole("kc-123", "PM"))
                .isInstanceOfSatisfying(KeycloakClientException.class, ex ->
                        assertThat(ex.getStatus()).isEqualTo(org.springframework.http.HttpStatus.NOT_FOUND));
    }

    @Test
    @DisplayName("creating a user with a role the realm does not know fails instead of creating a user with fewer authorities")
    void creatingAUserWithAnUnknownRoleFails() {
        expectAdminToken();
        server.expect(requestTo(USERS_ENDPOINT))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"id\":\"kc-123\"}", MediaType.APPLICATION_JSON)
                        .header("Location", USERS_ENDPOINT + "/kc-123"));
        server.expect(requestTo(ROLES_ENDPOINT + "/PM"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(org.springframework.http.HttpStatus.NOT_FOUND)
                        .body("{\"error\":\"Role not found\"}"));

        assertThatThrownBy(() -> client.createUser("bob", "bob@example.com", "Bob", "secret",
                java.util.List.of("PM")))
                .isInstanceOf(KeycloakClientException.class);
    }
}
