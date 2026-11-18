package com.shop.mediaservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

@ConfigurationProperties(prefix = "media")
public record MediaProperties(
        String bucket,
        Duration presignTtl,
        DataSize maxUpload,
        Duration purgeGrace,
        int displayWidth,
        int thumbWidth,
        DataSize maxDecodedBytes) {

    public long maxUploadBytes() {
        return maxUpload.toBytes();
    }

    public long maxDecodedHeapBytes() {
        return maxDecodedBytes.toBytes();
    }
}
