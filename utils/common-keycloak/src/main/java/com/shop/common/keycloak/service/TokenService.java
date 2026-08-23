package com.shop.common.keycloak.service;

import com.shop.common.keycloak.client.KeycloakTokenResponse;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;

/**
 * Token utilities — parse and validate Keycloak-issued JWTs without round-tripping
 * to the {@code /token} endpoint.
 *
 * <p>Use this when a downstream service needs to inspect a token it received
 * (e.g. as a Bearer header from an upstream gateway) without performing a
 * full OAuth2 handshake. For interactive token exchange, the
 * {@code auth-service} should call Keycloak directly via its own REST client.</p>
 */
@Component
public class TokenService {

    private final JwtDecoder jwtDecoder;

    public TokenService(com.shop.common.keycloak.config.KeycloakProperties properties) {
        this.jwtDecoder = NimbusJwtDecoder.withIssuerLocation(properties.issuerUri()).build();
    }

    /**
     * Decode a Bearer token into a verified {@link Jwt}. Throws
     * {@link org.springframework.security.oauth2.jwt.BadJwtException} when
     * the signature, issuer, or expiry checks fail.
     */
    public Jwt decode(String tokenValue) {
        Objects.requireNonNull(tokenValue, "tokenValue");
        return jwtDecoder.decode(stripBearer(tokenValue));
    }

    /** Convenience: parse only the subject claim — empty when the token is invalid. */
    public Optional<String> subject(String tokenValue) {
        if (tokenValue == null || tokenValue.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(decode(tokenValue).getSubject());
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    /** Token-utility adapter — bridges this module's snake-case DTO to Spring's parser. */
    public static KeycloakTokenResponse toTokenResponse(Jwt jwt) {
        return new KeycloakTokenResponse(
                jwt.getTokenValue(),
                null,
                "Bearer",
                null,
                null,
                null
        );
    }

    private static String stripBearer(String header) {
        String trimmed = header.trim();
        if (trimmed.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return trimmed.substring(7).trim();
        }
        return trimmed;
    }
}
