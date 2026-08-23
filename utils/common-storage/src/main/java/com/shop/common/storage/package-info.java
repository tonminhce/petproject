/**
 * S3-compatible object storage abstractions and auto-configuration.
 * <p>
 * Public surface: {@link com.shop.common.storage.service.ObjectStorageService} interface
 * with an AWS SDK v2 implementation in
 * {@link com.shop.common.storage.service.S3ObjectStorageService}, wired by
 * {@link com.shop.common.storage.config.ObjectStorageAutoConfiguration}.
 *
 * <p>Targets any S3-compatible backend (RustFS, MinIO, AWS S3, ...) by overriding
 * the {@code shop.storage.endpoint} / credentials properties.</p>
 */
package com.shop.common.storage;
