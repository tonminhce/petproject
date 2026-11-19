package com.shop.mediaservice.service.impls;

import com.shop.mediaservice.client.MediaReferenceClient;
import com.shop.mediaservice.service.MediaReferenceChecker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class ProductMediaReferenceChecker implements MediaReferenceChecker {

    private final MediaReferenceClient referenceClient;

    /**
     * @return {@code true} unless product-service provably reports zero live
     *         references (or the client reports the media as referenced);
     *         failures of any shape fail safe to REFERENCED (+ WARN)
     */
    @Override
    public boolean isReferenced(UUID mediaId) {
        var count = referenceClient.referenceCount(mediaId);
        if (count.isEmpty()) {
            log.warn("Reference check UNAVAILABLE for media {} — FAIL-SAFE: treating as REFERENCED, "
                    + "purge skips this cycle", mediaId);
            return true;
        }
        boolean referenced = count.getAsLong() > 0;
        log.debug("Reference check for media {}: {} live product reference(s) — referenced={}",
                mediaId, count.getAsLong(), referenced);
        return referenced;
    }
}
