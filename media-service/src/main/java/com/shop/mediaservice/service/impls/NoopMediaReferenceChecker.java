package com.shop.mediaservice.service.impls;

import com.shop.mediaservice.service.MediaReferenceChecker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Production default of the {@link MediaReferenceChecker} PORT: FAIL-SAFE
 * answer — every media is treated as REFERENCED, so the purge job skips all
 * candidates (WARN per cycle) until a real checker exists. Skipping is the
 * safe direction: soft-deleted objects accumulate during the grace window
 * and beyond, but nothing referenced can be hard-purged into broken product
 * images.
 *
 * <p><strong>KNOWN LIMITATION (final review F-3, waived):</strong> the media
 * DB is separate from the product DB (spec D5) — no FK, no join — and
 * product-service exposes no reference-check endpoint, so a truthful answer
 * is impossible from inside media-service today. DELETE-TIME is still
 * EVENTUAL: the MediaDeleted consumer chain clears {@code products.media_id}.
 * A REAL checker (product-side reference endpoint + purge-side
 * reconciliation) is a follow-up ticket / future epic; until it lands the
 * purge is a documented no-op by fail-safe design (see
 * docs/PRODUCTION-READINESS.md, media runbook).
 */
@Component
@Slf4j
public class NoopMediaReferenceChecker implements MediaReferenceChecker {

    @Override
    public boolean isReferenced(UUID mediaId) {
        log.warn("NoopMediaReferenceChecker: no real reference checker is wired — "
                + "treating media {} as REFERENCED (purge skips; follow-up epic: "
                + "product-side reference endpoint)", mediaId);
        return true;
    }
}
