package com.shop.productservice.security;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.shop.productservice.config.MediaClientProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Client-credentials token cache for outbound service-to-service calls.
 *
 * <p>Obtains an access token from Keycloak via the {@code client_credentials}
 * grant and caches it in-memory, refreshing 30 seconds before expiry. Used by
 * outbound HTTP clients (e.g. {@code MediaHeadClient}) to attach a
 * {@code Bearer} token when calling downstream services.</p>
 *
 * <p>Local to product-service deliberately (rating/search precedent) —
 * keycloak {@code token-url / client-id / client-secret} live in
 * {@link MediaClientProperties.Keycloak} (not the auth-owned
 * {@code shop.keycloak}). See order-service Task 2 P0-7b rationale.</p>
 */
@Component
@Slf4j
public class ServiceTokenProvider {

    private static final long REFRESH_SKEW_SECONDS = 30L;

    private final RestClient restClient;
    private final String tokenUrl;
    private final String clientId;
    private final String clientSecret;

    private final AtomicReference<CachedToken> cache = new AtomicReference<>();
    private final ReentrantLock refreshLock = new ReentrantLock();

    public ServiceTokenProvider(RestClient.Builder restClientBuilder,
                                MediaClientProperties properties) {
        MediaClientProperties.Keycloak kc = properties.keycloak();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        int timeoutMs = properties.timeoutMs() > 0 ? (int) properties.timeoutMs() : 5000;
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);
        this.restClient = restClientBuilder.requestFactory(factory).build();
        this.tokenUrl = kc.tokenUrl();
        this.clientId = kc.clientId();
        this.clientSecret = kc.clientSecret();
    }

    /**
     * Returns a valid (non-near-expiry) access token, refreshing the cache
     * synchronously when needed. Thread-safe: only one refresh runs at a time.
     */
    public String getToken() {
        CachedToken current = cache.get();
        Instant cutoff = Instant.now().plusSeconds(REFRESH_SKEW_SECONDS);
        if (current != null && current.expiresAt().isAfter(cutoff)) {
            return current.accessToken();
        }
        try {
            if (refreshLock.tryLock(5, TimeUnit.SECONDS)) {
                try {
                    return refreshToken();
                } finally {
                    refreshLock.unlock();
                }
            } else {
                current = cache.get();
                if (current != null && current.expiresAt().isAfter(Instant.now())) {
                    return current.accessToken();
                }
                throw new IllegalStateException("Timeout acquiring Keycloak token refresh lock");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted waiting for Keycloak token refresh", e);
        }
    }

    private String refreshToken() {
        CachedToken current = cache.get();
        Instant cutoff = Instant.now().plusSeconds(REFRESH_SKEW_SECONDS);
        if (current != null && current.expiresAt().isAfter(cutoff)) {
            return current.accessToken();
        }

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);

        TokenResponse response = restClient.post()
                .uri(tokenUrl)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(TokenResponse.class);

        if (response == null || response.accessToken() == null) {
            throw new IllegalStateException("Keycloak token endpoint returned empty body");
        }

        CachedToken fresh = new CachedToken(
                response.accessToken(),
                Instant.now().plusSeconds(response.expiresIn())
        );
        cache.set(fresh);
        log.info("Refreshed Keycloak service token (expires in {}s)", response.expiresIn());
        return fresh.accessToken();
    }

    private record CachedToken(String accessToken, Instant expiresAt) {}

    private record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("expires_in") long expiresIn
    ) {}
}
