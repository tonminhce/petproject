package com.shop.authservice.mapper;

import com.shop.authservice.config.RoleName;
import com.shop.common.core.exception.BusinessException;

public class RoleMapper {
    private RoleMapper() {
    }

    public static RoleName toRoleName(String roleName) {
        try {
            return RoleName.valueOf(roleName.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw BusinessException.badRequest("auth.unsupported.role", roleName);
        }
    }
}
