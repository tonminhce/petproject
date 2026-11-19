package com.shop.common.keycloak.client;

import com.shop.common.keycloak.dto.KeycloakTokenResponse;
import com.shop.common.keycloak.exception.KeycloakServiceTokenException;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

public class KeycloakServiceTokenProvider {

    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(3);
    public static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(8);
    public static final Duration DEFAULT_REFRESH_BUDGET = Duration.ofSeconds(12);
    public static final int DEFAULT_MAX_ATTEMPTS = 3;

    private static final Logger log = LoggerFactory.getLogger(KeycloakServiceTokenProvider.class);
    private static final long REFRESH_SKEW_SECONDS = 30L;
    private static final long LOCK_WAIT_SECONDS = 10L;
    private static final long FAILURE_COOLDOWN_MILLIS = 2000L;
    private static final long BASE_BACKOFF_MILLIS = 200L;
    private static final long MAX_BACKOFF_MILLIS = 1000L;
    private static final String CLIENT_CREDENTIALS = "client_credentials";

    private final RestClient restClient;
    private final String tokenUrl;
    private final String clientId;
    private final String clientSecret;
    private final int maxAttempts;
    private final long refreshBudgetNanos;
    private final long readTimeoutNanos;

    private final AtomicReference<CachedToken> cache = new AtomicReference<>();
    private final AtomicReference<CachedFailure> lastFailure = new AtomicReference<>();
    private final ReentrantLock refreshLock = new ReentrantLock();

    public KeycloakServiceTokenProvider(RestClient.Builder restClientBuilder,
                                        String tokenUrl,
                                        String clientId,
                                        String clientSecret) {
        this(restClientBuilder, tokenUrl, clientId, clientSecret,
                DEFAULT_CONNECT_TIMEOUT, DEFAULT_READ_TIMEOUT, DEFAULT_REFRESH_BUDGET, DEFAULT_MAX_ATTEMPTS);
    }

    public KeycloakServiceTokenProvider(RestClient.Builder restClientBuilder,
                                        String tokenUrl,
                                        String clientId,
                                        String clientSecret,
                                        Duration connectTimeout,
                                        Duration readTimeout,
                                        Duration refreshBudget,
                                        int maxAttempts) {
        this.restClient = restClientBuilder
                .requestFactory(pooledRequestFactory(connectTimeout, readTimeout))
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
                .build();
        this.tokenUrl = tokenUrl;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.refreshBudgetNanos = refreshBudget.toNanos();
        this.readTimeoutNanos = readTimeout.toNanos();
    }

    public String getToken() {
        CachedToken current = cache.get();
        Instant cutoff = Instant.now().plusSeconds(REFRESH_SKEW_SECONDS);
        if (current != null && current.expiresAt().isAfter(cutoff)) {
            return current.accessToken();
        }
        try {
            if (refreshLock.tryLock(LOCK_WAIT_SECONDS, TimeUnit.SECONDS)) {
                try {
                    return refreshToken();
                } finally {
                    refreshLock.unlock();
                }
            }
            current = cache.get();
            if (current != null && current.expiresAt().isAfter(Instant.now())) {
                return current.accessToken();
            }
            throw new KeycloakServiceTokenException("Timeout acquiring Keycloak token refresh lock");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KeycloakServiceTokenException("Interrupted waiting for Keycloak token refresh", e);
        }
    }

    public boolean hasFreshToken() {
        CachedToken current = cache.get();
        return current != null && current.expiresAt().isAfter(Instant.now());
    }

    private String refreshToken() {
        CachedToken current = cache.get();
        Instant cutoff = Instant.now().plusSeconds(REFRESH_SKEW_SECONDS);
        if (current != null && current.expiresAt().isAfter(cutoff)) {
            return current.accessToken();
        }

        CachedFailure recent = lastFailure.get();
        if (recent != null && recent.failedAt().isAfter(Instant.now().minusMillis(FAILURE_COOLDOWN_MILLIS))) {
            throw new KeycloakServiceTokenException("Keycloak token endpoint is failing", recent.failure());
        }

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", CLIENT_CREDENTIALS);
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);

        long startedAt = System.nanoTime();
        RuntimeException lastError = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (attempt > 1 && elapsedNanos(startedAt) + readTimeoutNanos > refreshBudgetNanos) {
                break;
            }
            try {
                KeycloakTokenResponse response = restClient.post()
                        .uri(tokenUrl)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .body(form)
                        .retrieve()
                        .body(KeycloakTokenResponse.class);
                if (response == null || response.accessToken() == null || response.expiresIn() == null) {
                    throw new KeycloakServiceTokenException("Keycloak token endpoint returned an incomplete body");
                }
                CachedToken fresh = new CachedToken(
                        response.accessToken(),
                        Instant.now().plusSeconds(response.expiresIn()));
                cache.set(fresh);
                lastFailure.set(null);
                log.info("Refreshed Keycloak service token (expires in {}s)", response.expiresIn());
                return fresh.accessToken();
            } catch (RestClientException ex) {
                lastError = new KeycloakServiceTokenException(
                        "Keycloak token refresh attempt " + attempt + "/" + maxAttempts + " failed", ex);
                log.warn("Keycloak token refresh attempt {}/{} failed: {}", attempt, maxAttempts, ex.getMessage());
                if (attempt < maxAttempts) {
                    backoff(attempt);
                }
            }
        }

        if (lastError == null) {
            lastError = new KeycloakServiceTokenException("Keycloak token refresh exhausted without a transport error");
        }
        lastFailure.set(new CachedFailure(lastError, Instant.now()));
        throw lastError;
    }

    private static long elapsedNanos(long startedAt) {
        return System.nanoTime() - startedAt;
    }

    private static void backoff(int attempt) {
        long delay = Math.min(BASE_BACKOFF_MILLIS * attempt, MAX_BACKOFF_MILLIS);
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KeycloakServiceTokenException("Interrupted during Keycloak token refresh backoff", e);
        }
    }

    private static ClientHttpRequestFactory pooledRequestFactory(Duration connectTimeout, Duration readTimeout) {
        PoolingHttpClientConnectionManager connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setMaxConnTotal(100)
                .setMaxConnPerRoute(25)
                .build();
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(Timeout.of(connectTimeout))
                .setResponseTimeout(Timeout.of(readTimeout))
                .setConnectionRequestTimeout(Timeout.of(connectTimeout))
                .build();
        CloseableHttpClient httpClient = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestConfig)
                .disableAutomaticRetries()
                .evictExpiredConnections()
                .evictIdleConnections(TimeValue.ofSeconds(30))
                .build();
        HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(readTimeout);
        return factory;
    }

    private record CachedToken(String accessToken, Instant expiresAt) {}

    private record CachedFailure(RuntimeException failure, Instant failedAt) {}
}
