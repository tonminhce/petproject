package com.shop.mediaservice.service.impls;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.common.storage.exception.StorageException;
import com.shop.common.storage.service.ObjectStorageService;
import com.shop.mediaservice.config.MediaProperties;
import com.shop.mediaservice.entity.Media;
import com.shop.mediaservice.entity.MediaVariant;
import com.shop.mediaservice.metrics.MediaMetrics;
import com.shop.mediaservice.repository.MediaRepository;
import com.shop.mediaservice.service.MediaQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URL;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * D3 read resolution: media row (deleted rows are invisible via the entity's
 * {@code @SQLRestriction} — a deleted media reads as unknown, 404) → variant
 * row per the variant/format contract ({@link MediaQueryService}) → presigned
 * GET from the common-storage client with the configured TTL.
 *
 * <p>Presign is a local computation (no S3 round-trip), but a misconfigured
 * or down signer still surfaces as 503 MED-12006 — object-storage failures
 * map to the D6 storage-unavailable code everywhere.</p>
 *
 * <p>H-5 bucket unification: presign uses the bucket-qualified 3-arg overload
 * with {@code media.bucket} — the SAME property that drives upload and purge
 * writes/deletes. (Historically presign rode the 2-arg default-bucket chain,
 * i.e. {@code shop.storage.bucket} — same value today, two drift-capable
 * trees; one property now drives read AND write AND delete.)</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MediaQueryServiceImpl implements MediaQueryService {

    private static final String VARIANT_ORIGINAL = "original";
    private static final String VARIANT_DISPLAY = "display";
    private static final String VARIANT_THUMB = "thumb";
    private static final Set<String> KNOWN_VARIANTS = Set.of(VARIANT_ORIGINAL, VARIANT_DISPLAY, VARIANT_THUMB);
    private static final String FORMAT_WEBP = "webp";

    private final MediaRepository mediaRepository;
    private final ObjectStorageService storage;
    private final MediaMetrics metrics;
    private final MediaProperties properties;

    @Override
    public URL resolve(UUID mediaId, String variant, String format) {
        String variantName = variantOrDefault(variant);
        String formatName = format == null || format.isBlank() ? DEFAULT_FORMAT : format.toLowerCase();
        if (!KNOWN_VARIANTS.contains(variantName)) {
            log.info("Media read rejected: unknown variant '{}' for media {}", variantName, mediaId);
            throw BusinessException.of(ErrorCode.MEDIA_NOT_FOUND);
        }

        Media media = mediaRepository.findById(mediaId)
                .orElseThrow(() -> BusinessException.of(ErrorCode.MEDIA_NOT_FOUND));

        MediaVariant row = pickVariantRow(media, variantName, formatName)
                .orElseThrow(() -> BusinessException.of(ErrorCode.MEDIA_NOT_FOUND));

        URL url;
        try {
            url = storage.presignedGetUrl(properties.bucket(), row.getObjectKey(), properties.presignTtl());
        } catch (StorageException e) {
            log.error("Presign failed for media {} object {}", mediaId, row.getObjectKey(), e);
            throw BusinessException.of(ErrorCode.MEDIA_STORAGE_UNAVAILABLE);
        }
        metrics.recordPresign(variantName);
        log.debug("Presigned media {} variant {} format {} → {}", mediaId, variantName, row.getFormat(), url);
        return url;
    }

    @Override
    public boolean exists(UUID mediaId) {
        return mediaRepository.existsById(mediaId);
    }

    /**
     * Format resolution: {@code auto} prefers the WebP render and falls back
     * to the original-format render; {@code webp} requires the WebP row.
     * Any other format value is a bad request by contract (the controller
     * enum-binds {@code auto|webp}) — treated as {@code auto} defensively.
     */
    private Optional<MediaVariant> pickVariantRow(Media media, String variantName, String formatName) {
        List<MediaVariant> rows = media.getVariants().stream()
                .filter(v -> variantName.equals(v.getVariant()))
                .toList();
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        if (FORMAT_WEBP.equals(formatName)) {
            return rows.stream().filter(v -> FORMAT_WEBP.equals(v.getFormat())).findFirst();
        }
        return rows.stream().filter(v -> FORMAT_WEBP.equals(v.getFormat())).findFirst()
                .or(() -> rows.stream().filter(v -> !FORMAT_WEBP.equals(v.getFormat())).findFirst());
    }

    private String variantOrDefault(String variant) {
        return variant == null || variant.isBlank() ? DEFAULT_VARIANT : variant.toLowerCase();
    }
}
