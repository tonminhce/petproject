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

    String delete(UUID id, String deletedBy);

    String restore(UUID id);

    UserResponse findById(UUID userId);

    UserResponse findByUsername(String userName);

    Page<UserResponse> findAllUsers(int page, int size, String sortBy, String sortOrder);

    void forgotPassword(String email);

    String resetPassword(ResetPasswordRequest request);
}