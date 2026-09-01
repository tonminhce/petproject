package com.shop.mediaservice.service.impls;

import com.shop.mediaservice.job.MediaPurgeJob;
import com.shop.mediaservice.service.MediaReferenceChecker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-3 fail-safe contract of the production default bean: with no real
 * reference checker wired (product-side reference endpoint is a follow-up
 * epic), the Noop answers REFERENCED for every media so the purge job skips
 * all candidates — soft-deleted objects accumulate by design instead of
 * referenced media being hard-purged into broken product images.
 * The purge-path proofs with a stubbed-false checker live in
 * {@link MediaPurgeJobTest} / {@code MediaPurgeIT}.
 */
class NoopMediaReferenceCheckerTest {

    private final MediaReferenceChecker checker = new NoopMediaReferenceChecker();

    @Test
    @DisplayName("fail-safe: always REFERENCED → purge skips until a real checker lands")
    void isReferenced_alwaysTrue() {
        assertThat(checker.isReferenced(UUID.randomUUID())).isTrue();
        assertThat(checker.isReferenced(UUID.randomUUID())).isTrue();
    }
}
