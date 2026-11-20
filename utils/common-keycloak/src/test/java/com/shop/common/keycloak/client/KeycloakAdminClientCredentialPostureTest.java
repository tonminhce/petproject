package com.shop.common.keycloak.client;

import com.shop.common.keycloak.exception.KeycloakClientException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KeycloakAdminClientCredentialPostureTest {

    private final AtomicInteger tokenRequests = new AtomicInteger();

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void startStubKeycloak() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopStubKeycloak() {
        server.stop(0);
    }

    @Test
    @DisplayName("a service-account secret without a token endpoint fails fast instead of degrading to a password grant")
    void aServiceAccountSecretWithoutATokenEndpointFailsFast() {
        KeycloakAdminClient client = new KeycloakAdminClient(
                RestClient.builder(),
                baseUrl + "/realms/master/protocol/openid-connect/token",
                baseUrl + "/admin/realms/ecommerce/users",
                baseUrl + "/admin/realms/ecommerce/roles",
                "admin-cli",
                "ecommerce-admin",
                "admin",
                "admin-password",
                "s3cret",
                null,
                baseUrl + "/admin/realms/ecommerce/clients");

        assertThatThrownBy(() -> client.assignRealmRole("kc-1", "MANAGER"))
                .isInstanceOf(KeycloakClientException.class)
                .hasMessageContaining("service-account token endpoint");

        assertThat(tokenRequests.get())
                .as("no grant may be attempted when the credential configuration is incomplete")
                .isZero();
    }

    @Test
    @DisplayName("an admin client without any credential fails fast instead of sending an anonymous password grant")
    void anAdminClientWithoutAnyCredentialFailsFast() {
        KeycloakAdminClient client = new KeycloakAdminClient(
                RestClient.builder(),
                baseUrl + "/realms/master/protocol/openid-connect/token",
                baseUrl + "/admin/realms/ecommerce/users",
                baseUrl + "/admin/realms/ecommerce/roles",
                "admin-cli",
                "ecommerce-admin",
                "",
                "",
                null,
                baseUrl + "/realms/ecommerce/protocol/openid-connect/token",
                baseUrl + "/admin/realms/ecommerce/clients");

        assertThatThrownBy(() -> client.assignRealmRole("kc-1", "MANAGER"))
                .isInstanceOf(KeycloakClientException.class)
                .hasMessageContaining("master-realm password grant is not supported");

        assertThat(tokenRequests.get())
                .as("no grant may be attempted when no admin credential is configured")
                .isZero();
    }

    @Test
    @DisplayName("a half-configured password credential fails fast instead of sending an empty password grant")
    void aHalfConfiguredPasswordCredentialFailsFast() {
        KeycloakAdminClient client = new KeycloakAdminClient(
                RestClient.builder(),
                baseUrl + "/realms/master/protocol/openid-connect/token",
                baseUrl + "/admin/realms/ecommerce/users",
                baseUrl + "/admin/realms/ecommerce/roles",
                "admin-cli",
                "ecommerce-admin",
                "admin",
                "",
                null,
                baseUrl + "/realms/ecommerce/protocol/openid-connect/token",
                baseUrl + "/admin/realms/ecommerce/clients");

        assertThatThrownBy(() -> client.assignRealmRole("kc-1", "MANAGER"))
                .isInstanceOf(KeycloakClientException.class)
                .hasMessageContaining("master-realm password grant is not supported");

        assertThat(tokenRequests.get())
                .as("no grant may be attempted when the password credential is incomplete")
                .isZero();
    }

    private void handle(HttpExchange exchange) throws IOException {
        tokenRequests.incrementAndGet();
        byte[] payload = "{\"access_token\":\"master-token\",\"expires_in\":300}"
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }
}