package com.shop.mediaservice.service.impls;

import com.shop.mediaservice.client.MediaReferenceClient;
import com.shop.mediaservice.service.MediaReferenceChecker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * H-4 PRODUCTION implementation of the {@link MediaReferenceChecker} port:
 * asks product-service (via {@link MediaReferenceClient}, SERVICE token) how
 * many live products still reference the media, and answers REFERENCED when
 * the count is non-zero. Replaces the fail-safe-always Noop default of the
 * media epic (F-3) — purge candidates are now checked for real.
 *
 * <p><strong>PURGE POLICY (H-4, fail-safe):</strong> when the checker cannot
 * PROVE the media is unreferenced — product outage, non-2xx, timeout,
 * malformed body (client answers empty) — the answer is REFERENCED: the purge
 * job skips the media with a WARN and retries next cycle. Purge never
 * hard-deletes on doubt; soft-deleted objects accumulating during an outage
 * is the accepted cost. (The job additionally treats ANY exception thrown by
 * this checker the same way — belt and braces for failure modes outside the
 * client's mapped ones.) A successful count of 0 is the ONLY purge-green
 * answer.</p>
 */
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
