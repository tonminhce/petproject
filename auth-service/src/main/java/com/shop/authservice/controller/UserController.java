package com.shop.authservice.controller;

import com.shop.authservice.dto.request.ChangePasswordRequest;
import com.shop.authservice.dto.request.UpdateUserRequest;
import com.shop.authservice.dto.response.UserResponse;
import com.shop.authservice.service.UserService;
import com.shop.common.core.constants.ApiPaths;
import com.shop.common.core.viewmodel.ApiResponse;
import com.shop.common.security.jwt.AuthenticatedUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping(ApiPaths.USERS)
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @PutMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<UserResponse> updateCurrentUser(
            @Valid @RequestBody UpdateUserRequest request) {
        UUID userId = UUID.fromString(AuthenticatedUser.requireCurrent().id());
        return ApiResponse.ok(userService.update(userId, request));
    }

    @PutMapping("/me/password")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        userService.changePassword(request);
        return ApiResponse.message("Password changed successfully");
    }

    @DeleteMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<Void> deleteCurrentUser() {
        AuthenticatedUser current = AuthenticatedUser.requireCurrent();
        UUID userId = UUID.fromString(current.id());
        userService.delete(userId, current.username());
        return ApiResponse.message("User deleted successfully");
    }

    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<UserResponse> getCurrentUser() {
        String username = AuthenticatedUser.requireCurrent().username();
        return ApiResponse.ok(userService.findByUsername(username));
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated() and hasRole('ADMIN')")
    public ApiResponse<UserResponse> getUserById(@PathVariable UUID id) {
        return ApiResponse.ok(userService.findById(id));
    }

    @GetMapping
    @PreAuthorize("isAuthenticated() and hasRole('ADMIN')")
    public ApiResponse<Page<UserResponse>> getAllUsers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "id") String sortBy,
            @RequestParam(defaultValue = "ASC") String sortOrder) {
        return ApiResponse.ok(userService.findAllUsers(page, size, sortBy, sortOrder));
    }

    @PutMapping("/{id}/restore")
    @PreAuthorize("isAuthenticated() and hasRole('ADMIN')")
    public ApiResponse<Void> restoreUser(@PathVariable UUID id) {
        userService.restore(id);
        return ApiResponse.message("User restored successfully");
    }
}
