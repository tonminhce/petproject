package com.shop.common.storage.service;

import java.io.InputStream;

/**
 * Result of a download: the object payload plus its metadata.
 *
 * <p>{@link #content()} is an in-memory stream backed by the bytes already
 * buffered by {@code S3ObjectStorageService.download()}. The S3-side HTTP
 * connection is released before this object is returned to the caller, so
 * {@link #content()} does not hold any external resource. Calling
 * {@link InputStream#close()} on it is still safe and recommended for
 * symmetry, but it is not required to avoid leaks.</p>
 *
 * @param content       in-memory stream over the object bytes; caller may close it
 * @param key           object key within the bucket
 * @param contentType   MIME type as stored, may be {@code null}
 * @param contentLength size in bytes, or {@code -1} if unknown
 */
public record StorageObject(
        InputStream content,
        String key,
        String contentType,
        long contentLength
) {

    public static StorageObject of(InputStream content, String key, String contentType, long contentLength) {
        return new StorageObject(content, key, contentType, contentLength);
    }
}
