package com.shop.authservice.service;

import com.shop.authservice.dto.request.ChangePasswordRequest;
import com.shop.authservice.dto.request.RegisterRequest;
import com.shop.authservice.dto.request.ResetPasswordRequest;
import com.shop.authservice.dto.request.UpdateUserRequest;
import com.shop.authservice.dto.response.UserResponse;
import org.springframework.data.domain.Page;

import java.util.UUID;

public interface UserService {
    UserResponse register(RegisterRequest request);

    UserResponse update(UUID userId, UpdateUserRequest request);

    String changePassword(ChangePasswordRequest request);

    /**
     * Soft-deletes the user. The row stays in the DB for audit; subsequent
     * find queries will skip it via {@code @SQLRestriction}. Returns a
     * human-readable status string for the controller.
     */
    String delete(UUID id, String deletedBy);

    /**
     * Restores a previously soft-deleted user (admin only).
     */
    String restore(UUID id);

    /**
     * Finds a user by id, excluding soft-deleted records. To look up a deleted
     * record (admin restore flow), call {@code repository.findByIdIncludingDeleted}.
     */
    UserResponse findById(UUID userId);

    UserResponse findByUsername(String userName);

    Page<UserResponse> findAllUsers(int page, int size, String sortBy, String sortOrder);

    void forgotPassword(String email);

    String resetPassword(ResetPasswordRequest request);
}