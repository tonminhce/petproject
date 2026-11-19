package com.shop.authservice.dto.request;

import jakarta.validation.constraints.NotBlank;

public record RoleRequest(
        @NotBlank(message = "roleName must not be blank")
        String roleName
) {
}