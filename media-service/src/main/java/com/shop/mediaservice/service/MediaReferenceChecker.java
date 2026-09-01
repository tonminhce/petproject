package com.shop.mediaservice.service;

import java.util.UUID;

/**
 * PORT — answers whether a media is still referenced by an owning aggregate
 * (today: product.media_id). The MediaPurgeJob consults it before hard-delete
 * and SKIPS + logs WARN for referenced media, retrying next cycle (D3).
 *
 * <p>Why a port and not a direct query: the media database is SEPARATE from
 * the product database (spec D5) — there is no joinable FK, so the truth
 * lives behind another service's boundary. Task 5's product wiring closes
 * the loop (delete-time is EVENTUAL via the MediaDeleted consumer chain);
 * the purge grace window is the safety margin that makes the check
 * best-effort rather than load-bearing.</p>
 */
public interface MediaReferenceChecker {

    /**
     * True when some owning aggregate still points at this media. Callers
     * must treat false as "no known reference AT CHECK TIME", never as a
     * guarantee (races with concurrent product writes are possible by
     * design — the grace window absorbs them).
     */
    boolean isReferenced(UUID mediaId);
}
