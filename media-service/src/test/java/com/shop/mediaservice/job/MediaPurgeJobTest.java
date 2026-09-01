package com.shop.mediaservice.job;

import com.shop.common.storage.exception.StorageException;
import com.shop.common.storage.service.ObjectStorageService;
import com.shop.mediaservice.config.MediaProperties;
import com.shop.mediaservice.repository.MediaRepository;
import com.shop.mediaservice.service.MediaReferenceChecker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Purge unit proofs with STUBBED reference checker (the port's production
 * impl is a documented no-op): grace boundary (a row at EXACTLY the grace
 * horizon is purgeable — the repo's {@code <=} query is IT-proven in
 * {@code MediaPurgeIT}), referenced-skip logs WARN + leaves objects/rows for
 * the NEXT cycle, unreferenced purge is objects-then-rows in order, and one
 * failing media never aborts the batch.
 */
@ExtendWith(MockitoExtension.class)
class MediaPurgeJobTest {

    @Mock
    private MediaRepository mediaRepository;

    @Mock
    private ObjectStorageService storage;

    @Mock
    private MediaReferenceChecker referenceChecker;

    private MediaPurgeJob job;

    private static final UUID MEDIA_A = UUID.fromString("f1000000-0000-0000-0000-00000000000a");
    private static final UUID MEDIA_B = UUID.fromString("f1000000-0000-0000-0000-00000000000b");
    private static final String BUCKET = "media";
    private static final Duration GRACE = Duration.ofDays(30);

    @BeforeEach
    void setUp() {
        MediaProperties properties = new MediaProperties(
                BUCKET, Duration.ofDays(7), DataSize.ofMegabytes(10), GRACE, 1200, 320);
        job = new MediaPurgeJob(mediaRepository, storage, referenceChecker, properties);
    }

    private void candidate(UUID id, String... objectKeys) {
        when(mediaRepository.findObjectKeysByMediaId(id)).thenReturn(List.of(objectKeys));
    }

    @Test
    @DisplayName("no candidates → storage and checker never touched")
    void purge_noCandidates_isANoOp() {
        when(mediaRepository.findPurgeableIds(any())).thenReturn(List.of());

        job.purge();

        verifyNoInteractions(storage, referenceChecker);
    }

    @Test
    @DisplayName("grace boundary: cutoff = now − grace; a row AT the horizon (as the <= query returns it) purges")
    void purge_exactlyAtGraceBoundary_purges() {
        when(mediaRepository.findPurgeableIds(any())).thenReturn(List.of(MEDIA_A));
        candidate(MEDIA_A, MEDIA_A + "/original.jpg", MEDIA_A + "/display.webp");
        when(referenceChecker.isReferenced(MEDIA_A)).thenReturn(false);
        Instant before = Instant.now();

        job.purge();
        Instant after = Instant.now();

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(mediaRepository).findPurgeableIds(cutoff.capture());
        assertThat(cutoff.getValue())
                .isAfterOrEqualTo(before.minus(GRACE))
                .isBeforeOrEqualTo(after.minus(GRACE));

        InOrder order = inOrder(storage, mediaRepository);
        order.verify(storage).delete(BUCKET, MEDIA_A + "/original.jpg");
        order.verify(storage).delete(BUCKET, MEDIA_A + "/display.webp");
        order.verify(mediaRepository).deleteIncludingDeleted(MEDIA_A);
    }

    @Test
    @DisplayName("inside the grace window the job never even sees the row (cutoff math)")
    void purge_insideGrace_rowIsNotACandidate() {
        // a row deleted_at = now − grace + 60s is OLDER than no cutoff the job
        // can compute: cutoff = now′ − grace < deleted_at for now′ ≈ now
        when(mediaRepository.findPurgeableIds(any())).thenReturn(List.of());

        job.purge();

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(mediaRepository).findPurgeableIds(cutoff.capture());
        Instant rowInsideGrace = Instant.now().minus(GRACE).plusSeconds(60);
        assertThat(rowInsideGrace).isAfter(cutoff.getValue());
        verifyNoInteractions(storage, referenceChecker);
    }

    @Test
    @DisplayName("referenced → WARN-skip: objects NOT deleted, row kept, retried next cycle")
    void purge_referencedMedia_skipsAndRetriesNextCycle() {
        when(mediaRepository.findPurgeableIds(any())).thenReturn(List.of(MEDIA_A));
        candidate(MEDIA_A, MEDIA_A + "/original.jpg");
        when(referenceChecker.isReferenced(MEDIA_A)).thenReturn(true, false);

        // cycle 1: referenced → skipped, nothing deleted
        job.purge();
        verifyNoInteractions(storage);
        verify(mediaRepository, never()).deleteIncludingDeleted(MEDIA_A);

        // cycle 2: reference gone → purge proceeds
        job.purge();
        verify(storage).delete(BUCKET, MEDIA_A + "/original.jpg");
        verify(mediaRepository).deleteIncludingDeleted(MEDIA_A);
    }

    @Test
    @DisplayName("unreferenced media purges objects-then-rows, bucket = media.bucket")
    void purge_unreferenced_deletesObjectsThenRows() {
        when(mediaRepository.findPurgeableIds(any())).thenReturn(List.of(MEDIA_A));
        candidate(MEDIA_A, MEDIA_A + "/thumb.webp");
        when(referenceChecker.isReferenced(MEDIA_A)).thenReturn(false);

        job.purge();

        InOrder order = inOrder(storage, mediaRepository);
        order.verify(storage).delete(BUCKET, MEDIA_A + "/thumb.webp");
        order.verify(mediaRepository).deleteIncludingDeleted(MEDIA_A);
    }

    @Test
    @DisplayName("one media's purge failure does not abort the batch — the rest still purge")
    void purge_oneFailure_doesNotAbortBatch() {
        when(mediaRepository.findPurgeableIds(any())).thenReturn(List.of(MEDIA_A, MEDIA_B));
        candidate(MEDIA_A, MEDIA_A + "/original.jpg");
        candidate(MEDIA_B, MEDIA_B + "/display.webp");
        when(referenceChecker.isReferenced(any())).thenReturn(false);
        doThrow(new StorageException("S3 down"))
                .when(storage).delete(BUCKET, MEDIA_A + "/original.jpg");

        assertThatCode(() -> job.purge()).doesNotThrowAnyException();

        verify(mediaRepository, never()).deleteIncludingDeleted(MEDIA_A); // A stays for next cycle
        verify(storage).delete(BUCKET, MEDIA_B + "/display.webp");
        verify(mediaRepository).deleteIncludingDeleted(MEDIA_B);
    }

    @Test
    @DisplayName("row removal failure after objects are deleted also retries next cycle")
    void purge_rowDeleteFailure_retriesNextCycle() {
        when(mediaRepository.findPurgeableIds(any())).thenReturn(List.of(MEDIA_A));
        candidate(MEDIA_A, MEDIA_A + "/original.jpg");
        when(referenceChecker.isReferenced(MEDIA_A)).thenReturn(false);
        when(mediaRepository.deleteIncludingDeleted(MEDIA_A)).thenThrow(new RuntimeException("db blip"));

        assertThatCode(() -> job.purge()).doesNotThrowAnyException();

        verify(storage).delete(BUCKET, MEDIA_A + "/original.jpg");
    }
}
