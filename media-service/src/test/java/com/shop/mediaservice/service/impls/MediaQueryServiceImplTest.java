package com.shop.mediaservice.service.impls;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.storage.exception.StorageException;
import com.shop.common.storage.service.ObjectStorageService;
import com.shop.mediaservice.config.MediaProperties;
import com.shop.mediaservice.entity.Media;
import com.shop.mediaservice.entity.MediaVariant;
import com.shop.mediaservice.metrics.MediaMetrics;
import com.shop.mediaservice.repository.MediaRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.util.unit.DataSize;

import java.net.URL;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * D3 read resolution unit proofs: variant/format selection (auto → WebP
 * when stored, fallback to the original-format render; webp → WebP-only),
 * 404 MED-12004 for unknown media/variant/row, 503 MED-12006 on storage
 * failure, TTL pass-through, and the {@code media_presigned_total{variant}}
 * meter.
 */
@ExtendWith(MockitoExtension.class)
class MediaQueryServiceImplTest {

    @Mock
    private MediaRepository mediaRepository;

    @Mock
    private ObjectStorageService storage;

    private SimpleMeterRegistry registry;
    private MediaQueryServiceImpl queryService;

    private static final UUID MEDIA_ID = UUID.fromString("d1000000-0000-0000-0000-000000000001");
    private static final Duration TTL = Duration.ofDays(7);
    private static final String BUCKET = "media";

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        MediaProperties properties = new MediaProperties(
                "media", TTL, DataSize.ofMegabytes(10), Duration.ofDays(30), 1200, 320);
        queryService = new MediaQueryServiceImpl(
                mediaRepository, storage, new MediaMetrics(registry), properties);
    }

    // --- fixtures ---

    private Media media(MediaVariant... rows) {
        Media media = Media.builder().id(MEDIA_ID).sha256("a".repeat(64)).contentType("image/jpeg").build();
        media.getVariants().addAll(List.of(rows));
        return media;
    }

    private MediaVariant row(String variant, String format) {
        return MediaVariant.builder()
                .variant(variant)
                .format(format)
                .width(1200)
                .bytes(1234)
                .objectKey(MEDIA_ID + "/" + variant + "." + MediaFormats.extOf(format))
                .build();
    }

    private void stored(MediaVariant... rows) {
        when(mediaRepository.findById(MEDIA_ID)).thenReturn(Optional.of(media(rows)));
    }

    private URL presignedFor(String objectKey) throws Exception {
        URL url = new URL("http://minio.test:9000/media/" + objectKey + "?X-Amz-Signature=sig");
        // H-5: reads presign against the EXPLICIT media.bucket (3-arg overload)
        when(storage.presignedGetUrl(BUCKET, objectKey, TTL)).thenReturn(url);
        return url;
    }

    private double presignCounter(String variant) {
        var counter = registry.find("media_presigned_total").tag("variant", variant).counter();
        return counter == null ? 0.0 : counter.count();
    }

    // --- format resolution: auto prefers WebP, falls back to original format ---

    @Test
    @DisplayName("auto → WebP row when stored")
    void autoPrefersWebpWhenStored() throws Exception {
        stored(row("display", "jpeg"), row("display", "webp"));
        URL expected = presignedFor(MEDIA_ID + "/display.webp");

        assertThat(queryService.resolve(MEDIA_ID, "display", "auto")).isEqualTo(expected);
        assertThat(presignCounter("display")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("auto → falls back to the original-format row when no WebP render exists")
    void autoFallsBackToOriginalFormatWhenNoWebp() throws Exception {
        stored(row("thumb", "png"));
        URL expected = presignedFor(MEDIA_ID + "/thumb.png");

        assertThat(queryService.resolve(MEDIA_ID, "thumb", "auto")).isEqualTo(expected);
    }

    @Test
    @DisplayName("webp → WebP row when stored")
    void webpUsesTheWebpRowWhenStored() throws Exception {
        stored(row("thumb", "jpeg"), row("thumb", "webp"));
        URL expected = presignedFor(MEDIA_ID + "/thumb.webp");

        assertThat(queryService.resolve(MEDIA_ID, "thumb", "webp")).isEqualTo(expected);
    }

    @Test
    @DisplayName("webp without a WebP render → 404 MED-12004, storage never touched")
    void webpWithoutWebpRowIs404() {
        stored(row("original", "jpeg"));

        assertThatThrownBy(() -> queryService.resolve(MEDIA_ID, "original", "webp"))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo("MED-12004");
                    assertThat(ex.getStatus().value()).isEqualTo(404);
                });
        verifyNoInteractions(storage);
    }

    // --- variant selection ---

    @Test
    @DisplayName("each variant name resolves to its own row; default variant is display")
    void variantSelectionAndDefault() throws Exception {
        stored(row("original", "jpeg"), row("display", "webp"), row("thumb", "webp"));
        URL originalUrl = presignedFor(MEDIA_ID + "/original.jpg");
        URL thumbUrl = presignedFor(MEDIA_ID + "/thumb.webp");
        URL displayUrl = presignedFor(MEDIA_ID + "/display.webp");

        assertThat(queryService.resolve(MEDIA_ID, "original", "auto")).isEqualTo(originalUrl);
        assertThat(queryService.resolve(MEDIA_ID, "thumb", "webp")).isEqualTo(thumbUrl);

        // null variant → display; null format → auto
        assertThat(queryService.resolve(MEDIA_ID, null, null)).isEqualTo(displayUrl);
        assertThat(presignCounter("display")).isEqualTo(1.0);
        assertThat(presignCounter("thumb")).isEqualTo(1.0);
        assertThat(presignCounter("original")).isEqualTo(1.0);
    }

    // --- 404 family ---

    @Test
    @DisplayName("unknown variant name → 404 MED-12004, storage never touched")
    void unknownVariantNameIs404() {
        assertThatThrownBy(() -> queryService.resolve(MEDIA_ID, "hero", "auto"))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo("MED-12004"));

        verifyNoInteractions(mediaRepository, storage);
    }

    @Test
    @DisplayName("unknown media id → 404 MED-12004")
    void unknownMediaIdIs404() {
        when(mediaRepository.findById(MEDIA_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> queryService.resolve(MEDIA_ID, "display", "auto"))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo("MED-12004"));
        verifyNoInteractions(storage);
    }

    @Test
    @DisplayName("media without any row for the requested variant → 404 MED-12004")
    void variantRowMissingIs404() {
        stored(row("display", "webp"));

        assertThatThrownBy(() -> queryService.resolve(MEDIA_ID, "thumb", "auto"))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo("MED-12004"));
    }

    // --- storage failure → 503 MED-12006 ---

    @Test
    @DisplayName("storage presign failure → 503 MED-12006")
    void storageFailureIs503Med12006() {
        stored(row("display", "webp"));
        when(storage.presignedGetUrl(BUCKET, MEDIA_ID + "/display.webp", TTL))
                .thenThrow(new StorageException("storage down"));

        assertThatThrownBy(() -> queryService.resolve(MEDIA_ID, "display", "auto"))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo("MED-12006");
                    assertThat(ex.getStatus().value()).isEqualTo(503);
                });
        // failures are NOT counted as presigns
        assertThat(presignCounter("display")).isZero();
    }

    // --- exists: no presign, no storage ---

    @Test
    void exists_isARepositoryLookupWithoutStorage() {
        when(mediaRepository.existsById(MEDIA_ID)).thenReturn(true);

        assertThat(queryService.exists(MEDIA_ID)).isTrue();
        verifyNoInteractions(storage);
    }

    // --- TTL pass-through ---

    @Test
    void resolve_usesConfiguredPresignTtl() throws Exception {
        stored(row("display", "webp"));
        queryService.resolve(MEDIA_ID, "display", "auto");

        verify(storage).presignedGetUrl(BUCKET, MEDIA_ID + "/display.webp", TTL);
    }

    // --- H-5 bucket unification: reads presign against media.bucket explicitly ---

    @Test
    @DisplayName("presign goes through the 3-arg bucket-qualified overload with media.bucket")
    void resolve_presignsAgainstTheMediaBucketProperty() throws Exception {
        stored(row("display", "webp"));
        queryService.resolve(MEDIA_ID, "display", "auto");

        // the properties record's bucket value — NOT defaultBucket() — drives the read
        verify(storage).presignedGetUrl(BUCKET, MEDIA_ID + "/display.webp", TTL);
        verify(storage, never()).presignedGetUrl(MEDIA_ID + "/display.webp", TTL);
        verify(storage, never()).defaultBucket();
    }
}
