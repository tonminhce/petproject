package com.shop.common.core.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClientCredentialsGuardTest {

    @Test
    void aConfiguredClientAndSecretPass() {
        assertThatCode(() -> ClientCredentialsGuard.requireConfigured("media-service", "s3cret"))
                .doesNotThrowAnyException();
    }

    @Test
    void aBlankSecretIsRejected() {
        assertThatThrownBy(() -> ClientCredentialsGuard.requireConfigured("media-service", "  "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("media-service")
                .hasMessageContaining("must be configured");
    }

    @Test
    void aNullSecretIsRejected() {
        assertThatThrownBy(() -> ClientCredentialsGuard.requireConfigured("media-service", null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aBlankClientIdIsRejected() {
        assertThatThrownBy(() -> ClientCredentialsGuard.requireConfigured("", "s3cret"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("client-id");
    }
}
