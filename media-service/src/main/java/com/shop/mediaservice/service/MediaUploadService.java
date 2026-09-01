package com.shop.mediaservice.service;

import com.shop.mediaservice.dto.response.MediaResponse;
import org.springframework.web.multipart.MultipartFile;

/**
 * D1/D2 upload pipeline: magic-byte + mime allowlist → size guard → SHA-256
 * dedup (existing media → 200 + duplicate:true BEFORE any object write) →
 * metadata inspection → full-resolution re-encode + variant renders → S3
 * writes → media row commits LAST (orphan cleanup on any post-write failure).
 */
public interface MediaUploadService {

    MediaResponse upload(MultipartFile file);
}
