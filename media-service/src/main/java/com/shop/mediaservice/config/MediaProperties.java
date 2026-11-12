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
 * @param maxDecodedBytes budget for the DECODED image (pixels x 4 bytes) plus the
 *                     source bytes; a larger image is rejected before it is
 *                     decoded (F-062)
 */
@ConfigurationProperties(prefix = "media")
public record MediaProperties(
        String bucket,
        Duration presignTtl,
        DataSize maxUpload,
        Duration purgeGrace,
        int displayWidth,
        int thumbWidth,
        DataSize maxDecodedBytes) {

    /** Max bytes accepted by a single upload request (D1 cap). */
    public long maxUploadBytes() {
        return maxUpload.toBytes();
    }

    /**
     * The peak heap one upload may allocate for the decoded image.
     *
     * <p>F-062: the pixel guard used to allow 8192 x 8192 = 268 MB of
     * {@code BufferedImage} for a single upload - concurrent uploads turned
     * that into a heap-exhaustion outage instead of a rejected upload. The
     * budget covers the decoded pixels (4 bytes per pixel) AND the source
     * bytes, because both are held at the same time.</p>
     */
    public long maxDecodedHeapBytes() {
        return maxDecodedBytes.toBytes();
    }
}
