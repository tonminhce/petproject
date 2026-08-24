package com.shop.authservice.service;

import com.shop.authservice.dto.request.ChangePasswordRequest;
import com.shop.authservice.dto.request.RegisterRequest;
import com.shop.authservice.dto.request.UpdateUserRequest;
import com.shop.authservice.dto.response.UserResponse;
import com.shop.authservice.entity.User;
import org.springframework.data.domain.Page;

import java.util.UUID;

public interface UserService {
    User register(RegisterRequest request);

    UserResponse update(UUID userId, UpdateUserRequest request);

    String changePassword(ChangePasswordRequest request);

    String delete(UUID id);

    User findById(UUID userId);

    User findByUsername(String userName);

    Page<UserResponse> findAllUsers(int page, int size, String sortBy, String sortOrder);
}