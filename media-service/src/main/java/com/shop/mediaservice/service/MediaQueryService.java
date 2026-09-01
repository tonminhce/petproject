package com.shop.mediaservice.service;

import java.net.URL;
import java.util.UUID;

/**
 * D3 read side — resolves a stored media into a presigned GET URL for the
 * requested variant/format, and answers existence checks WITHOUT touching
 * storage (the {@code HEAD /api/v1/medias/{id}} service-facing validation,
 * product Option C write-time check).
 *
 * <p>Param contract (binding): {@code variant} is one of
 * {@code display|thumb|original} ({@link #DEFAULT_VARIANT} when null/blank);
 * anything else — or a media/variant that has no stored row — is
 * 404 MED-12004. {@code format} is {@code auto|webp} ({@link #DEFAULT_FORMAT}
 * when null/blank): {@code auto} prefers the WebP render when stored and
 * falls back to the original-format render, {@code webp} serves the WebP
 * row only.</p>
 */
public interface MediaQueryService {

    String DEFAULT_VARIANT = "display";
    String DEFAULT_FORMAT = "auto";

    /**
     * Presigned GET URL for one stored variant render (expiry
     * {@code media.presign-ttl}). Increments {@code media_presigned_total}
     * tagged with the resolved variant.
     *
     * @throws com.shop.common.core.exception.BusinessException
     *         404 MED-12004 (unknown media/variant/format-row) or
     *         503 MED-12006 (object storage unavailable)
     */
    URL resolve(UUID mediaId, String variant, String format);

    /**
     * True when the media row exists and is NOT soft-deleted. Deliberately
     * does NOT presign or touch object storage — the existence check must
     * stay cheap and storage-independent.
     */
    boolean exists(UUID mediaId);
}
