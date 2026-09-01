package com.shop.mediaservice.service.impls;

import com.shop.common.core.constants.ApiPaths;
import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.common.storage.exception.StorageException;
import com.shop.common.storage.service.ObjectStorageService;
import com.shop.mediaservice.config.MediaProperties;
import com.shop.mediaservice.dto.response.MediaResponse;
import com.shop.mediaservice.dto.response.MediaVariantResponse;
import com.shop.mediaservice.entity.Media;
import com.shop.mediaservice.entity.MediaVariant;
import com.shop.mediaservice.metrics.MediaMetrics;
import com.shop.mediaservice.repository.MediaRepository;
import com.shop.mediaservice.service.MediaUploadService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * D1/D2 pipeline, in binding order:
 * <ol>
 *   <li>mime allowlist on the declared type → 415 MED-12003;</li>
 *   <li>magic-byte sniff must agree with the declared type — a corrupted or
 *       lying file → 400 MED-12001;</li>
 *   <li>size guard against {@code media.max-upload} → 413 MED-12002;</li>
 *   <li>SHA-256 dedup lookup — an existing media is returned (duplicate flag,
 *       same id) BEFORE any object is written;</li>
 *   <li>metadata-extractor audit log of EXIF/GPS (M1) — logged only;</li>
 *   <li>full-resolution re-encode + 6 variant renders (thumbnailator; the
 *       stored original is never the raw bytes — metadata dies in the
 *       decode/re-encode);</li>
 *   <li>S3 writes (each key tracked);</li>
 *   <li>media + variant rows commit LAST, in one short transaction.</li>
 * </ol>
 * Any failure after the first object write triggers best-effort orphan
 * deletion of the already-written keys; object-storage failures surface as
 * 503 MED-12006, undecodable uploads as 400 MED-12001. A unique-index race
 * with a concurrent duplicate upload resolves to the winner's media
 * (duplicate:true) and cleans up the loser's objects.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MediaUploadServiceImpl implements MediaUploadService {

    private static final int SNIFF_LENGTH = 32;
    private static final String SHA_256 = "SHA-256";

    private final MediaRepository mediaRepository;
    private final ObjectStorageService storage;
    private final VariantRenderer renderer;
    private final MediaMetadataInspector metadataInspector;
    private final MediaMetrics metrics;
    private final MediaProperties properties;
    private final TransactionTemplate transactionTemplate;

    @Override
    public MediaResponse upload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            metrics.recordUpload(MediaMetrics.OUTCOME_REJECTED);
            throw BusinessException.of(ErrorCode.MEDIA_INVALID_FILE);
        }

        // 1. allowlist on the declared type (415)
        String format = MediaFormats.formatOfContentType(file.getContentType());
        if (format == null) {
            log.warn("Upload rejected: content type '{}' not in allowlist", file.getContentType());
            metrics.recordUpload(MediaMetrics.OUTCOME_REJECTED);
            throw BusinessException.of(ErrorCode.MEDIA_TYPE_NOT_ALLOWED);
        }

        // 2. magic bytes must exist and agree with the declared type (400)
        String sniffed = MediaFormats.sniff(headBytes(file));
        if (sniffed == null || !sniffed.equals(format)) {
            log.warn("Upload rejected: declared '{}' but magic bytes say '{}' (corrupt or lying upload)",
                    file.getContentType(), sniffed);
            metrics.recordUpload(MediaMetrics.OUTCOME_REJECTED);
            throw BusinessException.of(ErrorCode.MEDIA_INVALID_FILE);
        }

        // 3. business size guard (413) — servlet ceiling sits above this
        if (file.getSize() > properties.maxUploadBytes()) {
            log.warn("Upload rejected: {} bytes exceed cap of {} bytes", file.getSize(), properties.maxUploadBytes());
            metrics.recordUpload(MediaMetrics.OUTCOME_REJECTED);
            throw BusinessException.of(ErrorCode.MEDIA_TOO_LARGE);
        }

        byte[] source = readAll(file);

        // 4. SHA-256 dedup — BEFORE any S3 write (D1)
        String sha256 = sha256(source);
        var existing = mediaRepository.findBySha256(sha256);
        if (existing.isPresent()) {
            log.info("Upload dedup hit: sha256={} resolves to existing media {}", sha256, existing.get().getId());
            metrics.recordUpload(MediaMetrics.OUTCOME_DUPLICATE);
            return toResponse(existing.get(), true);
        }

        // 5. metadata audit log (M1) — inspect, log, never store
        metadataInspector.inspect(source);

        // 6. decode + full-res re-encode + six variants (corrupt render → 400)
        List<VariantRenderer.Render> renders;
        try {
            renders = renderer.render(source, format, properties.displayWidth(), properties.thumbWidth());
        } catch (VariantRenderer.InvalidImageException e) {
            log.warn("Upload rejected: magic-valid '{}' bytes are not decodable — {}", format, e.getMessage());
            metrics.recordUpload(MediaMetrics.OUTCOME_REJECTED);
            throw BusinessException.of(ErrorCode.MEDIA_INVALID_FILE);
        }

        UUID mediaId = UUID.randomUUID();
        List<MediaVariant> variants = new ArrayList<>(renders.size());
        for (VariantRenderer.Render render : renders) {
            String objectKey = objectKey(mediaId, render.variant(), render.format());
            variants.add(MediaVariant.builder()
                    .variant(render.variant())
                    .format(render.format())
                    .width(render.width())
                    .bytes(render.bytes().length)
                    .objectKey(objectKey)
                    .build());
        }

        // 7. S3 writes first — every key tracked for orphan cleanup
        Set<String> writtenKeys = new LinkedHashSet<>();
        try {
            for (int i = 0; i < renders.size(); i++) {
                VariantRenderer.Render render = renders.get(i);
                MediaVariant variant = variants.get(i);
                storage.upload(properties.bucket(), variant.getObjectKey(),
                        new ByteArrayInputStream(render.bytes()), render.bytes().length,
                        MediaFormats.contentTypeOf(variant.getFormat()));
                writtenKeys.add(variant.getObjectKey());
            }
        } catch (StorageException e) {
            log.error("Object storage failed mid-upload — purging {} orphan object(s)", writtenKeys.size(), e);
            purgeOrphans(writtenKeys);
            metrics.recordUpload(MediaMetrics.OUTCOME_REJECTED);
            throw BusinessException.of(ErrorCode.MEDIA_STORAGE_UNAVAILABLE);
        }

        // 8. media row + variant rows commit LAST (short tx, no S3 inside)
        Media media = Media.builder()
                .id(mediaId)
                .sha256(sha256)
                .contentType(MediaFormats.contentTypeOf(format))
                .sizeBytes(source.length)
                .build();
        variants.forEach(variant -> {
            variant.setMedia(media); // owning side — mappedBy needs the FK set
            media.getVariants().add(variant);
        });
        try {
            Media saved = transactionTemplate.execute(tx -> mediaRepository.saveAndFlush(media));
            metrics.recordUpload(MediaMetrics.OUTCOME_CREATED);
            log.info("Media {} stored: sha256={}, {} variants, {} source bytes",
                    saved.getId(), sha256, saved.getVariants().size(), source.length);
            return toResponse(saved, false);
        } catch (DataIntegrityViolationException race) {
            // Concurrent duplicate upload won the unique index — keep THEIR
            // media, purge our now-orphaned objects.
            log.info("Dedup race lost for sha256={} — resolving to the winner's media", sha256);
            purgeOrphans(writtenKeys);
            Media winner = mediaRepository.findBySha256(sha256)
                    .orElseThrow(() -> race);
            metrics.recordUpload(MediaMetrics.OUTCOME_DUPLICATE);
            return toResponse(winner, true);
        } catch (RuntimeException persistFailure) {
            log.error("Media row persist failed after S3 writes — purging {} orphan object(s)",
                    writtenKeys.size(), persistFailure);
            purgeOrphans(writtenKeys);
            throw persistFailure;
        }
    }

    private byte[] headBytes(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            return in.readNBytes(SNIFF_LENGTH);
        } catch (IOException e) {
            throw BusinessException.of(ErrorCode.MEDIA_INVALID_FILE);
        }
    }

    private byte[] readAll(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            log.warn("Upload rejected: multipart bytes unreadable", e);
            metrics.recordUpload(MediaMetrics.OUTCOME_REJECTED);
            throw BusinessException.of(ErrorCode.MEDIA_INVALID_FILE);
        }
    }

    private static String sha256(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance(SHA_256);
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest unavailable", e);
        }
    }

    private String objectKey(UUID mediaId, String variant, String format) {
        return mediaId + "/" + variant + "." + MediaFormats.extOf(format);
    }

    private void purgeOrphans(Set<String> writtenKeys) {
        for (String key : writtenKeys) {
            try {
                storage.delete(properties.bucket(), key);
                log.info("Orphan object deleted: {}", key);
            } catch (Exception deleteFailure) {
                log.warn("Best-effort orphan delete failed for {} — left for the purge job", key, deleteFailure);
            }
        }
    }

    private MediaResponse toResponse(Media media, boolean duplicate) {
        List<MediaVariantResponse> variants = media.getVariants().stream()
                .map(v -> new MediaVariantResponse(v.getVariant(), v.getFormat(), v.getWidth(), v.getBytes(),
                        v.getObjectKey()))
                .toList();
        String canonicalPath = ApiPaths.MEDIAS + "/" + media.getId();
        return new MediaResponse(media.getId(), media.getSha256(), media.getContentType(), media.getSizeBytes(),
                canonicalPath, variants, duplicate);
    }
}
