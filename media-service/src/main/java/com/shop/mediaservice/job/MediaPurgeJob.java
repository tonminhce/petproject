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
