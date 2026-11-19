package com.shop.authservice.dto.response;

/**
 * OIDC-style token response (RFC 6749 §5.1). H3 — Java record (fleet
 * convention rule 1: layer + records). {@code tokenType} is always
 * {@code "Bearer"} for the fleet's Keycloak adapter; {@code expiresIn} is
 * the access-token lifetime in seconds. Constructed via the canonical
 * record constructor; tests build partial responses with
 * {@code new TokenResponse(...)} directly — no Lombok {@code @Builder}
 * needed on a record.
 */
public record TokenResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        Long expiresIn
) {
}