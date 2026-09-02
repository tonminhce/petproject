package com.shop.common.security.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * H33 — Holder-pattern {@link JwtDecoder} that defers the OIDC discovery + JWKS
 * fetch to the first {@link #decode(String)} call.
 *
 * <p>The previous {@code @Bean} factory called
 * {@code NimbusJwtDecoder.withIssuerLocation(...).build()} directly, which
 * synchronously fetches the OIDC discovery document over HTTP at startup.
 * That made every service hang on Keycloak being reachable — a blocker for
 * dev loops, CI image builds, and multi-pod rolling restarts when the IdP
 * is briefly unavailable.</p>
 *
 * <p>This Holder:</p>
 * <ul>
 *   <li>Returns instantly from the bean factory — startup is non-blocking.</li>
 *   <li>Spawns a daemon thread at construction that pre-warms the real decoder.
 *       The first request that needs JWT validation hits the warmed cache in
 *       the common case, so request-path latency is unaffected.</li>
 *   <li>Falls back to a synchronous build on first {@code decode} if the
 *       pre-warm has not yet completed (or failed). The exception then
 *       surfaces as a {@link JwtException} the resource-server filter chain
 *       translates to 401, never a startup crash.</li>
 *   <li>Is thread-safe: a CAS on the delegate ensures only one real decoder
 *       is built even under concurrent first-request load.</li>
 * </ul>
 */
public final class LazyJwtDecoder implements JwtDecoder {

    private static final Logger log = LoggerFactory.getLogger(LazyJwtDecoder.class);

    private final Supplier<NimbusJwtDecoder> factory;
    private final AtomicReference<JwtDecoder> delegate = new AtomicReference<>();

    public LazyJwtDecoder(Supplier<NimbusJwtDecoder> factory) {
        this.factory = factory;
        prewarm();
    }

    @Override
    public Jwt decode(String token) throws JwtException {
        JwtDecoder live = delegate.get();
        if (live == null) {
            live = buildOrFallback();
        }
        return live.decode(token);
    }

    /**
     * Best-effort background build so the first request that needs JWT
     * validation does not pay the OIDC-discovery cost. Failure is logged and
     * swallowed — the first {@code decode} will retry the build synchronously.
     */
    private void prewarm() {
        Thread t = new Thread(this::buildSilently, "jwt-decoder-prewarm");
        t.setDaemon(true);
        t.start();
    }

    private void buildSilently() {
        try {
            JwtDecoder built = factory.get();
            if (delegate.compareAndSet(null, built)) {
                log.debug("JWT decoder pre-warm completed");
            }
        } catch (Exception e) {
            log.warn("JWT decoder pre-warm failed; first decode will retry synchronously: {}",
                    e.getMessage());
        }
    }

    private JwtDecoder buildOrFallback() {
        JwtDecoder live;
        try {
            live = factory.get();
        } catch (Exception e) {
            throw new JwtException("Failed to build JWT decoder: " + e.getMessage(), e);
        }
        if (!delegate.compareAndSet(null, live)) {
            // Another thread won the race — return its decoder.
            JwtDecoder winner = delegate.get();
            if (winner != null) {
                return winner;
            }
        }
        return live;
    }
}