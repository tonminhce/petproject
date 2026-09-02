package com.shop.mediaservice.job;

import com.shop.common.storage.service.ObjectStorageService;
import com.shop.mediaservice.config.MediaProperties;
import com.shop.mediaservice.repository.MediaRepository;
import com.shop.mediaservice.service.MediaReferenceChecker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * D3 purge: hard-deletes the S3 objects of media whose grace window has
 * fully elapsed ({@code deleted_at <= now - media.purge-grace}, default 30d),
 * then removes the rows (the variant rows cascade with the media row).
 * Scheduling shape ports rating-service's outbox relay: {@code @Scheduled}
 * fixed-delay, one batch per tick, per-item failure isolation — one broken
 * media never aborts the cycle, its purge simply retries next tick.
 *
 * <p>Ordering is objects-then-rows: if the run dies mid-way, the row survives
 * (with its object keys) and the next cycle replays idempotently — S3 delete
 * of an already-gone key is a no-op. A media still REPORTED referenced by the
 * {@link MediaReferenceChecker} port is skipped with a WARN and retried next
 * cycle — never deleted behind a live reference.</p>
 *
 * <p>H-4: the checker call sits INSIDE the per-row try — the checker (the
 * real one consults product-service over HTTP) failing for ONE row must never
 * abort the cycle. A checker exception is fail-safe REFERENCED (WARN + skip,
 * same as a mapped "unavailable" answer from the checker itself); the cycle
 * moves on to the next candidate. Only a successful zero-reference answer
 * lets {@link #purgeOne} run.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MediaPurgeJob {

    private final MediaRepository mediaRepository;
    private final ObjectStorageService storage;
    private final MediaReferenceChecker referenceChecker;
    private final MediaProperties properties;

    @Scheduled(fixedDelayString = "${media.purge.poll-millis:3600000}")
    public void purge() {
        Instant cutoff = Instant.now().minus(properties.purgeGrace());
        List<UUID> candidates = mediaRepository.findPurgeableIds(cutoff);
        if (candidates.isEmpty()) {
            return;
        }
        log.info("Purge cycle: {} media past the {} grace window", candidates.size(), properties.purgeGrace());
        for (UUID mediaId : candidates) {
            try {
                if (referenceChecker.isReferenced(mediaId)) {
                    log.warn("Media {} is still referenced — purge SKIPPED, will retry next cycle", mediaId);
                    continue;
                }
            } catch (Exception checkerFailure) {
                // H-4 fail-safe: cannot PROVE the media is unreferenced → keep it.
                // WARN (not ERROR): expected during product outages; the row simply
                // retries next cycle. The cycle continues with the next candidate.
                log.warn("Reference check FAILED for media {} — FAIL-SAFE: treating as REFERENCED, "
                        + "purge skips (cycle continues)", mediaId, checkerFailure);
                continue;
            }
            purgeOne(mediaId);
        }
    }

    private void purgeOne(UUID mediaId) {
        try {
            List<String> objectKeys = mediaRepository.findObjectKeysByMediaId(mediaId);
            for (String objectKey : objectKeys) {
                storage.delete(properties.bucket(), objectKey);
                log.info("Purged object {} of media {}", objectKey, mediaId);
            }
            mediaRepository.deleteIncludingDeleted(mediaId);
            log.info("Media {} purged — {} object(s) hard-deleted, rows removed", mediaId, objectKeys.size());
        } catch (Exception e) {
            log.error("Purge failed for media {} — will retry next cycle", mediaId, e);
        }
    }
}
