package com.shop.common.keycloak.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Keycloak user representation as returned by {@code GET /admin/realms/{realm}/users}.
 *
 * <p>Only the fields services actually consume are exposed — Keycloak's full
 * user representation has ~30 fields, most of which are noise from the
 * application's point of view. Add fields here only when there is a concrete
 * consumer.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KeycloakUser(
        @JsonProperty("id") String id,
        @JsonProperty("username") String username,
        @JsonProperty("email") String email,
        @JsonProperty("firstName") String firstName,
        @JsonProperty("lastName") String lastName,
        @JsonProperty("enabled") Boolean enabled,
        @JsonProperty("emailVerified") Boolean emailVerified,
        @JsonProperty("createdTimestamp") Long createdTimestamp,
        @JsonProperty("realmRoles") List<String> realmRoles
) {

    /** Convenience — full name assembled from {@code firstName} + {@code lastName}. */
    public String fullName() {
        String given = firstName == null ? "" : firstName.trim();
        String family = lastName == null ? "" : lastName.trim();
        String joined = (given + " " + family).trim();
        return joined.isEmpty() ? username : joined;
    }
}
