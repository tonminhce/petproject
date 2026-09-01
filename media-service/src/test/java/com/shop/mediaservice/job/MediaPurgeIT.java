package com.shop.mediaservice.job;

import com.shop.common.storage.service.ObjectStorageService;
import com.shop.mediaservice.config.MediaProperties;
import com.shop.mediaservice.entity.Media;
import com.shop.mediaservice.repository.MediaRepository;
import com.shop.mediaservice.service.MediaLifecycleService;
import com.shop.mediaservice.service.MediaUploadService;
import com.shop.mediaservice.support.AbstractMediaIntegrationTest;
import com.shop.mediaservice.support.TestImages;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import software.amazon.awssdk.services.s3.S3Client;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The purge GRACE BOUNDARY proven against the real database (the unit test
 * stubs the repo, so only here is the {@code deleted_at <= now − grace}
 * SQL itself exercised): a row deleted exactly at the grace horizon is
 * purgeable — objects hard-deleted, rows (media + cascaded variants) gone —
 * while a row inside the grace window survives untouched, ready for a later
 * cycle.
 */
class MediaPurgeIT extends AbstractMediaIntegrationTest {

    @Autowired
    private MediaUploadService uploadService;

    @Autowired
    private MediaLifecycleService lifecycleService;

    @Autowired
    private MediaPurgeJob purgeJob;

    @Autowired
    private MediaRepository mediaRepository;

    @Autowired
    private ObjectStorageService storage;

    @Autowired
    private S3Client s3Client;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MediaProperties properties;

    @BeforeEach
    void wipe() {
        // native DELETE — @SQLRestriction hides soft-deleted rows from bulk
        // JPQL too, so deleteAllInBatch would leave them behind
        jdbcTemplate.update("DELETE FROM media_variants");
        jdbcTemplate.update("DELETE FROM medias");
        while (true) {
            List<String> keys = new ArrayList<>();
            s3Client.listObjectsV2Paginator(b -> b.bucket(BUCKET))
                    .contents()
                    .forEach(summary -> keys.add(summary.key()));
            if (keys.isEmpty()) {
                break;
            }
            keys.forEach(key -> s3Client.deleteObject(b -> b.bucket(BUCKET).key(key)));
        }
    }

    @Test
    @DisplayName("exactly-at-grace purges (objects + rows); inside-grace skips until the next cycle")
    void graceBoundary_exactlyAtGracePurges_insideGraceSkips() throws Exception {
        byte[] sourceA = TestImages.jpeg(640, 480);
        byte[] sourceB = TestImages.png(640, 480);
        String idA = uploadAndSoftDelete(sourceA, "image/jpeg", "jpeg");
        String idB = uploadAndSoftDelete(sourceB, "image/png", "png");

        List<String> keysA = mediaRepository.findObjectKeysByMediaId(java.util.UUID.fromString(idA));
        List<String> keysB = mediaRepository.findObjectKeysByMediaId(java.util.UUID.fromString(idB));
        assertThat(keysA).isNotEmpty();
        assertThat(keysB).isNotEmpty();

        Duration grace = properties.purgeGrace();
        // A: deleted 60s AGO relative to the horizon → inside the grace, skips
        jdbcTemplate.update("UPDATE medias SET deleted_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minus(grace).plusSeconds(60)), java.util.UUID.fromString(idA));
        // B: deleted EXACTLY at the horizon when stamped (the job's cutoff is
        // computed later, so B is at-or-past it — the <= boundary purges it)
        jdbcTemplate.update("UPDATE medias SET deleted_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minus(grace)), java.util.UUID.fromString(idB));

        purgeJob.purge();

        // B: objects hard-deleted, media row gone, variant rows cascaded
        keysB.forEach(key -> assertThat(storage.exists(key)).as("B object %s purged", key).isFalse());
        assertThat(mediaRepository.existsIncludingDeleted(java.util.UUID.fromString(idB))).isFalse();
        Integer variantRowsB = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM media_variants WHERE media_id = ?::uuid",
                Integer.class, idB);
        assertThat(variantRowsB).isZero();

        // A: inside grace — objects remain, rows remain, purgeable next cycle
        keysA.forEach(key -> assertThat(storage.exists(key)).as("A object %s kept", key).isTrue());
        assertThat(mediaRepository.existsIncludingDeleted(java.util.UUID.fromString(idA))).isTrue();
        Integer variantRowsA = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM media_variants WHERE media_id = ?::uuid",
                Integer.class, idA);
        assertThat(variantRowsA).isPositive();
    }

    @Test
    @DisplayName("purge is idempotent — a row already purged is simply absent from the next cycle")
    void purge_isIdempotent() throws Exception {
        String idB = uploadAndSoftDelete(TestImages.jpeg(320, 320), "image/jpeg", "jpeg");
        jdbcTemplate.update("UPDATE medias SET deleted_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minus(properties.purgeGrace())),
                java.util.UUID.fromString(idB));

        purgeJob.purge();
        List<java.util.UUID> candidatesAfterFirst = mediaRepository.findPurgeableIds(Instant.now());
        assertThat(candidatesAfterFirst).isEmpty();

        purgeJob.purge();
    }

    private String uploadAndSoftDelete(byte[] bytes, String contentType, String format) throws Exception {
        var file = new org.springframework.mock.web.MockMultipartFile(
                "file", "img." + format, contentType, bytes);
        Media media = mediaRepository.findById(uploadService.upload(file).id()).orElseThrow();
        lifecycleService.softDelete(media.getId());
        return media.getId().toString();
    }
}
