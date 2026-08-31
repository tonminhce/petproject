package com.shop.searchservice.security;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.shop.searchservice.config.ShopServicesProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Client-credentials token cache for outbound service-to-service calls
 * (rating/order precedent, local to search-service deliberately — the
 * keycloak settings live in {@link ShopServicesProperties#keycloak()}).
 *
 * <p>Obtains an access token from Keycloak via the {@code client_credentials}
 * grant and caches it in-memory, refreshing 30 seconds before expiry. Used by
 * {@code ProductBackofficeClient} to attach a {@code Bearer} token when
 * streaming the reindex source from product-service.</p>
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

    public ServiceTokenProvider(RestClient.Builder restClientBuilder,
                                ShopServicesProperties properties) {
        ShopServicesProperties.Keycloak kc = properties.keycloak();
        this.restClient = restClientBuilder.build();
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
        return refreshToken();
    }

    private synchronized String refreshToken() {
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
