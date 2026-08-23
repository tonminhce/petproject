package com.shop.common.storage.config;

import com.shop.common.storage.client.S3ClientFactory;
import com.shop.common.storage.service.ObjectStorageService;
import com.shop.common.storage.service.S3ObjectStorageService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * Auto-wires an S3-compatible {@link ObjectStorageService} (RustFS by default).
 *
 * <p>Activated whenever the AWS SDK is on the classpath and
 * {@code shop.storage.enabled} is not explicitly set to {@code false}. Services
 * override any bean by declaring their own {@link S3Client}, {@link S3Presigner}
 * or {@link ObjectStorageService}.</p>
 */
@AutoConfiguration
@ConditionalOnClass(S3Client.class)
@ConditionalOnProperty(prefix = "shop.storage", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(StorageProperties.class)
public class ObjectStorageAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public S3Client s3Client(StorageProperties props) {
        return S3ClientFactory.s3Client(props);
    }

    @Bean
    @ConditionalOnMissingBean
    public S3Presigner s3Presigner(StorageProperties props) {
        return S3ClientFactory.s3Presigner(props);
    }

    @Bean
    @ConditionalOnMissingBean
    public ObjectStorageService objectStorageService(
            S3Client s3Client, S3Presigner presigner, StorageProperties props) {
        S3ObjectStorageService service = new S3ObjectStorageService(s3Client, presigner, props);
        if (props.isAutoCreateBucket()) {
            service.ensureBucketExists(props.getBucket());
        }
        return service;
    }
}
