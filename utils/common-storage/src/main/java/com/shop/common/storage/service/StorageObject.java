package com.shop.common.storage.service;

import java.io.InputStream;

/**
 * Result of a download: the object payload plus its metadata.
 *
 * <p>{@link #content()} is a live stream backed by the network connection — callers
 * must close it (e.g. try-with-resources, or hand it to a Spring {@code InputStreamResource}
 * which closes it after the body is written).</p>
 *
 * @param content       open stream over the object bytes; the caller is responsible for closing it
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
