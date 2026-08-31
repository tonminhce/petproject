package com.shop.searchservice.service.impls;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.searchservice.dto.response.ReindexResponse;
import com.shop.searchservice.service.ReindexService;
import com.shop.searchservice.support.AbstractSearchIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Full reindex over real Elasticsearch with WireMock-backed product-service
 * (spec D5): paged ACTIVE stream → bulk into products-v{n+1} → atomic alias
 * swap → old indices deleted. Covers dryRun (counts only), the 409 in-process
 * lock, and mid-stream source failure (503, alias untouched).
 */
class ReindexIT extends AbstractSearchIntegrationTest {

    static final WireMockServer productServer = new WireMockServer(0);
    static final WireMockServer keycloakServer = new WireMockServer(0);

    static {
        productServer.start();
        keycloakServer.start();
        // ServiceTokenProvider's client_credentials token endpoint (cached after first fetch).
        keycloakServer.stubFor(post(urlMatching("/realms/.*/protocol/openid-connect/token"))
            .willReturn(okJson(
                "{\"access_token\":\"dummy-jwt\",\"expires_in\":3600,\"token_type\":\"Bearer\"}")));
    }

    @DynamicPropertySource
    static void downstreamProps(DynamicPropertyRegistry r) {
        r.add("shop.services.product.url", productServer::baseUrl);
        r.add("shop.services.keycloak.token-url",
            () -> keycloakServer.url("/realms/test/protocol/openid-connect/token"));
    }

    @Autowired
    private ElasticsearchClient client;

    @Autowired
    private ReindexService reindexService;

    @BeforeEach
    void resetDownstreamStubs() {
        productServer.resetAll();
    }

    @Test
    @DisplayName("full reindex streams 2 pages, swaps alias to v(n+1), deletes old indices, docs queryable")
    void fullReindexStreamsPagesSwapsAliasAndDeletesOld() throws Exception {
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        UUID p3 = UUID.randomUUID();
        int beforeMax = maxGeneration();
        stubPage(0, false, 2, snapshotJson(p1, "iPhone 15"), snapshotJson(p2, "Galaxy S25"));
        stubPage(1, true, 2, snapshotJson(p3, "Pixel 10"));

        ReindexResponse response = reindexService.reindex(false);

        String newIndex = "products-v" + (beforeMax + 1);
        assertThat(response.indexed()).isEqualTo(3);
        assertThat(response.indexName()).isEqualTo(newIndex);
        assertThat(response.tookMs()).isGreaterThanOrEqualTo(0);

        client.indices().refresh(r -> r.index("products"));

        // alias points ONLY at the new generation
        assertThat(aliasIndices()).containsExactly(newIndex);
        // every superseded index (including v1 from provisioning) is gone
        assertThat(generationIndices()).containsExactly(newIndex);

        // docs queryable via the alias with the FULL D3 field set
        Map<String, Object> doc = docViaAlias(p1);
        assertThat(doc)
            .containsEntry("id", p1.toString())
            .containsEntry("title", "iPhone 15")
            .containsEntry("description", "Apple smartphone")
            .containsEntry("brandName", "Apple")
            .containsEntry("brandId", "11111111-1111-1111-1111-111111111111")
            .containsEntry("categoryId", "22222222-2222-2222-2222-222222222222")
            .containsEntry("categoryName", "Phones")
            .containsEntry("slug", "iphone-15")
            .containsEntry("imageUrl", "http://img/iphone.png")
            .containsEntry("status", "ACTIVE")
            .containsEntry("updatedAt", "2026-08-31T09:30:00Z");
        assertThat(((Number) doc.get("price")).doubleValue()).isEqualTo(999.00d);
        assertThat(((Number) doc.get("avgRating")).doubleValue()).isEqualTo(4.50d);
        assertThat(((Number) doc.get("ratingCount")).intValue()).isEqualTo(12);

        // source paging used page/size/status exactly (size = the 200 cap)
        productServer.verify(getRequestedFor(
            urlEqualTo("/api/v1/backoffice/products?page=0&size=200&status=ACTIVE")));
        productServer.verify(getRequestedFor(
            urlEqualTo("/api/v1/backoffice/products?page=1&size=200&status=ACTIVE")));
    }

    @Test
    @DisplayName("dryRun counts ACTIVE source rows only — no index creation, no alias change")
    void dryRunCountsOnlyWithoutCreatingIndexOrSwapping() throws Exception {
        int beforeMax = maxGeneration();
        Set<String> aliasBefore = aliasIndices();
        stubPage(0, true, 1, snapshotJson(UUID.randomUUID(), "iPhone 15"),
            snapshotJson(UUID.randomUUID(), "Galaxy S25"));

        ReindexResponse response = reindexService.reindex(true);

        assertThat(response.indexed()).isEqualTo(2);
        assertThat(response.indexName()).isEqualTo(aliasBefore.iterator().next());
        assertThat(maxGeneration()).isEqualTo(beforeMax);
        assertThat(aliasIndices()).isEqualTo(aliasBefore);
    }

    @Test
    @DisplayName("second concurrent call is rejected with 409 SRH-12001 (in-process lock)")
    void concurrentSecondCallReturns409() throws Exception {
        stubPage(0, true, 1, snapshotJson(UUID.randomUUID(), "Slow Phone"));
        // make page 0 slow so the first reindex is provably inside the lock
        productServer.stubFor(get(urlEqualTo("/api/v1/backoffice/products?page=0&size=200&status=ACTIVE"))
            .willReturn(okJson(pageJson(0, 1, true, snapshotJson(UUID.randomUUID(), "Slow Phone")))
                .withFixedDelay(800)));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ReindexResponse> first = executor.submit(() -> reindexService.reindex(false));
            // page-0 request served ⇒ thread A holds the lock
            await().atMost(java.time.Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(productServer.getAllServeEvents()).hasSize(1));

            assertThatThrownBy(() -> reindexService.reindex(false))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.SEARCH_REINDEX_IN_PROGRESS.getCode()));

            assertThat(first.get(20, TimeUnit.SECONDS).indexed()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("source 500 mid-stream → 503 SRH-12002, alias untouched (no swap)")
    void sourceFailureMidStreamKeepsAliasAndReturns503() throws Exception {
        Set<String> aliasBefore = aliasIndices();
        stubPage(0, false, 2, snapshotJson(UUID.randomUUID(), "iPhone 15"));
        productServer.stubFor(get(urlEqualTo("/api/v1/backoffice/products?page=1&size=200&status=ACTIVE"))
            .willReturn(serverError()));

        assertThatThrownBy(() -> reindexService.reindex(false))
            .isInstanceOfSatisfying(BusinessException.class, ex ->
                assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.SEARCH_QUERY_FAILED.getCode()));

        assertThat(aliasIndices()).isEqualTo(aliasBefore);
    }

    @Test
    @DisplayName("orphan index from a failed run is skipped (max+1) and reaped by the next success")
    void orphanFromFailedRunIsSkippedAndReapedByNextSuccess() throws Exception {
        int beforeMax = maxGeneration();
        // failing run leaves an orphan at v(beforeMax+1)
        stubPage(0, false, 2, snapshotJson(UUID.randomUUID(), "iPhone 15"));
        productServer.stubFor(get(urlEqualTo("/api/v1/backoffice/products?page=1&size=200&status=ACTIVE"))
            .willReturn(serverError()));
        assertThatThrownBy(() -> reindexService.reindex(false))
            .isInstanceOf(BusinessException.class);
        assertThat(maxGeneration()).isEqualTo(beforeMax + 1);

        // next successful run skips the orphan number and reaps it post-swap
        productServer.resetAll();
        stubPage(0, true, 1, snapshotJson(UUID.randomUUID(), "iPhone 15"));

        ReindexResponse response = reindexService.reindex(false);

        assertThat(response.indexName()).isEqualTo("products-v" + (beforeMax + 2));
        assertThat(generationIndices()).containsExactly(response.indexName());
        assertThat(aliasIndices()).containsExactly(response.indexName());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static final String PAGE_URI = "/api/v1/backoffice/products?page=%d&size=200&status=ACTIVE";

    private void stubPage(int page, boolean last, int totalPages, String... snapshots) {
        productServer.stubFor(get(urlEqualTo(PAGE_URI.formatted(page)))
            .willReturn(okJson(pageJson(page, totalPages, last, snapshots))));
    }

    private static String snapshotJson(UUID id, String title) {
        return """
            {"id":"%s","title":"%s","slug":"%s","description":"Apple smartphone",
             "priceUnit":999.00,"status":"ACTIVE","imageUrl":"http://img/iphone.png",
             "avgRating":4.50,"ratingCount":12,
             "categoryId":"22222222-2222-2222-2222-222222222222","categoryTitle":"Phones",
             "brandId":"11111111-1111-1111-1111-111111111111","brandName":"Apple",
             "updatedAt":"2026-08-31T09:30:00Z","sku":"IGNORED","quantity":9}
            """.formatted(id, title, title.toLowerCase().replace(" ", "-")).replace("\n", " ");
    }

    private static String pageJson(int page, int totalPages, boolean last, String... snapshots) {
        return """
            {"success":true,"code":"OK","data":{"content":[%s],"page":%d,"size":200,
             "totalElements":%d,"totalPages":%d,"first":%s,"last":%s}}
            """.formatted(String.join(",", snapshots), page, snapshots.length, totalPages,
                page == 0, last).replace("\n", " ");
    }

    private int maxGeneration() throws Exception {
        return generationIndices().stream()
            .mapToInt(name -> Integer.parseInt(name.substring("products-v".length())))
            .max().orElse(0);
    }

    private List<String> generationIndices() throws Exception {
        return client.indices().get(g -> g.index("products-v*")).result().keySet().stream().sorted().toList();
    }

    private Set<String> aliasIndices() throws Exception {
        return client.indices().getAlias(a -> a.name("products")).result().keySet();
    }

    private Map<String, Object> docViaAlias(UUID productId) throws Exception {
        var response = client.get(g -> g.index("products").id(productId.toString()), Map.class);
        return response.found() ? response.source() : null;
    }
}
