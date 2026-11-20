package com.shop.common.keycloak.config;

import com.shop.common.keycloak.client.KeycloakTokenClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class KeycloakAutoConfigurationRestClientBuilderTest {

    private static final String PLATFORM_HEADER = "X-Platform-Rest-Builder";

    private final List<String> observedHeaders = new CopyOnWriteArrayList<>();

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
    @DisplayName("the Keycloak token client is built from the application-configured RestClient.Builder")
    void theKeycloakTokenClientIsBuiltFromTheApplicationConfiguredRestClientBuilder() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(KeycloakAutoConfiguration.class))
                .withBean(RestClient.Builder.class,
                        () -> RestClient.builder().defaultHeader(PLATFORM_HEADER, "applied"))
                .withPropertyValues(
                        "shop.keycloak.server-url=" + baseUrl,
                        "shop.keycloak.realm=ecommerce",
                        "shop.keycloak.client-id=ecommerce-client")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    KeycloakTokenClient client = context.getBean(KeycloakTokenClient.class);

                    client.login("customer", "secret");

                    assertThat(observedHeaders).isNotEmpty();
                    assertThat(observedHeaders)
                            .as("the platform RestClient.Builder customisation must reach the Keycloak client")
                            .anyMatch(header -> header.toLowerCase()
                                    .startsWith(PLATFORM_HEADER.toLowerCase() + ":"));
                });
    }

    private void handle(HttpExchange exchange) throws IOException {
        exchange.getRequestHeaders().forEach((name, values) ->
                values.forEach(value -> observedHeaders.add(name + ": " + value)));
        byte[] payload = "{\"access_token\":\"token\",\"expires_in\":300}"
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }
}