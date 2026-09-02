package com.shop.mediaservice.job;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.shop.common.core.constants.ApiPaths;
import com.shop.common.storage.service.ObjectStorageService;
import com.shop.mediaservice.config.MediaProperties;
import com.shop.mediaservice.entity.Media;
import com.shop.mediaservice.repository.MediaRepository;
import com.shop.mediaservice.service.MediaLifecycleService;
import com.shop.mediaservice.service.MediaUploadService;
import com.shop.mediaservice.support.AbstractMediaIntegrationTest;
import com.shop.mediaservice.support.TestImages;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import software.amazon.awssdk.services.s3.S3Client;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The purge GRACE BOUNDARY + H-4 REFERENCE GATE proven against the real stack:
 * real Postgres (the {@code deleted_at <= now − grace} SQL itself), real MinIO
 * objects, and the REAL {@code ProductMediaReferenceChecker} chain — the
 * checker's product call goes over live HTTP to WireMock (product-service
 * internal reference-count endpoint + client-credentials token stub), exactly
 * like production.
 *
 * <p>Contract: count 0 → purge runs (objects + rows hard-deleted); count &gt; 0
 * → skip + WARN (objects/rows survive, retried next cycle); product UNREACHABLE
 * → fail-safe skip + WARN for THAT media, and the cycle continues with the
 * next candidate. A row deleted exactly at the grace horizon is purgeable, a
 * row inside the window survives.</p>
 */
class MediaPurgeIT extends AbstractMediaIntegrationTest {

    static final WireMockServer productServer = new WireMockServer(0);
    static final WireMockServer keycloakServer = new WireMockServer(0);

    @BeforeAll
    static void startStubs() {
        productServer.start();
        keycloakServer.start();
        // media's ServiceTokenProvider client_credentials endpoint (cached after first fetch)
        keycloakServer.stubFor(post(urlMatching("/realms/.*/protocol/openid-connect/token"))
            .willReturn(okJson(
                "{\"access_token\":\"dummy-jwt\",\"expires_in\":3600,\"token_type\":\"Bearer\"}")));
    }

    @AfterAll
    static void stopStubs() {
        productServer.stop();
        keycloakServer.stop();
    }

    @DynamicPropertySource
    static void downstreamProps(DynamicPropertyRegistry r) {
        r.add("shop.product.base-url", productServer::baseUrl);
        r.add("shop.product.keycloak.token-url",
            () -> keycloakServer.url("/realms/test/protocol/openid-connect/token"));
    }

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
        productServer.resetAll();
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

    private void stubReferenceCount(String mediaId, long count) {
        productServer.stubFor(get(urlEqualTo(ApiPaths.INTERNAL_PRODUCT_MEDIA_REFERENCES + "/" + mediaId))
            .willReturn(okJson(
                "{\"success\":true,\"code\":\"OK\",\"data\":{\"mediaId\":\"" + mediaId
                    + "\",\"referenceCount\":" + count + "}}")));
    }

    @Test
    @DisplayName("H-4 gate: referenceCount=0 → purge runs; referenceCount>0 → skip + WARN, objects/rows survive")
    void referenceGate_zeroPurges_positiveSkips() throws Exception {
        String idA = uploadAndSoftDelete(TestImages.jpeg(640, 480), "image/jpeg", "jpeg");
        String idB = uploadAndSoftDelete(TestImages.png(640, 480), "image/png", "png");
        List<String> keysA = mediaRepository.findObjectKeysByMediaId(UUID.fromString(idA));
        List<String> keysB = mediaRepository.findObjectKeysByMediaId(UUID.fromString(idB));

        // both EXACTLY at the grace horizon (purgeable if the gate is green)
        stampAtGraceHorizon(idA);
        stampAtGraceHorizon(idB);
        stubReferenceCount(idA, 0);
        stubReferenceCount(idB, 2);

        purgeJob.purge();

        // A: zero live references → objects hard-deleted, rows gone
        keysA.forEach(key -> assertThat(storage.exists(key)).as("A object %s purged", key).isFalse());
        assertThat(mediaRepository.existsIncludingDeleted(UUID.fromString(idA))).isFalse();
        // B: still referenced → everything survives for the next cycle
        keysB.forEach(key -> assertThat(storage.exists(key)).as("B object %s kept", key).isTrue());
        assertThat(mediaRepository.existsIncludingDeleted(UUID.fromString(idB))).isTrue();
        // the gate consulted product for BOTH candidates over real HTTP
        productServer.verify(com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor(
            urlEqualTo(ApiPaths.INTERNAL_PRODUCT_MEDIA_REFERENCES + "/" + idA)));
        productServer.verify(com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor(
            urlEqualTo(ApiPaths.INTERNAL_PRODUCT_MEDIA_REFERENCES + "/" + idB)));
    }

    @Test
    @DisplayName("product down for one candidate → fail-safe skip + cycle CONTINUES with the next")
    void productDown_failSafeSkip_cycleContinues() throws Exception {
        String idA = uploadAndSoftDelete(TestImages.jpeg(320, 320), "image/jpeg", "jpeg");
        String idB = uploadAndSoftDelete(TestImages.jpeg(300, 300), "image/jpeg", "jpeg");
        List<String> keysA = mediaRepository.findObjectKeysByMediaId(UUID.fromString(idA));
        List<String> keysB = mediaRepository.findObjectKeysByMediaId(UUID.fromString(idB));

        stampAtGraceHorizon(idA);
        stampAtGraceHorizon(idB);
        // A: product outage (connection reset) — cannot PROVE unreferenced
        productServer.stubFor(get(urlEqualTo(ApiPaths.INTERNAL_PRODUCT_MEDIA_REFERENCES + "/" + idA))
            .willReturn(aResponse().withFault(com.github.tomakehurst.wiremock.http.Fault.CONNECTION_RESET_BY_PEER)));
        stubReferenceCount(idB, 0);

        purgeJob.purge();

        // A: fail-safe keep — objects/rows survive, retried next cycle
        keysA.forEach(key -> assertThat(storage.exists(key)).as("A object %s kept", key).isTrue());
        assertThat(mediaRepository.existsIncludingDeleted(UUID.fromString(idA))).isTrue();
        // B: the cycle completed — next candidate purged normally
        keysB.forEach(key -> assertThat(storage.exists(key)).as("B object %s purged", key).isFalse());
        assertThat(mediaRepository.existsIncludingDeleted(UUID.fromString(idB))).isFalse();
    }

    @Test
    @DisplayName("exactly-at-grace purges (objects + rows); inside-grace skips until the next cycle")
    void graceBoundary_exactlyAtGracePurges_insideGraceSkips() throws Exception {
        byte[] sourceA = TestImages.jpeg(640, 480);
        byte[] sourceB = TestImages.png(640, 480);
        String idA = uploadAndSoftDelete(sourceA, "image/jpeg", "jpeg");
        String idB = uploadAndSoftDelete(sourceB, "image/png", "png");

        List<String> keysA = mediaRepository.findObjectKeysByMediaId(UUID.fromString(idA));
        List<String> keysB = mediaRepository.findObjectKeysByMediaId(UUID.fromString(idB));
        assertThat(keysA).isNotEmpty();
        assertThat(keysB).isNotEmpty();

        stubReferenceCount(idA, 0);
        stubReferenceCount(idB, 0);
        Duration grace = properties.purgeGrace();
        // A: deleted 60s AGO relative to the horizon → inside the grace, skips
        jdbcTemplate.update("UPDATE medias SET deleted_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minus(grace).plusSeconds(60)), UUID.fromString(idA));
        // B: deleted EXACTLY at the horizon when stamped (the job's cutoff is
        // computed later, so B is at-or-past it — the <= boundary purges it)
        jdbcTemplate.update("UPDATE medias SET deleted_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minus(grace)), UUID.fromString(idB));

        purgeJob.purge();

        // B: objects hard-deleted, media row gone, variant rows cascaded
        keysB.forEach(key -> assertThat(storage.exists(key)).as("B object %s purged", key).isFalse());
        assertThat(mediaRepository.existsIncludingDeleted(UUID.fromString(idB))).isFalse();
        Integer variantRowsB = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM media_variants WHERE media_id = ?::uuid",
                Integer.class, idB);
        assertThat(variantRowsB).isZero();

        // A: inside grace — objects remain, rows remain, purgeable next cycle
        keysA.forEach(key -> assertThat(storage.exists(key)).as("A object %s kept", key).isTrue());
        assertThat(mediaRepository.existsIncludingDeleted(UUID.fromString(idA))).isTrue();
        Integer variantRowsA = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM media_variants WHERE media_id = ?::uuid",
                Integer.class, idA);
        assertThat(variantRowsA).isPositive();
    }

    @Test
    @DisplayName("purge is idempotent — a row already purged is simply absent from the next cycle")
    void purge_isIdempotent() throws Exception {
        String idB = uploadAndSoftDelete(TestImages.jpeg(320, 320), "image/jpeg", "jpeg");
        stampAtGraceHorizon(idB);
        stubReferenceCount(idB, 0);

        purgeJob.purge();
        List<UUID> candidatesAfterFirst = mediaRepository.findPurgeableIds(Instant.now());
        assertThat(candidatesAfterFirst).isEmpty();

        purgeJob.purge();
    }

    private void stampAtGraceHorizon(String mediaId) {
        jdbcTemplate.update("UPDATE medias SET deleted_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minus(properties.purgeGrace())),
                UUID.fromString(mediaId));
    }

    private String uploadAndSoftDelete(byte[] bytes, String contentType, String format) throws Exception {
        var file = new org.springframework.mock.web.MockMultipartFile(
                "file", "img." + format, contentType, bytes);
        Media media = mediaRepository.findById(uploadService.upload(file).id()).orElseThrow();
        lifecycleService.softDelete(media.getId());
        return media.getId().toString();
    }
}
