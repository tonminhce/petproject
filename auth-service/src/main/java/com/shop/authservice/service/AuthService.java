package com.shop.authservice.service;

import com.shop.authservice.dto.response.TokenResponse;

public interface AuthService {

    TokenResponse login(String username, String password);

    TokenResponse refreshToken(String refreshToken);

    void logout(String refreshToken);
}
