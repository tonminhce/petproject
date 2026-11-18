package com.shop.common.storage.service;

import java.io.InputStream;

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
