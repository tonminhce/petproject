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

        when(adminClient.hasServiceAccountCredentials()).thenReturn(true);
        when(adminClient.isDirectAccessGrantEnabled("ecommerce-client")).thenReturn(true);

        new KeycloakPasswordGrantPostureCheck(adminClient, "ecommerce-client").report();

        verify(adminClient).isDirectAccessGrantEnabled("ecommerce-client");
    }

    @Test
    void withAServiceAccountAndWithoutAdminCredentialsThePostureIsStillMeasured() {

        when(adminClient.hasServiceAccountCredentials()).thenReturn(true);
        when(adminClient.hasAdminCredentials()).thenReturn(false);
        when(adminClient.isDirectAccessGrantEnabled("ecommerce-client")).thenReturn(false);

        new KeycloakPasswordGrantPostureCheck(adminClient, "ecommerce-client").report();

        verify(adminClient).isDirectAccessGrantEnabled("ecommerce-client");
    }

    @Test
    void withoutAdminCredentialsTheCheckDoesNotCallTheIdentityProvider() {

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

        new KeycloakPasswordGrantPostureCheck(adminClient, "ecommerce-client").report();

        verify(adminClient).isDirectAccessGrantEnabled("ecommerce-client");
    }
}
