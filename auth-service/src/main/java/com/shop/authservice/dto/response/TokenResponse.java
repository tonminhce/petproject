package com.shop.authservice.dto.response;

import lombok.Builder;

/**
 * OIDC-style token response (RFC 6749 §5.1). H3 — Java record (fleet
 * convention rule 1). {@code tokenType} is always {@code "Bearer"} for the
 * fleet's Keycloak adapter; {@code expiresIn} is the access-token lifetime
 * in seconds. Lombok {@code @Builder} generates the canonical
 * {@code builder()} for tests that build partial responses.
 */
@Builder
public record TokenResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        Long expiresIn
) {
}