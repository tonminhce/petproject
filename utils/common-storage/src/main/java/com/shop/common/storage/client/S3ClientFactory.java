package com.shop.common.storage.client;

import com.shop.common.storage.config.StorageProperties;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

public final class S3ClientFactory {

    private S3ClientFactory() {
    }

    public static StaticCredentialsProvider credentials(StorageProperties props) {
        return StaticCredentialsProvider.create(
                AwsBasicCredentials.create(props.getAccessKey(), props.getSecretKey()));
    }

    public static S3Client s3Client(StorageProperties props) {
        return S3Client.builder()
                .endpointOverride(URI.create(props.getEndpoint()))
                .region(Region.of(props.getRegion()))
                .credentialsProvider(credentials(props))
                .forcePathStyle(props.isPathStyleAccess())
                .overrideConfiguration(overrideConfiguration(props))
                .build();
    }

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

    private static ClientOverrideConfiguration overrideConfiguration(StorageProperties props) {
        return ClientOverrideConfiguration.builder()
                .apiCallTimeout(props.getApiCallTimeout())
                .apiCallAttemptTimeout(props.getApiCallAttemptTimeout())
                .build();
    }
}