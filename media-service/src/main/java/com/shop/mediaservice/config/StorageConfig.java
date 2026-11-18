package com.shop.mediaservice.config;

import com.shop.common.storage.client.S3ClientFactory;
import com.shop.common.storage.config.StorageProperties;
import com.shop.common.storage.service.ObjectStorageService;
import com.shop.common.storage.service.S3ObjectStorageService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(StorageProperties.class)
public class StorageConfig {

    @Bean
    public S3Client s3Client(final StorageProperties properties) {
        return S3ClientFactory.s3Client(properties);
    }

    @Bean
    public S3Presigner s3Presigner(final StorageProperties properties) {
        return S3ClientFactory.s3Presigner(properties);
    }

    @Bean
    public ObjectStorageService objectStorageService(final S3Client s3Client,
                                                     final S3Presigner s3Presigner,
                                                     final StorageProperties properties) {
        return new S3ObjectStorageService(s3Client, s3Presigner, properties);
    }
}
