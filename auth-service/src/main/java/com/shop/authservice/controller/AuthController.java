package com.shop.authservice.controller;

import com.shop.authservice.dto.request.LoginRequest;
import com.shop.authservice.dto.request.RefreshTokenRequest;
import com.shop.authservice.dto.request.RegisterRequest;
import com.shop.authservice.dto.response.TokenResponse;
import com.shop.authservice.service.AuthService;
import com.shop.authservice.service.UserService;
import com.shop.common.core.constants.ApiPaths;
import com.shop.common.core.viewmodel.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(ApiPaths.AUTH)
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final UserService userService;

    @PostMapping("/sign-up")
    public ApiResponse<Void> register(@Valid @RequestBody RegisterRequest request) {
        userService.register(request);
        return ApiResponse.message("User registered successfully");
    }

    @PostMapping("/login")
    public ApiResponse<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        TokenResponse tokens = authService.login(request.getUsername(), request.getPassword());
        return ApiResponse.ok(tokens);
    }

    @PostMapping("/refresh")
    public ApiResponse<TokenResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        TokenResponse tokens = authService.refreshToken(request.getRefreshToken());
        return ApiResponse.ok(tokens);
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(@Valid @RequestBody RefreshTokenRequest request) {
        authService.logout(request.getRefreshToken());
        return ApiResponse.message("Logged out successfully");
    }
}
