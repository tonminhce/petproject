package com.shop.common.security.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

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

            JwtDecoder winner = delegate.get();
            if (winner != null) {
                return winner;
            }
        }
        return live;
    }
}
