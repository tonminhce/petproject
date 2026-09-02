package com.shop.mediaservice.service.impls;

import com.shop.common.storage.service.ObjectStorageService;
import com.shop.mediaservice.config.MediaProperties;
import com.shop.mediaservice.entity.Media;
import com.shop.mediaservice.entity.MediaVariant;
import com.shop.mediaservice.job.MediaPurgeJob;
import com.shop.mediaservice.metrics.MediaMetrics;
import com.shop.mediaservice.outbox.MediaEventService;
import com.shop.mediaservice.repository.MediaRepository;
import com.shop.mediaservice.service.MediaReferenceChecker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.unit.DataSize;

import java.io.InputStream;
import java.net.URL;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

/**
 * H-5 ONE-SOURCE-OF-TRUTH proof: a single {@link MediaProperties} instance
 * (bucket = a deliberately non-default name) drives ALL object-storage
 * traffic — upload (write), orphan cleanup (delete), read presign, and purge
 * (delete). Historically presign rode the 2-arg default-bucket chain
 * ({@code shop.storage.bucket}) while upload/purge used {@code media.bucket}
 * — same value today, two drift-capable trees. Every call now goes through
 * the bucket-qualified overloads with {@code media.bucket}, and
 * {@code defaultBucket()} is never consulted.
 */
@ExtendWith(MockitoExtension.class)
class MediaBucketUnificationTest {

    /** NOT the common-storage default bucket name — proves the property, not the chain. */
    private static final String BUCKET = "media-unified-bucket";

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
    @Mock
    private MediaReferenceChecker referenceChecker;

    private MediaProperties properties;
    private MediaUploadServiceImpl uploadService;
    private MediaQueryServiceImpl queryService;
    private MediaPurgeJob purgeJob;

    private static final UUID MEDIA_ID = UUID.fromString("e1000000-0000-0000-0000-000000000001");

    @BeforeEach
    void setUp() {
        properties = new MediaProperties(
                BUCKET, Duration.ofDays(7), DataSize.ofMegabytes(10), Duration.ofDays(30), 1200, 320);
        uploadService = new MediaUploadServiceImpl(
                mediaRepository, storage, renderer, metadataInspector,
                new MediaMetrics(new SimpleMeterRegistry()), properties,
                transactionTemplate, mediaEvents);
        queryService = new MediaQueryServiceImpl(
                mediaRepository, storage, new MediaMetrics(new SimpleMeterRegistry()), properties);
        purgeJob = new MediaPurgeJob(mediaRepository, storage, referenceChecker, properties);
    }

    private byte[] jpegMagicBytes() {
        // any bytes with valid jpeg magic pass the pipeline guards here — the
        // renderer and repository are mocked, this test is about the BUCKET
        // ARGUMENT (sniff requires ≥12 bytes)
        byte[] head = new byte[16];
        head[0] = (byte) 0xFF; head[1] = (byte) 0xD8; head[2] = (byte) 0xFF; head[3] = (byte) 0xE0;
        return head;
    }

    @Test
    @DisplayName("upload writes every render into media.bucket (5-arg bucket-qualified overload)")
    void upload_writesToMediaBucket() throws Exception {
        when(renderer.render(any(), anyString(), anyInt(), anyInt())).thenReturn(List.of(
                new VariantRenderer.Render("original", "jpeg", 100, new byte[] {1})));
        when(mediaRepository.findBySha256(anyString())).thenReturn(Optional.empty());
        when(transactionTemplate.execute(any())).thenAnswer(inv ->
                ((TransactionCallback<Media>) inv.getArgument(0)).doInTransaction(null));
        when(mediaRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(storage.upload(eq(BUCKET), anyString(), any(InputStream.class), anyLong(), anyString()))
                .thenAnswer(inv -> inv.getArgument(1));

        var response = uploadService.upload(
                new org.springframework.mock.web.MockMultipartFile("file", "a.jpg", "image/jpeg", jpegMagicBytes()));

        // the write went to media.bucket with the persisted media's own key,
        // and NO default-bucket-chain overload was ever touched
        verify(storage).upload(eq(BUCKET), eq(response.id() + "/original.jpg"),
                any(InputStream.class), anyLong(), eq("image/jpeg"));
        verify(storage, never()).upload(anyString(), any(byte[].class), anyString());
        verify(storage, never()).defaultBucket();
    }

    @Test
    @DisplayName("persist failure after S3 writes → orphan purge deletes from media.bucket too")
    void orphanCleanup_deletesFromMediaBucket() throws Exception {
        when(renderer.render(any(), anyString(), anyInt(), anyInt())).thenReturn(List.of(
                new VariantRenderer.Render("original", "jpeg", 100, new byte[] {1})));
        when(mediaRepository.findBySha256(anyString())).thenReturn(Optional.empty());
        when(transactionTemplate.execute(any())).thenThrow(new RuntimeException("db down"));
        when(storage.upload(eq(BUCKET), anyString(), any(InputStream.class), anyLong(), anyString()))
                .thenAnswer(inv -> inv.getArgument(1));

        try {
            uploadService.upload(new org.springframework.mock.web.MockMultipartFile(
                    "file", "a.jpg", "image/jpeg", jpegMagicBytes()));
        } catch (RuntimeException expected) {
            // rethrown after orphan cleanup
        }

        ArgumentCaptor<String> orphanKey = ArgumentCaptor.forClass(String.class);
        verify(storage).delete(eq(BUCKET), orphanKey.capture());
        assertThat(orphanKey.getValue()).endsWith("/original.jpg");
        verify(storage, never()).delete(anyString());
        verify(storage, never()).defaultBucket();
    }

    @Test
    @DisplayName("read presigns against media.bucket via the 3-arg overload")
    void read_presignsAgainstMediaBucket() throws Exception {
        Media media = Media.builder().id(MEDIA_ID).sha256("a".repeat(64)).contentType("image/jpeg").build();
        MediaVariant row = MediaVariant.builder().variant("display").format("webp")
                .width(100).bytes(10).objectKey(MEDIA_ID + "/display.webp").build();
        media.getVariants().add(row);
        when(mediaRepository.findById(MEDIA_ID)).thenReturn(Optional.of(media));
        URL url = new URL("http://object-store.test/" + BUCKET + "/" + MEDIA_ID + "/display.webp");
        when(storage.presignedGetUrl(BUCKET, MEDIA_ID + "/display.webp", Duration.ofDays(7))).thenReturn(url);

        assertThat(queryService.resolve(MEDIA_ID, "display", "auto")).isEqualTo(url);
        verify(storage, never()).presignedGetUrl(anyString(), any(Duration.class));
        verify(storage, never()).defaultBucket();
    }

    @Test
    @DisplayName("purge deletes objects from media.bucket, objects-then-rows")
    void purge_deletesFromMediaBucket() {
        when(mediaRepository.findPurgeableIds(any())).thenReturn(List.of(MEDIA_ID));
        when(mediaRepository.findObjectKeysByMediaId(MEDIA_ID)).thenReturn(List.of(MEDIA_ID + "/original.jpg"));
        when(referenceChecker.isReferenced(MEDIA_ID)).thenReturn(false);

        purgeJob.purge();

        InOrder order = inOrder(storage, mediaRepository);
        order.verify(storage).delete(BUCKET, MEDIA_ID + "/original.jpg");
        order.verify(mediaRepository).deleteIncludingDeleted(MEDIA_ID);
        verify(storage, never()).delete(anyString());
        verify(storage, never()).defaultBucket();
    }
}
