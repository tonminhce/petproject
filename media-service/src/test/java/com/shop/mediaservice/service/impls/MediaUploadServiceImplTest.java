package com.shop.mediaservice.service.impls;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.storage.service.ObjectStorageService;
import com.shop.mediaservice.config.MediaProperties;
import com.shop.mediaservice.dto.response.MediaResponse;
import com.shop.mediaservice.entity.Media;
import com.shop.mediaservice.metrics.MediaMetrics;
import com.shop.mediaservice.outbox.MediaEventService;
import com.shop.mediaservice.repository.MediaRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D1 dedup + persist-failure unit proofs (the real-DB dedup story lives in
 * UploadIT / MediaOutboxIT): the unique-index race is resolved to the WINNER's
 * media with duplicate:true — the loser's freshly written objects are purged
 * first — and a persist failure that is NOT a unique race rethrows after
 * orphan cleanup (the caller sees the failure, storage holds no orphans).
 */
@ExtendWith(MockitoExtension.class)
class MediaUploadServiceImplTest {

    private static final String SHA = "a".repeat(64);

    @Mock
    private MediaRepository mediaRepository;
    @Mock
    private ObjectStorageService storage;
    @Mock
    private VariantRenderer renderer;
    @Mock
    private MediaMetadataInspector metadataInspector;
    @Mock
    private MediaEventService mediaEvents;
    @Mock
    private TransactionTemplate transactionTemplate;

    private MediaUploadServiceImpl service;

    @BeforeEach
    void setUp() {
        MediaProperties properties = new MediaProperties(
                "media", Duration.ofDays(7), DataSize.ofMegabytes(10), Duration.ofDays(30), 1200, 320);
        service = new MediaUploadServiceImpl(
                mediaRepository, storage, renderer, metadataInspector,
                new MediaMetrics(new SimpleMeterRegistry()), properties,
                transactionTemplate, mediaEvents);
    }

    private MultipartFile jpeg() {
        byte[] head = new byte[16];
        head[0] = (byte) 0xFF; head[1] = (byte) 0xD8; head[2] = (byte) 0xFF; head[3] = (byte) 0xE0;
        return new org.springframework.mock.web.MockMultipartFile("file", "a.jpg", "image/jpeg", head);
    }

    private void pipelineUpToPersist(UUID loserId) throws Exception {
        when(renderer.render(any(), anyString(), anyInt(), anyInt())).thenReturn(List.of(
                new VariantRenderer.Render("original", "jpeg", 100, new byte[] {1})));
        when(mediaRepository.findBySha256(anyString()))
                .thenReturn(Optional.empty())      // dedup probe: no winner yet
                .thenReturn(Optional.of(Media.builder().id(loserId).sha256(SHA).build())); // after the race
        when(storage.upload(eq("media"), anyString(), any(InputStream.class), anyLong(), anyString()))
                .thenAnswer(inv -> inv.getArgument(1));
        when(transactionTemplate.execute(any())).thenThrow(
                new DataIntegrityViolationException("uk_medias_sha256 violated"));
    }

    @Test
    @DisplayName("dedup concurrent race: unique violation → loser's objects purged, winner's media duplicate:true")
    void dedupRace_resolvesToWinnersMedia_duplicateTrue() throws Exception {
        UUID winnerId = UUID.randomUUID();
        pipelineUpToPersist(winnerId);

        MediaResponse response = service.upload(jpeg());

        assertThat(response.id()).as("the WINNER's media is returned").isEqualTo(winnerId);
        assertThat(response.duplicate()).isTrue();

        // the LOSER's freshly written object was purged (its own random key,
        // distinct from the winner's row), then the winner was re-read
        org.mockito.ArgumentCaptor<String> orphanKey = org.mockito.ArgumentCaptor.forClass(String.class);
        InOrder order = inOrder(storage, mediaRepository);
        order.verify(storage).delete(eq("media"), orphanKey.capture());
        assertThat(orphanKey.getValue()).endsWith("/original.jpg");
        order.verify(mediaRepository).findBySha256(anyString());
        // loser never re-inserted — the response IS the winner's row
        verify(mediaRepository, never()).deleteIncludingDeleted(any());
    }

    @Test
    @DisplayName("persist failure that is NOT a unique race → orphans purged and the failure RETHROWN")
    void persistFailure_purgesOrphansAndRethrows() throws Exception {
        when(renderer.render(any(), anyString(), anyInt(), anyInt())).thenReturn(List.of(
                new VariantRenderer.Render("original", "jpeg", 100, new byte[] {1})));
        when(mediaRepository.findBySha256(anyString())).thenReturn(Optional.empty());
        when(storage.upload(eq("media"), anyString(), any(InputStream.class), anyLong(), anyString()))
                .thenAnswer(inv -> inv.getArgument(1));
        when(transactionTemplate.execute(any())).thenThrow(new RuntimeException("db down"));

        assertThatThrownBy(() -> service.upload(jpeg()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("db down");

        // the object the pipeline wrote was cleaned up — no orphan storage
        verify(storage).delete(eq("media"), anyString());
    }
}
