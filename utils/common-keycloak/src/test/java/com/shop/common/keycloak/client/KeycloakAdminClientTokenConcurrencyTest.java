package com.shop.common.keycloak.client;

import com.shop.common.keycloak.exception.KeycloakClientException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class KeycloakAdminClientTokenConcurrencyTest {

    private static final int CONCURRENT_CALLERS = 16;
    private static final String TOKEN = "admin-token";

    private enum TokenOutcome {
        SUCCESS,
        FAILURE
    }

    private final StubKeycloak keycloak = new StubKeycloak();

    @Test
    @DisplayName("a failing token request is fetched once and the failure is shared by every concurrent caller")
    void aFailingTokenRequestIsFetchedOnceAndSharedByEveryCaller() throws Exception {
        keycloak.outcome = TokenOutcome.FAILURE;
        KeycloakAdminClient client = adminClient();
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());

        List<Thread> callers = startCallers(client, failures);
        assertThat(keycloak.tokenRequestStarted.await(10, TimeUnit.SECONDS)).isTrue();
        keycloak.releaseTokenResponse.countDown();
        joinAll(callers);

        assertThat(failures)
                .as("every concurrent caller must observe the token failure")
                .hasSize(CONCURRENT_CALLERS)
                .allMatch(KeycloakClientException.class::isInstance);
        assertThat(keycloak.tokenRequests.get())
                .as("a failing token request must be attempted once, not once per concurrent caller")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the admin client does not hold a JVM monitor while the token request is in flight")
    void theAdminClientDoesNotHoldAMonitorWhileTheTokenRequestIsInFlight() throws Exception {
        KeycloakAdminClient client = adminClient();
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());

        List<Thread> callers = startCallers(client, failures);
        assertThat(keycloak.tokenRequestStarted.await(10, TimeUnit.SECONDS)).isTrue();

        CountDownLatch monitorAcquired = new CountDownLatch(1);
        Thread prober = new Thread(() -> {
            synchronized (client) {
                monitorAcquired.countDown();
            }
        });
        prober.start();

        boolean monitorFreeDuringTokenFetch = monitorAcquired.await(5, TimeUnit.SECONDS);

        keycloak.releaseTokenResponse.countDown();
        joinAll(callers);
        prober.join(5_000);

        assertThat(monitorFreeDuringTokenFetch)
                .as("the admin client must not hold a JVM monitor while the token request is in flight")
                .isTrue();
        assertThat(failures).isEmpty();
    }

    @Test
    @DisplayName("every concurrent admin call receives the same freshly issued token")
    void everyConcurrentAdminCallReceivesTheSameFreshlyIssuedToken() throws Exception {
        KeycloakAdminClient client = adminClient();
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());

        List<Thread> callers = startCallers(client, failures);
        assertThat(keycloak.tokenRequestStarted.await(10, TimeUnit.SECONDS)).isTrue();
        keycloak.releaseTokenResponse.countDown();
        joinAll(callers);

        assertThat(failures).isEmpty();
        assertThat(keycloak.tokenRequests.get()).isEqualTo(1);
        assertThat(keycloak.roleMappingAuthorizations).hasSize(CONCURRENT_CALLERS);
        assertThat(keycloak.roleMappingAuthorizations).allMatch(header -> ("Bearer " + TOKEN).equals(header));
    }

    private List<Thread> startCallers(KeycloakAdminClient client, List<Throwable> failures) throws InterruptedException {
        CountDownLatch callersReady = new CountDownLatch(CONCURRENT_CALLERS);
        CountDownLatch startLine = new CountDownLatch(1);
        CountDownLatch fetchersReady = new CountDownLatch(CONCURRENT_CALLERS);
        List<Thread> callers = new ArrayList<>();

        for (int index = 0; index < CONCURRENT_CALLERS; index++) {
            String userId = "user-" + index;
            Thread caller = new Thread(() -> {
                callersReady.countDown();
                try {
                    startLine.await();
                    fetchersReady.countDown();
                    client.assignRealmRole(userId, "MANAGER");
                } catch (Throwable failure) {
                    failures.add(failure);
                }
            });
            callers.add(caller);
            caller.start();
        }

        assertThat(callersReady.await(10, TimeUnit.SECONDS)).isTrue();
        startLine.countDown();
        assertThat(fetchersReady.await(10, TimeUnit.SECONDS)).isTrue();
        return callers;
    }

    private static void joinAll(List<Thread> callers) throws InterruptedException {
        for (Thread caller : callers) {
            caller.join(30_000);
        }
    }

    private KeycloakAdminClient adminClient() {
        return new KeycloakAdminClient(
                RestClient.builder().requestFactory(keycloak),
                "http://keycloak/realms/master/protocol/openid-connect/token",
                "http://keycloak/admin/realms/ecommerce/users",
                "http://keycloak/admin/realms/ecommerce/roles",
                "admin-cli",
                "ecommerce-admin",
                "",
                "",
                "s3cret",
                "http://keycloak/realms/ecommerce/protocol/openid-connect/token",
                "http://keycloak/admin/realms/ecommerce/clients");
    }

    private static final class StubKeycloak implements ClientHttpRequestFactory {

        private final AtomicInteger tokenRequests = new AtomicInteger();
        private final List<String> roleMappingAuthorizations = Collections.synchronizedList(new ArrayList<>());
        private final CountDownLatch tokenRequestStarted = new CountDownLatch(1);
        private final CountDownLatch releaseTokenResponse = new CountDownLatch(1);

        private volatile TokenOutcome outcome = TokenOutcome.SUCCESS;

        @Override
        public ClientHttpRequest createRequest(URI uri, HttpMethod httpMethod) {
            return new StubRequest(uri, httpMethod, this);
        }

        private ClientHttpResponse respond(URI uri, HttpHeaders headers) throws IOException {
            String path = uri.getPath();
            if (path.endsWith("/token")) {
                tokenRequests.incrementAndGet();
                tokenRequestStarted.countDown();
                awaitRelease();
                if (outcome == TokenOutcome.FAILURE) {
                    return new StubResponse(HttpStatus.SERVICE_UNAVAILABLE,
                            "{\"error\":\"temporarily_unavailable\"}");
                }
                return new StubResponse(HttpStatus.OK, "{\"access_token\":\"" + TOKEN + "\",\"expires_in\":300}");
            }
            if (path.contains("/roles/")) {
                return new StubResponse(HttpStatus.OK, "{\"id\":\"role-1\",\"name\":\"MANAGER\"}");
            }
            if (path.contains("/role-mappings/realm")) {
                roleMappingAuthorizations.add(headers.getFirst("Authorization"));
                return new StubResponse(HttpStatus.OK, "");
            }
            return new StubResponse(HttpStatus.NOT_FOUND, "");
        }

        private void awaitRelease() {
            try {
                releaseTokenResponse.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static final class StubRequest implements ClientHttpRequest {

        private final URI uri;
        private final HttpMethod httpMethod;
        private final HttpHeaders headers = new HttpHeaders();
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();
        private final StubKeycloak keycloak;

        private StubRequest(URI uri, HttpMethod httpMethod, StubKeycloak keycloak) {
            this.uri = uri;
            this.httpMethod = httpMethod;
            this.keycloak = keycloak;
        }

        @Override
        public HttpMethod getMethod() {
            return httpMethod;
        }

        @Override
        public java.util.Map<String, Object> getAttributes() {
            return new java.util.HashMap<>();
        }

        @Override
        public URI getURI() {
            return uri;
        }

        @Override
        public HttpHeaders getHeaders() {
            return headers;
        }

        @Override
        public OutputStream getBody() {
            return body;
        }

        @Override
        public ClientHttpResponse execute() throws IOException {
            return keycloak.respond(uri, headers);
        }
    }

    private static final class StubResponse implements ClientHttpResponse {

        private final HttpStatus status;
        private final byte[] payload;
        private final HttpHeaders headers = new HttpHeaders();

        private StubResponse(HttpStatus status, String payload) {
            this.status = status;
            this.payload = payload.getBytes(StandardCharsets.UTF_8);
            this.headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        }

        @Override
        public HttpStatusCode getStatusCode() {
            return HttpStatusCode.valueOf(status.value());
        }

        @Override
        public String getStatusText() {
            return status.getReasonPhrase();
        }

        @Override
        public HttpHeaders getHeaders() {
            return headers;
        }

        @Override
        public InputStream getBody() {
            return new ByteArrayInputStream(payload);
        }

        @Override
        public void close() {
        }
    }
}