package com.shop.common.keycloak.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Credential payload sent to {@code POST /admin/realms/{realm}/users} /
 * {@code PUT /admin/realms/{realm}/users/{id}/reset-password}.
 *
 * @param value     the clear-text password — never log this
 * @param temporary {@code true} forces a password change on first login
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KeycloakCredential(
        @JsonProperty("type") String type,
        @JsonProperty("value") String value,
        @JsonProperty("temporary") Boolean temporary
) {

    /** Permanent password credential — the most common shape. */
    public static KeycloakCredential password(String value) {
        return new KeycloakCredential("password", value, false);
    }

    /** Temporary password — Keycloak forces a change on next login. */
    public static KeycloakCredential temporaryPassword(String value) {
        return new KeycloakCredential("password", value, true);
    }
}
