package com.shop.mediaservice.service.impls;

import com.shop.mediaservice.service.MediaReferenceChecker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Production default of the {@link MediaReferenceChecker} PORT: answers
 * "not referenced" for EVERY media.
 *
 * <p><strong>KNOWN LIMITATION (flagged for final review):</strong> this is a
 * stub. The media DB is separate from the product DB (spec D5) — no FK, no
 * join — so a truthful answer requires asking product-service (in-process
 * client or via the T5 wiring). Delete-time is EVENTUAL by design: the
 * MediaDeleted consumer chain clears {@code products.media_id}; the purge
 * grace window (default 30d) is the safety window that makes this
 * best-effort answer survivable — a referenced media whose reference is
 * cleared inside the grace is never actually purged, and a WARN-skip only
 * postpones the hard delete.</p>
 */
@Component
@Slf4j
public class NoopMediaReferenceChecker implements MediaReferenceChecker {

    @Override
    public boolean isReferenced(UUID mediaId) {
        // TODO(media-reference-check): once the product-service wiring exists
        // (T5 / client port), replace with a real cross-DB reference query —
        // media DB and product DB are separate (D5), so this cannot be a
        // local join. Until then the purge job treats every purged media as
        // unreferenced; the grace window + eventual MediaDeleted consumer
        // chain are the compensating controls.
        return false;
    }
}
