package com.shop.mediaservice.service.impls;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.GpsDirectory;
import com.shop.common.core.exception.BusinessException;
import com.shop.common.storage.exception.StorageException;
import com.shop.common.storage.service.ObjectStorageService;
import com.shop.common.storage.service.StorageObject;
import com.shop.mediaservice.config.MediaProperties;
import com.shop.mediaservice.dto.response.MediaResponse;
import com.shop.mediaservice.dto.response.MediaVariantResponse;
import com.shop.mediaservice.entity.MediaVariant;
import com.shop.mediaservice.metrics.MediaMetrics;
import com.shop.mediaservice.repository.MediaRepository;
import com.shop.mediaservice.service.MediaUploadService;
import com.shop.mediaservice.support.AbstractMediaIntegrationTest;
import com.shop.mediaservice.support.TestImages;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.services.s3.S3Client;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URL;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * D1/D2 upload pipeline against the real MinIO singleton: per-format
 * six-variant renders within caps, oversize 413 / wrong-mime 415 / corrupt
 * 400 mapping, SHA-256 dedup before any object write, EXIF/GPS strip proof,
 * and orphan-object cleanup on an injected mid-write storage failure.
 */
class UploadIT extends AbstractMediaIntegrationTest {

    @Autowired
    private MediaUploadService uploadService;

    @Autowired
    private MediaRepository mediaRepository;

    @Autowired
    private ObjectStorageService storage;

    @Autowired
    private S3Client s3Client;

    @Autowired
    private MediaProperties properties;

    @Autowired
    private MeterRegistry meterRegistry;

    private double createdCount;
    private double duplicateCount;
    private double rejectedCount;

    @BeforeEach
    void resetStorageAndMetrics() {
        mediaRepository.deleteAllInBatch();
        // wipe every object so per-test object-count asserts are exact
        List<String> keys;
        do {
            keys = listAllKeys();
            keys.forEach(key -> storage.delete(BUCKET, key));
        } while (!keys.isEmpty());

        createdCount = counter(MediaMetrics.OUTCOME_CREATED);
        duplicateCount = counter(MediaMetrics.OUTCOME_DUPLICATE);
        rejectedCount = counter(MediaMetrics.OUTCOME_REJECTED);
        FlakyStorage.writtenKeys.clear();
        FlakyStorage.failAfter = Integer.MAX_VALUE;
        FlakyStorage.writes = 0;
    }

    // --- happy path: one test per allowed format ---

    @ParameterizedTest(name = "upload {0} → 6 variants, widths capped, WebP present, objects stored")
    @CsvSource({
            "jpeg, image/jpeg, jpg",
            "png,  image/png,  png",
            "webp, image/webp, webp"})
    void uploadEachFormat_storesSixVariantsWithinCaps(String format, String contentType, String ext) throws Exception {
        byte[] source = TestImages.encode(TestImages.image(2400, 1200), format);

        MediaResponse response = uploadService.upload(multipart(contentType, source));

        assertThat(response.id()).isNotNull();
        assertThat(response.duplicate()).isFalse();
        assertThat(response.sha256()).isEqualTo(sha256Hex(source));
        assertThat(response.contentType()).isEqualTo(contentType);
        assertThat(response.sizeBytes()).isEqualTo(source.length);
        assertThat(response.canonicalPath()).isEqualTo("/api/v1/medias/" + response.id());

        // D2 variant rows: original/display/thumb × original-format + webp.
        // A WebP SOURCE collapses the two formats — 3 distinct rows then.
        int expectedFormats = "webp".equals(format) ? 1 : 2;
        assertThat(response.variants()).hasSize(3 * expectedFormats);
        assertThat(response.variants()).extracting(MediaVariantResponse::variant)
                .containsOnly("original", "display", "thumb");
        assertThat(response.variants()).filteredOn(v -> "original".equals(v.variant()))
                .extracting(MediaVariantResponse::format)
                .containsExactlyInAnyOrderElementsOf(expectedFormats == 1
                        ? List.of("webp") : List.of(format, "webp"));

        int displayWidth = 0;
        int thumbWidth = 0;
        for (MediaVariantResponse variant : response.variants()) {
            switch (variant.variant()) {
                case "original" -> assertThat(variant.width()).isEqualTo(2400);
                case "display" -> displayWidth = Math.max(displayWidth, variant.width());
                case "thumb" -> thumbWidth = Math.max(thumbWidth, variant.width());
                default -> throw new IllegalStateException(variant.variant());
            }
            assertThat(variant.bytes()).isPositive();
            // D1 key contract: {mediaId}/{variant}.{ext}
            String expectedExt = MediaFormats.WEBP.equals(variant.format()) ? "webp" : ext;
            assertThat(variant.objectKey())
                    .isEqualTo(response.id() + "/" + variant.variant() + "." + expectedExt);
        }
        assertThat(displayWidth).isEqualTo(properties.displayWidth());
        assertThat(thumbWidth).isEqualTo(properties.thumbWidth());

        // every object exists in MinIO, has the advertised byte size, and
        // decodes as the real format at the advertised width
        for (MediaVariantResponse variant : response.variants()) {
            StorageObject object = storage.download(BUCKET, variant.objectKey());
            assertThat(object.contentLength()).isEqualTo(variant.bytes());
            byte[] stored;
            try (InputStream in = object.content()) {
                stored = in.readAllBytes();
            }
            var decoded = ImageIO.read(new ByteArrayInputStream(stored));
            assertThat(decoded).as("stored %s bytes decode", variant.objectKey()).isNotNull();
            assertThat(decoded.getWidth()).isEqualTo(variant.width());
        }

        // media row + variant rows committed
        var saved = mediaRepository.findById(response.id()).orElseThrow();
        assertThat(saved.getVariants()).hasSize(3 * expectedFormats);
        assertThat(saved.getVariants()).extracting(MediaVariant::getObjectKey)
                .doesNotContainNull();

        assertThat(counter(MediaMetrics.OUTCOME_CREATED) - createdCount).isEqualTo(1.0);
        assertThat(counter(MediaMetrics.OUTCOME_REJECTED) - rejectedCount).isZero();
    }

    @Test
    @DisplayName("stored original is a re-encode — bytes are never the raw upload")
    void storedOriginalIsNeverRawBytes() throws Exception {
        byte[] source = TestImages.jpeg(800, 600);
        MediaResponse response = uploadService.upload(multipart("image/jpeg", source));

        String originalKey = originalKey(response, "jpeg");
        StorageObject object = storage.download(BUCKET, originalKey);
        byte[] stored;
        try (InputStream in = object.content()) {
            stored = in.readAllBytes();
        }
        assertThat(stored).isNotEqualTo(source);
    }

    // --- validation failures ---

    @Test
    @DisplayName("oversize upload → 413 MED-12002, nothing stored")
    void oversizeUpload_returns413Med12002() {
        long oversize = properties.maxUploadBytes() + 512 * 1024; // 10.5MB, passes magic+type first
        byte[] bytes = TestImages.jpegMagicOfSize(oversize);

        assertThatThrownBy(() -> uploadService.upload(multipart("image/jpeg", bytes)))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo("MED-12002");
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
                });

        assertThat(mediaRepository.count()).isZero();
        assertThat(counter(MediaMetrics.OUTCOME_REJECTED) - rejectedCount).isEqualTo(1.0);
    }

    @Test
    @DisplayName("content type outside the allowlist → 415 MED-12003")
    void wrongMimeType_returns415Med12003() {
        assertThatThrownBy(() -> uploadService.upload(multipart("image/gif", TestImages.nonImageBytes())))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo("MED-12003");
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
                });
        assertThatThrownBy(() -> uploadService.upload(multipart("text/plain", TestImages.nonImageBytes())))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo("MED-12003"));

        assertThat(mediaRepository.count()).isZero();
    }

    @Test
    @DisplayName("corrupt magic bytes → 400 MED-12001 (junk, and lying declared type)")
    void corruptMagicBytes_returns400Med12001() {
        // junk that matches no allowed magic
        assertThatThrownBy(() -> uploadService.upload(multipart("image/jpeg", TestImages.nonImageBytes())))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo("MED-12001");
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                });
        // valid PNG magic but declared as jpeg — a lying upload is corrupt input
        assertThatThrownBy(() -> uploadService.upload(multipart("image/jpeg", TestImages.png(64, 64))))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo("MED-12001"));

        assertThat(mediaRepository.count()).isZero();
        assertThat(counter(MediaMetrics.OUTCOME_REJECTED) - rejectedCount).isEqualTo(2.0);
    }

    // --- dedup ---

    @Test
    @DisplayName("same bytes uploaded twice → second returns existing id, duplicate:true, no new objects")
    void duplicateUploadReturnsExistingWithoutNewObjects() throws Exception {
        byte[] source = TestImages.jpeg(1024, 768);

        MediaResponse first = uploadService.upload(multipart("image/jpeg", source));
        int objectsAfterFirst = listAllKeys().size();

        MockMultipartFile renamedCopy = new MockMultipartFile(
                "file", "renamed-copy.jpg", "image/jpeg", source);
        MediaResponse second = uploadService.upload(renamedCopy);

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.duplicate()).isTrue();
        assertThat(second.sha256()).isEqualTo(first.sha256());
        assertThat(listAllKeys().size()).isEqualTo(objectsAfterFirst); // zero new objects
        assertThat(mediaRepository.count()).isEqualTo(1);
        assertThat(counter(MediaMetrics.OUTCOME_DUPLICATE) - duplicateCount).isEqualTo(1.0);
        assertThat(counter(MediaMetrics.OUTCOME_CREATED) - createdCount).isEqualTo(1.0);
    }

    // --- M1 EXIF/GPS strip proof ---

    @Test
    @DisplayName("JPEG with EXIF+GPS: stored original decodes with NO metadata directories")
    void exifAndGpsAreStrippedFromStoredOriginal() throws Exception {
        byte[] crafted = TestImages.jpegWithExifAndGps(1600, 900);
        // sanity: the fixture really carries EXIF + GPS before the pipeline
        Metadata before = ImageMetadataReader.readMetadata(new ByteArrayInputStream(crafted));
        assertThat(before.getDirectoriesOfType(ExifIFD0Directory.class)).isNotEmpty();
        assertThat(before.getDirectoriesOfType(GpsDirectory.class)).isNotEmpty();

        MediaResponse response = uploadService.upload(multipart("image/jpeg", crafted));

        try (InputStream fetched = storage.download(BUCKET, originalKey(response, "jpeg")).content()) {
            byte[] stored = fetched.readAllBytes();
            Metadata after = ImageMetadataReader.readMetadata(new ByteArrayInputStream(stored));
            assertThat(after.getDirectoriesOfType(ExifIFD0Directory.class)).isEmpty();
            assertThat(after.getDirectoriesOfType(GpsDirectory.class)).isEmpty();
            // still a decodable full-resolution image
            var image = ImageIO.read(new ByteArrayInputStream(stored));
            assertThat(image).isNotNull();
            assertThat(image.getWidth()).isEqualTo(1600);
        }
    }

    // --- orphan cleanup on injected storage failure ---

    @Test
    @DisplayName("storage fails on the 2nd object write → 503 MED-12006, orphans purged, no media row")
    void storageFailureMidUploadPurgesOrphansAndPersistsNothing() {
        FlakyStorage.failAfter = 1; // original written, first variant write fails
        byte[] source = TestImages.jpeg(1200, 800);

        assertThatThrownBy(() -> uploadService.upload(multipart("image/jpeg", source)))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo("MED-12006");
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                });

        // every object the pipeline managed to write was deleted again
        assertThat(FlakyStorage.writtenKeys).isNotEmpty();
        for (String key : FlakyStorage.writtenKeys) {
            assertThat(storage.exists(key)).as("orphan %s purged", key).isFalse();
        }
        assertThat(listAllKeys()).isEmpty();
        assertThat(mediaRepository.count()).isZero();
        assertThat(counter(MediaMetrics.OUTCOME_REJECTED) - rejectedCount).isEqualTo(1.0);
    }

    // --- helpers ---

    private MultipartFile multipart(String contentType, byte[] bytes) {
        return new MockMultipartFile("file", "upload", contentType, bytes);
    }

    private static String originalKey(MediaResponse response, String format) {
        return response.variants().stream()
                .filter(v -> "original".equals(v.variant()) && format.equals(v.format()))
                .findFirst().orElseThrow()
                .objectKey();
    }

    private static String sha256Hex(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }

    private double counter(String outcome) {
        var counter = meterRegistry.find("media_uploads_total").tag("outcome", outcome).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private List<String> listAllKeys() {
        List<String> keys = new ArrayList<>();
        s3Client.listObjectsV2Paginator(b -> b.bucket(BUCKET))
                .contents()
                .forEach(summary -> keys.add(summary.key()));
        return keys;
    }

    /** Test-only storage wrapper: pass-through until {@code failAfter} writes, then outage. */
    @TestConfiguration(proxyBeanMethods = false)
    static class FlakyStorageConfig {

        @Bean
        @Primary
        ObjectStorageService flakyObjectStorage(ObjectStorageService real) {
            return new FlakyStorage(real);
        }
    }

    static final class FlakyStorage implements ObjectStorageService {

        static final List<String> writtenKeys = Collections.synchronizedList(new ArrayList<>());
        static volatile int failAfter = Integer.MAX_VALUE;
        static volatile int writes = 0;

        private final ObjectStorageService delegate;

        FlakyStorage(ObjectStorageService delegate) {
            this.delegate = delegate;
        }

        @Override
        public String defaultBucket() {
            return delegate.defaultBucket();
        }

        @Override
        public void ensureBucketExists(String bucket) {
            delegate.ensureBucketExists(bucket);
        }

        @Override
        public String upload(String key, byte[] content, String contentType) {
            return delegate.upload(key, content, contentType);
        }

        @Override
        public String upload(String bucket, String key, InputStream content, long contentLength, String contentType) {
            if (++writes > failAfter) {
                throw new StorageException("Injected storage outage (test)");
            }
            String stored = delegate.upload(bucket, key, content, contentLength, contentType);
            writtenKeys.add(key);
            return stored;
        }

        @Override
        public StorageObject download(String key) {
            return delegate.download(key);
        }

        @Override
        public StorageObject download(String bucket, String key) {
            return delegate.download(bucket, key);
        }

        @Override
        public boolean exists(String key) {
            return delegate.exists(key);
        }

        @Override
        public void delete(String key) {
            delegate.delete(key);
        }

        @Override
        public void delete(String bucket, String key) {
            delegate.delete(bucket, key);
        }

        @Override
        public URL presignedGetUrl(String key, Duration ttl) {
            return delegate.presignedGetUrl(key, ttl);
        }

        @Override
        public URL presignedPutUrl(String key, String contentType, Duration ttl) {
            return delegate.presignedPutUrl(key, contentType, ttl);
        }
    }
}
