package com.shop.authservice.service;

import com.shop.authservice.constant.RoleName;
import com.shop.authservice.entity.Role;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RoleService {
    Optional<Role> findByName(RoleName name);

    boolean assignRole(UUID id, String roleName);

    boolean revokeRole(UUID id, String roleName);

    List<String> getUserRoles(UUID id);
}
