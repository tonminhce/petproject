package com.shop.authservice.health;

import com.shop.common.keycloak.client.KeycloakAdminClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F-004 — the password-grant posture must be reported, and an unreachable
 * identity provider must never fail the check.
 */
@ExtendWith(MockitoExtension.class)
class KeycloakPasswordGrantPostureCheckTest {

    @Mock
    KeycloakAdminClient adminClient;

    @Test
    void readsTheGrantConfigurationOfThePlatformClient() {
        when(adminClient.isDirectAccessGrantEnabled("shop-backend")).thenReturn(true);

        new KeycloakPasswordGrantPostureCheck(adminClient, "shop-backend").report();

        verify(adminClient).isDirectAccessGrantEnabled("shop-backend");
    }

    @Test
    void anUnreachableIdentityProviderDoesNotFailTheCheck() {
        doThrow(new RuntimeException("keycloak down"))
                .when(adminClient).isDirectAccessGrantEnabled("shop-backend");

        // Must not throw: the identity provider being briefly unreachable must
        // not stop the service from booting.
        new KeycloakPasswordGrantPostureCheck(adminClient, "shop-backend").report();

        verify(adminClient).isDirectAccessGrantEnabled("shop-backend");
    }
}
