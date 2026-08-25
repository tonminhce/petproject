package com.shop.authservice.controller;

import com.shop.authservice.dto.request.RoleRequest;
import com.shop.authservice.service.RoleService;
import com.shop.common.core.viewmodel.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/roles")
@PreAuthorize("hasAuthority('ADMIN')")
@RequiredArgsConstructor
public class RoleController {

    private final RoleService roleService;

    @PostMapping("/users/{userId}/assign")
    public ApiResponse<Boolean> assignRole(@PathVariable UUID userId,
                                           @Valid @RequestBody RoleRequest request) {
        return ApiResponse.ok(roleService.assignRole(userId, request.getRoleName()));
    }

    @PostMapping("/users/{userId}/revoke")
    public ApiResponse<Boolean> revokeRole(@PathVariable UUID userId,
                                           @Valid @RequestBody RoleRequest request) {
        return ApiResponse.ok(roleService.revokeRole(userId, request.getRoleName()));
    }

    @GetMapping("/users/{userId}")
    public ApiResponse<List<String>> getUserRoles(@PathVariable UUID userId) {
        return ApiResponse.ok(roleService.getUserRoles(userId));
    }
}
