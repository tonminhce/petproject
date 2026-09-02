package com.shop.authservice.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * Role-name bind payload (admin endpoint). H3 — Java record (fleet convention
 * rule 1).
 */
public record RoleRequest(
        @NotBlank(message = "roleName must not be blank")
        String roleName
) {
}