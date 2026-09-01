package com.shop.productservice.job;

import com.shop.common.core.constants.ApiPaths;
import com.shop.productservice.constant.ProductStatus;
import com.shop.productservice.entity.OutboxEvent;
import com.shop.productservice.entity.Product;
import com.shop.productservice.repository.OutboxEventRepository;
import com.shop.productservice.repository.ProductRepository;
import com.shop.productservice.support.AbstractIntegrationTest;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.head;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * H-3 reconciliation sweep over REAL HTTP (WireMock-backed media + client-
 * credentials token stub, MediaHeadValidationIT precedent): a dangling product
 * reference (HEAD 404) is cleared and re-published as ProductUpdated; a live
 * reference (HEAD 200) is kept; media being DOWN (fail-closed client) must
 * skip the ENTIRE cycle — no clears, no re-publication (never mass-clear
 * behind an outage). The cron is pinned to a never-fire instant so only the
 * explicit {@code sweep()} invocations run.
 */
class ProductMediaSweepJobIT extends AbstractIntegrationTest {

    static final WireMockServer mediaServer = new WireMockServer(0);
    static final WireMockServer keycloakServer = new WireMockServer(0);

    static {
        mediaServer.start();
        keycloakServer.start();
        // ServiceTokenProvider's client_credentials token endpoint (cached after first fetch).
        keycloakServer.stubFor(post(urlMatching("/realms/.*/protocol/openid-connect/token"))
            .willReturn(okJson(
                "{\"access_token\":\"dummy-jwt\",\"expires_in\":3600,\"token_type\":\"Bearer\"}")));
    }

    @AfterAll
    static void stopStubs() {
        mediaServer.stop();
        keycloakServer.stop();
    }

    @DynamicPropertySource
    static void downstreamProps(DynamicPropertyRegistry r) {
        r.add("shop.media.base-url", mediaServer::baseUrl);
        r.add("shop.media.keycloak.token-url",
            () -> keycloakServer.url("/realms/test/protocol/openid-connect/token"));
        // never fire from the scheduler (Feb 30 does not exist) — only explicit
        // sweep() calls run here
        r.add("shop.product.media-sweep.cron", () -> "0 0 0 30 2 *");
    }

    @Autowired
    private ProductMediaSweepJob sweepJob;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private OutboxEventRepository outboxRepository;

    @Autowired
    private MeterRegistry meterRegistry;

    private static final UUID DEAD_MEDIA = UUID.fromString("dddddddd-0000-0000-0000-00000000000d");
    private static final UUID LIVE_MEDIA = UUID.fromString("eeeeeeee-0000-0000-0000-00000000000e");

    @BeforeEach
    void resetStubsAndState() {
        mediaServer.resetAll();
        productRepository.deleteAllInBatch();
        outboxRepository.deleteAllInBatch();
    }

    private Product productWithMedia(UUID mediaId, String title) {
        Product p = Product.builder()
            .title(title)
            .slug(title.toLowerCase().replace(' ', '-') + "-" + UUID.randomUUID())
            .sku("SWP-" + UUID.randomUUID().toString().substring(0, 8))
            .priceUnit(new BigDecimal("9.00"))
            .quantity(1)
            .status(ProductStatus.ACTIVE)
            .imageUrl("http://legacy.example/" + title.replace(' ', '-') + ".png")
            .mediaId(mediaId)
            .build();
        return productRepository.save(p);
    }

    private void stubHead(UUID mediaId, int status) {
        mediaServer.stubFor(head(urlEqualTo(ApiPaths.MEDIAS + "/" + mediaId))
            .willReturn(aResponse().withStatus(status)));
    }

    private List<OutboxEvent> productUpdatedRows() {
        return outboxRepository.findAll().stream()
            .filter(r -> "ProductUpdated".equals(r.getEventType()))
            .toList();
    }

    /** Meter counters accumulate across tests sharing this cached context — assert on deltas. */
    private double checkedDelta(double before) {
        return meterRegistry.counter("product_media_sweep_checked_total").count() - before;
    }

    private double clearedDelta(double before) {
        return meterRegistry.counter("product_media_sweep_cleared_total").count() - before;
    }

    @Test
    @DisplayName("HEAD 404 → dangling reference cleared + ProductUpdated published; HEAD 200 → kept")
    void sweep_clearsDangling_keepsLive() {
        Product dangling1 = productWithMedia(DEAD_MEDIA, "Dangling One");
        Product dangling2 = productWithMedia(DEAD_MEDIA, "Dangling Two");
        Product live = productWithMedia(LIVE_MEDIA, "Live Product");
        stubHead(DEAD_MEDIA, 404);
        stubHead(LIVE_MEDIA, 200);
        double checkedBefore = meterRegistry.counter("product_media_sweep_checked_total").count();
        double clearedBefore = meterRegistry.counter("product_media_sweep_cleared_total").count();

        sweepJob.sweep();

        assertThat(productRepository.findById(dangling1.getId()).orElseThrow().getMediaId())
            .as("dangling reference must be cleared").isNull();
        assertThat(productRepository.findById(dangling2.getId()).orElseThrow().getMediaId())
            .as("second row sharing the dead media must be cleared too").isNull();
        assertThat(productRepository.findById(live.getId()).orElseThrow().getMediaId())
            .as("live reference must be kept").isEqualTo(LIVE_MEDIA);

        List<OutboxEvent> rows = productUpdatedRows();
        assertThat(rows).as("one ProductUpdated per cleared product").hasSize(2);
        assertThat(rows).extracting(OutboxEvent::getAggregateId)
            .containsExactlyInAnyOrder(dangling1.getId(), dangling2.getId());

        assertThat(checkedDelta(checkedBefore)).isEqualTo(3);
        assertThat(clearedDelta(clearedBefore))
            .as("cleared meter counts ROWS: call 1 repaired both dangling rows, row-2 replay repaired 0")
            .isEqualTo(2);

        mediaServer.verify(com.github.tomakehurst.wiremock.client.WireMock
            .headRequestedFor(urlEqualTo(ApiPaths.MEDIAS + "/" + DEAD_MEDIA)));
        mediaServer.verify(com.github.tomakehurst.wiremock.client.WireMock
            .headRequestedFor(urlEqualTo(ApiPaths.MEDIAS + "/" + LIVE_MEDIA)));
    }

    @Test
    @DisplayName("media down (connection reset) → ENTIRE cycle skipped: no clears, no re-publication")
    void sweep_outage_skipsCycleWithoutWrites() {
        Product p = productWithMedia(DEAD_MEDIA, "Outage Product");
        mediaServer.stubFor(head(urlEqualTo(ApiPaths.MEDIAS + "/" + DEAD_MEDIA))
            .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
        double checkedBefore = meterRegistry.counter("product_media_sweep_checked_total").count();
        double clearedBefore = meterRegistry.counter("product_media_sweep_cleared_total").count();

        sweepJob.sweep();

        assertThat(productRepository.findById(p.getId()).orElseThrow().getMediaId())
            .as("outage must never look like 'media gone' — reference untouched").isEqualTo(DEAD_MEDIA);
        assertThat(productUpdatedRows())
            .as("no re-publication on a skipped cycle").isEmpty();
        assertThat(checkedDelta(checkedBefore))
            .as("only the failing row was checked before the abort").isEqualTo(1);
        assertThat(clearedDelta(clearedBefore)).isZero();
    }
}
