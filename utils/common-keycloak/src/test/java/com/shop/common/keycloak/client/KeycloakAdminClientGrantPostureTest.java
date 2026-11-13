package com.shop.common.keycloak.client;

import com.shop.common.keycloak.exception.KeycloakClientException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
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
 * F-079: the grant posture is a property of the CLIENT.
 *
 * <p>{@code isDirectAccessGrantEnabled} appended the client id to the USERS
 * endpoint. The realm answered 404 for a client id, the call threw, and the
 * posture check reported the posture as unknown on every boot: the
 * measurement never observed anything, so the migration away from ROPC had
 * no signal.</p>
 */
class KeycloakAdminClientGrantPostureTest {

    private static final String TOKEN_ENDPOINT = "http://keycloak/realms/master/protocol/openid-connect/token";
    private static final String USERS_ENDPOINT = "http://keycloak/admin/realms/ecommerce/users";
    private static final String ROLES_ENDPOINT = "http://keycloak/admin/realms/ecommerce/roles";
    private static final String CLIENTS_ENDPOINT = "http://keycloak/admin/realms/ecommerce/clients";

    private MockRestServiceServer server;
    private KeycloakAdminClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new KeycloakAdminClient(builder, TOKEN_ENDPOINT, USERS_ENDPOINT, ROLES_ENDPOINT,
                "admin-cli", "admin", "admin-password", null, TOKEN_ENDPOINT, CLIENTS_ENDPOINT);
    }

    private void expectAdminToken() {
        server.expect(requestTo(TOKEN_ENDPOINT))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"access_token\":\"admin-token\",\"expires_in\":300}",
                        MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("the grant posture is read from the clients endpoint, not from the users endpoint")
    void theGrantPostureIsReadFromTheClientsEndpoint() {
        expectAdminToken();
        server.expect(requestTo(CLIENTS_ENDPOINT + "?clientId=ecommerce-client"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "[{\"clientId\":\"ecommerce-client\",\"directAccessGrantsEnabled\":true}]",
                        MediaType.APPLICATION_JSON));

        assertThat(client.isDirectAccessGrantEnabled("ecommerce-client")).isTrue();

        server.verify();
    }

    @Test
    @DisplayName("a client without the password grant reports the posture as disabled")
    void aClientWithoutThePasswordGrantReportsThePostureAsDisabled() {
        expectAdminToken();
        server.expect(requestTo(CLIENTS_ENDPOINT + "?clientId=ecommerce-admin"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "[{\"clientId\":\"ecommerce-admin\",\"directAccessGrantsEnabled\":false}]",
                        MediaType.APPLICATION_JSON));

        assertThat(client.isDirectAccessGrantEnabled("ecommerce-admin")).isFalse();

        server.verify();
    }

    @Test
    @DisplayName("a client that does not exist fails instead of reporting the posture as disabled")
    void aClientThatDoesNotExistFailsInsteadOfReportingThePostureAsDisabled() {
        expectAdminToken();
        server.expect(requestTo(CLIENTS_ENDPOINT + "?clientId=does-not-exist"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        // Reporting "no ROPC" for a client that does not exist would hide the
        // misconfiguration behind a reassuring posture.
        assertThatThrownBy(() -> client.isDirectAccessGrantEnabled("does-not-exist"))
                .isInstanceOfSatisfying(KeycloakClientException.class, ex ->
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    @DisplayName("a realm that refuses the lookup surfaces the failure")
    void aRealmThatRefusesTheLookupSurfacesTheFailure() {
        expectAdminToken();
        server.expect(requestTo(CLIENTS_ENDPOINT + "?clientId=ecommerce-client"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).body("{\"error\":\"forbidden\"}"));

        assertThatThrownBy(() -> client.isDirectAccessGrantEnabled("ecommerce-client"))
                .isInstanceOfSatisfying(KeycloakClientException.class, ex ->
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }
}
