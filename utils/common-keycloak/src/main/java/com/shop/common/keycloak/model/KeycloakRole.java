package com.shop.common.keycloak.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Keycloak realm-role representation as returned by {@code GET /admin/realms/{realm}/roles}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KeycloakRole(
        @JsonProperty("id") String id,
        @JsonProperty("name") String name,
        @JsonProperty("description") String description,
        @JsonProperty("composite") Boolean composite,
        @JsonProperty("clientRole") Boolean clientRole,
        @JsonProperty("containerId") String containerId
) {
}
