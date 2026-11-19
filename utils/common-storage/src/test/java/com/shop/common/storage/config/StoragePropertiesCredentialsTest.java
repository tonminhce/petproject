package com.shop.common.storage.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F-008: the shared library must not ship an object-store credential.
 *
 * <p>{@code StorageProperties} used to default the access key and the secret
 * key to {@code rustfsadmin}. A deployment that forgot to configure the
 * object store therefore talked to it with a well-known credential instead
 * of failing - and the mistake stayed invisible until the first storage call
 * failed with an opaque 403. A blank credential is not a usable credential:
 * the auto-configuration refuses to build the client without one.</p>
 */
class StoragePropertiesCredentialsTest {

    @Test
    @DisplayName("the credentials are blank by default - no shipped credential")
    void theCredentialsAreBlankByDefault() {
        StorageProperties props = new StorageProperties();

        assertThat(props.getAccessKey()).isBlank();
        assertThat(props.getSecretKey()).isBlank();
    }

    @Test
    @DisplayName("the auto-configuration refuses to build a client without credentials")
    void theAutoConfigurationRefusesToBuildAClientWithoutCredentials() {
        StorageProperties props = new StorageProperties();
        var configuration = new ObjectStorageAutoConfiguration();

        assertThatThrownBy(() -> configuration.s3Client(props))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("shop.storage.access-key");

        assertThatThrownBy(() -> configuration.s3Presigner(props))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("shop.storage.access-key");
    }

    @Test
    @DisplayName("a missing secret key is rejected as well")
    void aMissingSecretKeyIsRejected() {
        StorageProperties props = new StorageProperties();
        props.setAccessKey("an-access-key");
        var configuration = new ObjectStorageAutoConfiguration();

        assertThatThrownBy(() -> configuration.s3Client(props))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("shop.storage.secret-key");
    }

    @Test
    @DisplayName("with credentials the client is built")
    void withCredentialsTheClientIsBuilt() {
        StorageProperties props = new StorageProperties();
        props.setAccessKey("an-access-key");
        props.setSecretKey("a-secret-key");
        var configuration = new ObjectStorageAutoConfiguration();

        assertThat(configuration.s3Client(props)).isNotNull();
        assertThat(configuration.s3Presigner(props)).isNotNull();
    }
}
