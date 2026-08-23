package com.shop.common.keycloak.client;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Snake-case payload returned by Keycloak's
 * {@code /protocol/openid-connect/token} endpoint.
 *
 * <p>Each field is optional because Keycloak may omit some of them depending
 * on the grant type (e.g. {@code refresh_token} is absent for the
 * {@code client_credentials} grant).</p>
 */
public record KeycloakTokenResponse(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("refresh_token") String refreshToken,
        @JsonProperty("token_type") String tokenType,
        @JsonProperty("expires_in") Long expiresIn,
        @JsonProperty("refresh_expires_in") Long refreshExpiresIn,
        @JsonProperty("scope") String scope
) {
}
