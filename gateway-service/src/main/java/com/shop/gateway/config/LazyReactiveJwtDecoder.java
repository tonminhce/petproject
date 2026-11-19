package com.shop.gateway.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * <p>{@code NimbusReactiveJwtDecoder.withIssuerLocation(...).build()} performs
 * a BLOCKING OIDC discovery fetch while the bean is being created. On a
 * reactive gateway that blocks the startup thread on the identity provider
 * being reachable, and it makes the whole edge fail to boot when Keycloak is
 * briefly unavailable (rolling restart, IdP failover).
 * <p>This holder defers the discovery fetch to the first decode, mirroring
 * {@code common-security}'s {@code LazyJwtDecoder} for the servlet stack, and
 * serves the decoded token from the built decoder afterwards.</p>
 */
public final class LazyReactiveJwtDecoder implements ReactiveJwtDecoder {

    private static final Logger log = LoggerFactory.getLogger(LazyReactiveJwtDecoder.class);

    private final Supplier<ReactiveJwtDecoder> factory;
    private final AtomicReference<ReactiveJwtDecoder> delegate = new AtomicReference<>();

    public LazyReactiveJwtDecoder(Supplier<ReactiveJwtDecoder> factory) {
        this.factory = factory;
    }

    @Override
    public Mono<Jwt> decode(String token) throws JwtException {
        ReactiveJwtDecoder live = delegate.get();
        if (live == null) {
            live = buildOrFallback();
        }
        return live.decode(token);
    }

    private ReactiveJwtDecoder buildOrFallback() {
        try {
            ReactiveJwtDecoder built = factory.get();
            if (delegate.compareAndSet(null, built)) {
                log.info("Gateway JWT decoder built");
                return built;
            }
            ReactiveJwtDecoder winner = delegate.get();
            return winner != null ? winner : built;
        } catch (RuntimeException e) {
            throw new JwtException("Failed to build the gateway JWT decoder: " + e.getMessage(), e);
        }
    }
}
