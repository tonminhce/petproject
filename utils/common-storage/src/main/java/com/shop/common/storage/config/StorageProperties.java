package com.shop.common.storage.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuration for the shared S3-compatible {@link com.shop.common.storage.service.ObjectStorageService}.
 *
 * <p>Defaults target a local RustFS node exposing the S3 API on {@code :9000}.
 * Any S3-compatible backend (RustFS, AWS S3, ...) works by pointing
 * {@code endpoint} / credentials at it.</p>
 *
 * <pre>
 * shop:
 *   storage:
 *     endpoint: http://localhost:9000
 *     region: us-east-1
 *     access-key: rustfsadmin
 *     secret-key: rustfsadmin
 *     bucket: shop-media
 *     path-style-access: true
 *     auto-create-bucket: true
 *     presign-duration: 1h
 * </pre>
 */
@ConfigurationProperties(prefix = "shop.storage")
public class StorageProperties {

    /** Toggle the whole storage auto-configuration on/off. */
    private boolean enabled = true;

    /** S3 API endpoint of the backend (RustFS). */
    private String endpoint = "http://localhost:9000";

    /** Region sent in the signature; arbitrary but required by the SDK. */
    private String region = "us-east-1";

    /** Access key / username. */
    private String accessKey = "rustfsadmin";

    /** Secret key / password. */
    private String secretKey = "rustfsadmin";

    /** Default bucket used by the single-argument helper methods. */
    private String bucket = "shop-media";

    /**
     * Use path-style addressing ({@code endpoint/bucket/key}) instead of virtual-hosted
     * ({@code bucket.endpoint/key}). Required for most self-hosted backends like RustFS.
     */
    private boolean pathStyleAccess = true;

    /** Create the default bucket on startup if it is missing. */
    private boolean autoCreateBucket = true;

    /** Default time-to-live for generated pre-signed URLs. */
    private Duration presignDuration = Duration.ofHours(1);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getAccessKey() {
        return accessKey;
    }

    public void setAccessKey(String accessKey) {
        this.accessKey = accessKey;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public String getBucket() {
        return bucket;
    }

    public void setBucket(String bucket) {
        this.bucket = bucket;
    }

    public boolean isPathStyleAccess() {
        return pathStyleAccess;
    }

    public void setPathStyleAccess(boolean pathStyleAccess) {
        this.pathStyleAccess = pathStyleAccess;
    }

    public boolean isAutoCreateBucket() {
        return autoCreateBucket;
    }

    public void setAutoCreateBucket(boolean autoCreateBucket) {
        this.autoCreateBucket = autoCreateBucket;
    }

    public Duration getPresignDuration() {
        return presignDuration;
    }

    public void setPresignDuration(Duration presignDuration) {
        this.presignDuration = presignDuration;
    }
}
