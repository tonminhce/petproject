package com.shop.common.keycloak.client;

import com.shop.common.keycloak.exception.KeycloakServiceTokenException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KeycloakServiceTokenProviderTest {

    private HttpServer server;
    private String tokenUrl;
    private final AtomicInteger requestCount = new AtomicInteger();
    private final List<String> acceptHeaders = new CopyOnWriteArrayList<>();
    private final List<String> requestBodies = new CopyOnWriteArrayList<>();
    private volatile int failuresBeforeSuccess = 0;
    private volatile long responseDelayMillis = 0;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", this::handleToken);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        tokenUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/token";
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private void handleToken(HttpExchange exchange) throws IOException {
        try (InputStream body = exchange.getRequestBody()) {
            requestBodies.add(new String(body.readAllBytes(), StandardCharsets.UTF_8));
        }
        acceptHeaders.add(String.valueOf(exchange.getRequestHeaders().getFirst("Accept")));
        int call = requestCount.incrementAndGet();

        if (responseDelayMillis > 0) {
            try {
                Thread.sleep(responseDelayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        if (call <= failuresBeforeSuccess) {
            respond(exchange, 503, "{\"error\":\"temporarily_unavailable\"}");
            return;
        }
        respond(exchange, 200, "{\"access_token\":\"service-token\",\"expires_in\":300}");
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }

    private KeycloakServiceTokenProvider provider(Duration connect, Duration read, Duration budget, int attempts) {
        return new KeycloakServiceTokenProvider(
                RestClient.builder(), tokenUrl, "rating-service", "s3cret", connect, read, budget, attempts);
    }

    private KeycloakServiceTokenProvider provider() {
        return provider(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(5), 3);
    }

    @Test
    void aSuccessfulGrantReturnsTheAccessToken() {
        assertThat(provider().getToken()).isEqualTo("service-token");
        assertThat(requestCount.get()).isEqualTo(1);
    }

    @Test
    void theGrantUsesTheClientCredentialsForm() {
        provider().getToken();

        assertThat(requestBodies.get(0))
                .contains("grant_type=client_credentials")
                .contains("client_id=rating-service")
                .contains("client_secret=s3cret");
    }

    @Test
    void theGrantAsksForJson() {
        provider().getToken();

        assertThat(acceptHeaders.get(0)).contains("application/json");
    }

    @Test
    void theTokenIsCachedForTheFollowingCalls() {
        KeycloakServiceTokenProvider provider = provider();

        assertThat(provider.getToken()).isEqualTo("service-token");
        assertThat(provider.getToken()).isEqualTo("service-token");
        assertThat(requestCount.get()).isEqualTo(1);
    }

    @Test
    void aTransientFailureIsRetried() {
        failuresBeforeSuccess = 1;

        assertThat(provider().getToken()).isEqualTo("service-token");
        assertThat(requestCount.get()).isEqualTo(2);
    }

    @Test
    void aPersistentFailureSurfacesAsAServiceTokenException() {
        failuresBeforeSuccess = 99;

        assertThatThrownBy(() -> provider().getToken())
                .isInstanceOf(KeycloakServiceTokenException.class);
        assertThat(requestCount.get()).isEqualTo(3);
    }

    @Test
    void aFailedRefreshIsNotRepeatedImmediatelyByTheNextCaller() {
        failuresBeforeSuccess = 99;
        KeycloakServiceTokenProvider provider = provider();

        assertThatThrownBy(() -> provider.getToken()).isInstanceOf(KeycloakServiceTokenException.class);
        int callsAfterFirstFailure = requestCount.get();

        assertThatThrownBy(() -> provider.getToken()).isInstanceOf(KeycloakServiceTokenException.class);
        assertThat(requestCount.get()).isEqualTo(callsAfterFirstFailure);
    }

    @Test
    void aStalledEndpointIsBoundedByTheRefreshBudget() {
        responseDelayMillis = 5000L;
        KeycloakServiceTokenProvider provider = provider(
                Duration.ofMillis(300), Duration.ofMillis(300), Duration.ofMillis(900), 5);

        long startedAt = System.nanoTime();
        assertThatThrownBy(() -> provider.getToken()).isInstanceOf(KeycloakServiceTokenException.class);
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;

        assertThat(elapsedMillis).isLessThan(8000L);
    }
}
