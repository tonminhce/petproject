package com.shop.authservice.service.impls;

import com.shop.authservice.dto.response.TokenResponse;
import com.shop.common.keycloak.client.KeycloakTokenClient;
import com.shop.common.keycloak.dto.KeycloakTokenResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

    @Mock
    private KeycloakTokenClient keycloakTokenClient;

    @InjectMocks
    private AuthServiceImpl authService;

    private static KeycloakTokenResponse kcTokens() {
        return new KeycloakTokenResponse("access-1", "refresh-1", "Bearer", 300L, 1800L, "openid");
    }

    @Test
    void loginDelegatesToKeycloakAndMapsTokens() {
        when(keycloakTokenClient.login("alice", "secret")).thenReturn(kcTokens());

        TokenResponse response = authService.login("alice", "secret");

        assertEquals("access-1", response.getAccessToken());
        assertEquals("refresh-1", response.getRefreshToken());
        assertEquals("Bearer", response.getTokenType());
        assertEquals(300L, response.getExpiresIn());
        verify(keycloakTokenClient).login("alice", "secret");
    }

    @Test
    void refreshTokenDelegatesAndMapsTokens() {
        when(keycloakTokenClient.refreshToken("refresh-1")).thenReturn(kcTokens());

        TokenResponse response = authService.refreshToken("refresh-1");

        assertEquals("access-1", response.getAccessToken());
        verify(keycloakTokenClient).refreshToken("refresh-1");
    }

    @Test
    void logoutDelegatesToKeycloak() {
        authService.logout("refresh-1");

        verify(keycloakTokenClient).logout("refresh-1");
    }
}
