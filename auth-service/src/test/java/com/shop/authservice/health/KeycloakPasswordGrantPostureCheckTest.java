package com.shop.authservice.health;

import com.shop.common.keycloak.client.KeycloakAdminClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KeycloakPasswordGrantPostureCheckTest {

    @Mock
    KeycloakAdminClient adminClient;

    @Test
    void readsTheGrantConfigurationOfThePlatformClient() {
        when(adminClient.hasAdminCredentials()).thenReturn(true);
        when(adminClient.isDirectAccessGrantEnabled("shop-backend")).thenReturn(true);

        new KeycloakPasswordGrantPostureCheck(adminClient, "shop-backend").report();

        verify(adminClient).isDirectAccessGrantEnabled("shop-backend");
    }

    @Test
    void withoutAdminCredentialsTheCheckDoesNotCallTheIdentityProvider() {
        // F-006: the shared defaults ship no credential any more. With blank
        // credentials the posture is unknown and the admin API is unusable - the
        // check must say so instead of sending a blank credential.
        when(adminClient.hasAdminCredentials()).thenReturn(false);

        new KeycloakPasswordGrantPostureCheck(adminClient, "shop-backend").report();

        verify(adminClient, never()).isDirectAccessGrantEnabled("shop-backend");
    }

    @Test
    void anUnreachableIdentityProviderDoesNotFailTheCheck() {
        when(adminClient.hasAdminCredentials()).thenReturn(true);
        doThrow(new RuntimeException("keycloak down"))
                .when(adminClient).isDirectAccessGrantEnabled("shop-backend");

        // Must not throw: the identity provider being briefly unreachable must
        // not stop the service from booting.
        new KeycloakPasswordGrantPostureCheck(adminClient, "shop-backend").report();

        verify(adminClient).isDirectAccessGrantEnabled("shop-backend");
    }
}
