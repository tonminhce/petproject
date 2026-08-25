package com.shop.common.keycloak.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * OAuth2 token response from Keycloak.
 * Maps to the JSON response from /protocol/openid-connect/token endpoint.
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
