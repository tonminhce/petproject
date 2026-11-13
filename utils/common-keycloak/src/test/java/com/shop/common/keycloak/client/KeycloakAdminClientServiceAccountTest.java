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

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * F-078: the admin token comes from a service account, not from a
 * master-realm password grant.
 *
 * <p>The admin client used the {@code password} grant against the master
 * realm on every admin call: the master-realm administrator credential
 * travelled in a plain password grant, and the permissions of the token
 * covered the whole Keycloak installation. With a configured service-account
 * secret the client uses {@code client_credentials} against the application
 * realm instead.</p>
 */
class KeycloakAdminClientServiceAccountTest {

    private static final String MASTER_TOKEN_ENDPOINT = "http://keycloak/realms/master/protocol/openid-connect/token";
    private static final String REALM_TOKEN_ENDPOINT = "http://keycloak/realms/ecommerce/protocol/openid-connect/token";
    private static final String USERS_ENDPOINT = "http://keycloak/admin/realms/ecommerce/users";
    private static final String ROLES_ENDPOINT = "http://keycloak/admin/realms/ecommerce/roles";

    /** The form body of the last token request. */
    private final AtomicReference<String> tokenRequestBody = new AtomicReference<>();

    private RestClient.Builder builder;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder()
                .requestInterceptor((request, body, execution) -> {
                    // only the token request matters - the other calls of the
                    // admin client carry no grant
                    if (request.getURI().getPath().contains("token")) {
                        tokenRequestBody.set(body instanceof byte[] bytes
                                ? new String(bytes, StandardCharsets.UTF_8)
                                : String.valueOf(body));
                    }
                    return execution.execute(request, body);
                });
        server = MockRestServiceServer.bindTo(builder).build();
    }

    private void expectRoleLookup() {
        expectRoleLookup("kc-123");
    }

    private void expectRoleLookup(String userId) {
        server.expect(requestTo(ROLES_ENDPOINT + "/MANAGER"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"id\":\"role-1\",\"name\":\"MANAGER\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(USERS_ENDPOINT + "/" + userId + "/role-mappings/realm"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess());
    }

    @Test
    @DisplayName("with a service-account secret the token comes from the application realm with client_credentials")
    void withAServiceAccountSecretTheTokenComesFromTheApplicationRealm() {
        KeycloakAdminClient client = new KeycloakAdminClient(builder, MASTER_TOKEN_ENDPOINT, USERS_ENDPOINT,
                ROLES_ENDPOINT, "ecommerce-admin", "", "", "s3cret", REALM_TOKEN_ENDPOINT);

        assertThat(client.hasServiceAccountCredentials()).isTrue();

        server.expect(requestTo(REALM_TOKEN_ENDPOINT))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"access_token\":\"service-token\",\"expires_in\":300}",
                        MediaType.APPLICATION_JSON));
        expectRoleLookup();

        client.assignRealmRole("kc-123", "MANAGER");

        assertThat(tokenRequestBody.get())
                .contains("grant_type=client_credentials")
                .contains("client_id=ecommerce-admin")
                .contains("client_secret=s3cret")
                .doesNotContain("grant_type=password")
                .doesNotContain("username=");
        server.verify();
    }

    @Test
    @DisplayName("without a service-account secret the master-realm password grant is used")
    void withoutAServiceAccountSecretTheMasterRealmPasswordGrantIsUsed() {
        KeycloakAdminClient client = new KeycloakAdminClient(builder,MASTER_TOKEN_ENDPOINT, USERS_ENDPOINT,
                ROLES_ENDPOINT, "admin-cli", "admin", "admin-password", null, REALM_TOKEN_ENDPOINT);

        assertThat(client.hasServiceAccountCredentials()).isFalse();

        server.expect(requestTo(MASTER_TOKEN_ENDPOINT))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"access_token\":\"master-token\",\"expires_in\":300}",
                        MediaType.APPLICATION_JSON));
        expectRoleLookup();

        client.assignRealmRole("kc-123", "MANAGER");

        assertThat(tokenRequestBody.get())
                .contains("grant_type=password")
                .contains("username=admin");
        server.verify();
    }

    @Test
    @DisplayName("a rejected service-account token surfaces as an error, not as a silent fallback")
    void aRejectedServiceAccountTokenSurfacesAsAnError() {
        KeycloakAdminClient client = new KeycloakAdminClient(builder, MASTER_TOKEN_ENDPOINT, USERS_ENDPOINT,
                ROLES_ENDPOINT, "ecommerce-admin", "", "", "wrong", REALM_TOKEN_ENDPOINT);

        server.expect(requestTo(REALM_TOKEN_ENDPOINT))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("{\"error\":\"invalid_client\"}"));

        assertThatThrownBy(() -> client.assignRealmRole("kc-123", "MANAGER"))
                .isInstanceOfSatisfying(KeycloakClientException.class, ex ->
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    @DisplayName("a configured service account never falls back to the master-realm password grant")
    void aConfiguredServiceAccountNeverFallsBackToTheMasterRealmPasswordGrant() {
        KeycloakAdminClient client = new KeycloakAdminClient(builder, MASTER_TOKEN_ENDPOINT, USERS_ENDPOINT,
                ROLES_ENDPOINT, "ecommerce-admin", "admin", "admin-password", "s3cret", REALM_TOKEN_ENDPOINT);

        server.expect(requestTo(REALM_TOKEN_ENDPOINT))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).body("{\"error\":\"temporarily_unavailable\"}"));

        assertThatThrownBy(() -> client.assignRealmRole("kc-123", "MANAGER"))
                .isInstanceOf(KeycloakClientException.class);

        // The master realm must not be contacted at all: a silent fallback
        // would put the master-realm administrator credential back on the
        // wire after the migration to the service account.
        server.verify();
    }

    @Test
    @DisplayName("the service-account token is reused for the following admin calls")
    void theServiceAccountTokenIsReusedForTheFollowingAdminCalls() {
        KeycloakAdminClient client = new KeycloakAdminClient(builder, MASTER_TOKEN_ENDPOINT, USERS_ENDPOINT,
                ROLES_ENDPOINT, "ecommerce-admin", "", "", "s3cret", REALM_TOKEN_ENDPOINT);

        // exactly one token request
        server.expect(requestTo(REALM_TOKEN_ENDPOINT))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"access_token\":\"service-token\",\"expires_in\":300}",
                        MediaType.APPLICATION_JSON));
        expectRoleLookup();
        expectRoleLookup("kc-456");

        client.assignRealmRole("kc-123", "MANAGER");
        client.assignRealmRole("kc-456", "MANAGER");

        server.verify();
    }
}
