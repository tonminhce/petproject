package com.shop.common.storage.client;

import com.shop.common.storage.config.StorageProperties;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

/**
 * Builds {@link S3Client} and {@link S3Presigner} instances from a
 * {@link StorageProperties} record. Kept as a small factory so callers can swap
 * in their own credential provider without rewriting the auto-configuration.
 */
public final class S3ClientFactory {

    private S3ClientFactory() {
    }

    /** Static credentials sourced from the configured access/secret key pair. */
    public static StaticCredentialsProvider credentials(StorageProperties props) {
        return StaticCredentialsProvider.create(
                AwsBasicCredentials.create(props.getAccessKey(), props.getSecretKey()));
    }

    /** S3 client targeting the configured endpoint (path-style by default). */
    public static S3Client s3Client(StorageProperties props) {
        return S3Client.builder()
                .endpointOverride(URI.create(props.getEndpoint()))
                .region(Region.of(props.getRegion()))
                .credentialsProvider(credentials(props))
                .forcePathStyle(props.isPathStyleAccess())
                .build();
    }

    /** Pre-signer targeting the configured endpoint (path-style by default). */
    public static S3Presigner s3Presigner(StorageProperties props) {
        return S3Presigner.builder()
                .endpointOverride(URI.create(props.getEndpoint()))
                .region(Region.of(props.getRegion()))
                .credentialsProvider(credentials(props))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(props.isPathStyleAccess())
                        .build())
                .build();
    }
}
