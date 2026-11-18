package com.shop.common.storage.service;

import java.io.InputStream;
import java.net.URL;
import java.time.Duration;

public interface ObjectStorageService {

    String defaultBucket();

    void ensureBucketExists(String bucket);

    String upload(String key, byte[] content, String contentType);

    String upload(String bucket, String key, InputStream content, long contentLength, String contentType);

    StorageObject download(String key);

    StorageObject download(String bucket, String key);

    default InputStream openStream(String bucket, String key) {
        StorageObject obj = download(bucket, key);
        return obj == null ? null : obj.content();
    }

    boolean exists(String key);

    void delete(String key);

    void delete(String bucket, String key);

    URL presignedGetUrl(String key, Duration ttl);

    URL presignedGetUrl(String bucket, String key, Duration ttl);

    URL presignedPutUrl(String key, String contentType, Duration ttl);
}
