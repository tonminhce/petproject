package com.shop.authservice.service.impls;

import com.shop.authservice.dto.response.TokenResponse;
import com.shop.authservice.service.AuthService;
import com.shop.common.keycloak.client.KeycloakTokenClient;
import com.shop.common.keycloak.dto.KeycloakTokenResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final KeycloakTokenClient keycloakTokenClient;

    @Override
    public TokenResponse login(String username, String password) {
        KeycloakTokenResponse keycloakResponse = keycloakTokenClient.login(username, password);
        return mapToTokenResponse(keycloakResponse);
    }

    @Override
    public TokenResponse refreshToken(String refreshToken) {
        KeycloakTokenResponse keycloakResponse = keycloakTokenClient.refreshToken(refreshToken);
        return mapToTokenResponse(keycloakResponse);
    }

    @Override
    public void logout(String refreshToken) {
        keycloakTokenClient.logout(refreshToken);
    }

    private TokenResponse mapToTokenResponse(KeycloakTokenResponse keycloakResponse) {
        return new TokenResponse(
                keycloakResponse.accessToken(),
                keycloakResponse.refreshToken(),
                keycloakResponse.tokenType(),
                keycloakResponse.expiresIn()
        );
    }
}
