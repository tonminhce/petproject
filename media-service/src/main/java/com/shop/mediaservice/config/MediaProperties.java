package com.shop.mediaservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

/**
 * Media settings ({@code media.*}).
 *
 * @param bucket       private object-storage bucket all media objects live in
 * @param presignTtl   expiry of presigned GET URLs handed out on public media reads
 * @param maxUpload    hard upload size cap (413 above it)
 * @param purgeGrace   soft-deleted media objects are purged after this long
 * @param displayWidth display-variant width cap in px (downscale-only)
 * @param thumbWidth   thumb-variant width cap in px (downscale-only)
 */
@ConfigurationProperties(prefix = "media")
public record MediaProperties(
        String bucket,
        Duration presignTtl,
        DataSize maxUpload,
        Duration purgeGrace,
        int displayWidth,
        int thumbWidth) {

    /** Max bytes accepted by a single upload request (D1 cap). */
    public long maxUploadBytes() {
        return maxUpload.toBytes();
    }
}
