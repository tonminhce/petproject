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
        when(adminClient.isDirectAccessGrantEnabled("ecommerce-client")).thenReturn(true);

        new KeycloakPasswordGrantPostureCheck(adminClient, "ecommerce-client").report();

        verify(adminClient).isDirectAccessGrantEnabled("ecommerce-client");
    }

    @Test
    void withAServiceAccountThePostureIsStillMeasured() {
        // F-078: the admin path uses the service account of the application
        // realm. The ROPC posture must still be measured through it - the
        // migration away from the password grant needs the signal.
        when(adminClient.hasServiceAccountCredentials()).thenReturn(true);
        when(adminClient.isDirectAccessGrantEnabled("ecommerce-client")).thenReturn(true);

        new KeycloakPasswordGrantPostureCheck(adminClient, "ecommerce-client").report();

        verify(adminClient).isDirectAccessGrantEnabled("ecommerce-client");
    }

    @Test
    void withAServiceAccountAndWithoutAdminCredentialsThePostureIsStillMeasured() {
        // The master-realm credential is not configured any more: that must
        // NOT stop the posture measurement - the service account can read the
        // client configuration.
        when(adminClient.hasServiceAccountCredentials()).thenReturn(true);
        when(adminClient.hasAdminCredentials()).thenReturn(false);
        when(adminClient.isDirectAccessGrantEnabled("ecommerce-client")).thenReturn(false);

        new KeycloakPasswordGrantPostureCheck(adminClient, "ecommerce-client").report();

        verify(adminClient).isDirectAccessGrantEnabled("ecommerce-client");
    }

    @Test
    void withoutAdminCredentialsTheCheckDoesNotCallTheIdentityProvider() {
        // F-006: the shared defaults ship no credential any more. With blank
        // credentials the posture is unknown and the admin API is unusable - the
        // check must say so instead of sending a blank credential.
        when(adminClient.hasAdminCredentials()).thenReturn(false);
        when(adminClient.hasServiceAccountCredentials()).thenReturn(false);

        new KeycloakPasswordGrantPostureCheck(adminClient, "ecommerce-client").report();

        verify(adminClient, never()).isDirectAccessGrantEnabled("ecommerce-client");
    }

    @Test
    void anUnreachableIdentityProviderDoesNotFailTheCheck() {
        when(adminClient.hasAdminCredentials()).thenReturn(true);
        when(adminClient.hasServiceAccountCredentials()).thenReturn(false);
        doThrow(new RuntimeException("keycloak down"))
                .when(adminClient).isDirectAccessGrantEnabled("ecommerce-client");

        // Must not throw: the identity provider being briefly unreachable must
        // not stop the service from booting.
        new KeycloakPasswordGrantPostureCheck(adminClient, "ecommerce-client").report();

        verify(adminClient).isDirectAccessGrantEnabled("ecommerce-client");
    }
}
