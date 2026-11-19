package com.shop.searchservice.service.impls;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.shop.searchservice.dto.request.SearchRequest;
import com.shop.searchservice.dto.response.ProductSearchResponse;
import com.shop.searchservice.kafka.ProductLifecycleEvent;
import com.shop.searchservice.service.ProductSearchService;
import com.shop.searchservice.service.SearchQueryService;
import com.shop.searchservice.support.AbstractSearchIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Query behavior over the REAL Elasticsearch container (spec D5): relevance,
 * filters, every sort incl. the F3 branching rules, pagination/cap, browse,
 * the P2-6 auth posture and the search_queries_total meter. Docs are seeded
 * through {@link ProductSearchService} — the same ingestion path the consumer
 * uses — so query assertions always run against production-shaped documents.
 */
@AutoConfigureMockMvc
class SearchQueryIT extends AbstractSearchIntegrationTest {

    private static final UUID P1 = UUID.fromString("a1000000-0000-0000-0000-000000000001");
    private static final UUID P2 = UUID.fromString("a1000000-0000-0000-0000-000000000002");
    private static final UUID P3 = UUID.fromString("a1000000-0000-0000-0000-000000000003");
    private static final UUID P4 = UUID.fromString("a1000000-0000-0000-0000-000000000004");
    private static final UUID P5 = UUID.fromString("a1000000-0000-0000-0000-000000000005");

    private static final UUID BRAND_A = UUID.fromString("b1000000-0000-0000-0000-00000000000a");
    private static final UUID BRAND_B = UUID.fromString("b1000000-0000-0000-0000-00000000000b");
    private static final UUID BRAND_C = UUID.fromString("b1000000-0000-0000-0000-00000000000c");
    private static final UUID CAT_X = UUID.fromString("c1000000-0000-0000-0000-00000000000a");
    private static final UUID CAT_Y = UUID.fromString("c1000000-0000-0000-0000-00000000000b");
    private static final UUID CAT_Z = UUID.fromString("c1000000-0000-0000-0000-00000000000c");

    private static final String T1 = "2026-08-31T10:01:00Z";
    private static final String T2 = "2026-08-31T10:02:00Z";
    private static final String T3 = "2026-08-31T10:03:00Z";
    private static final String T4 = "2026-08-31T10:04:00Z";
    private static final String T5 = "2026-08-31T10:05:00Z";

    @Autowired ProductSearchService productSearchService;
    @Autowired SearchQueryService searchQueryService;
    @Autowired ElasticsearchClient elasticsearchClient;
    @Autowired MockMvc mockMvc;
    @Autowired MeterRegistry meterRegistry;

    @BeforeEach
    void seedDocs() throws Exception {
        elasticsearchClient.deleteByQuery(d -> d.index("products").query(q -> q.matchAll(m -> m)));
        seed(P1, "Laser Mouse", "Ergonomic office mouse for daily work",
            BRAND_A, "LogiTech", CAT_X, "Peripherals", "10.00", "4.5", 2, T1);
        seed(P2, "Office Keyboard", "Laser tracking keyboard for professionals",
            BRAND_A, "LogiTech", CAT_Y, "Input", "30.00", "3.0", 1, T2);
        seed(P3, "Mechanical Keyboard", "Tactile switches for typing",
            BRAND_B, "Nordica", CAT_X, "Peripherals", "20.00", null, null, T3);
        seed(P4, "Wireless Mouse Pro", "Silent clicks and long battery life",
            BRAND_B, "Nordica", CAT_Y, "Input", "40.00", "5.0", 4, T4);
        seed(P5, "USB Hub", "Seven ports for everything on the desk",
            BRAND_C, "Kestrel", CAT_Z, "Accessories", "25.00", "2.0", 1, T5);
        elasticsearchClient.indices().refresh(r -> r.index("products"));
    }

    private void seed(UUID id, String title, String description, UUID brandId, String brandName,
                      UUID categoryId, String categoryName, String price, String avgRating,
                      Integer ratingCount, String updatedAt) {
        productSearchService.index(new ProductLifecycleEvent(
            UUID.randomUUID().toString(), "ProductCreated", updatedAt, id,
            slugFor(title, id), "ACTIVE", title, description, brandId, brandName,
            categoryId, categoryName, price != null ? new BigDecimal(price) : null,
            "http://img.example/" + slugFor(title, id) + ".png",
            avgRating != null ? new BigDecimal(avgRating) : null, ratingCount, updatedAt));
    }

    private static String slugFor(String title, UUID id) {
        return title.toLowerCase().replace(' ', '-') + "-" + id;
    }

    private static List<UUID> ids(List<ProductSearchResponse> content) {
        return content.stream().map(ProductSearchResponse::id).toList();
    }

    private static SearchRequest params() {
        return new SearchRequest(null, null, null, null, null, null, null, null, null);
    }

    private double counter(String sort) {
        var meter = meterRegistry.find("search_queries_total").tag("sort", sort).counter();
        return meter == null ? 0.0d : meter.count();
    }

    // --- relevance ---

    @Test
    @DisplayName("q relevance: title match ranks above description match")
    void relevance_titleMatchRanksAboveDescriptionMatch() {
        List<UUID> result = ids(searchQueryService.search(
            new SearchRequest("laser", null, null, null, null, null, null, null, null)).content());

        assertThat(result).containsExactly(P1, P2);
    }

    // --- filters ---

    @Test
    @DisplayName("brandId filter narrows to the brand's products")
    void filter_brandId_narrowsToBrand() {
        List<UUID> result = ids(searchQueryService.search(
            new SearchRequest(null, BRAND_A, null, null, null, null, null, null, null)).content());

        assertThat(result).containsExactlyInAnyOrder(P1, P2);
    }

    @Test
    @DisplayName("categoryId filter narrows to the category's products")
    void filter_categoryId_narrowsToCategory() {
        List<UUID> result = ids(searchQueryService.search(
            new SearchRequest(null, null, CAT_X, null, null, null, null, null, null)).content());

        assertThat(result).containsExactlyInAnyOrder(P1, P3);
    }

    @Test
    @DisplayName("minPrice/maxPrice range filter is inclusive on both bounds")
    void filter_priceRange_boundsInclusive() {
        List<UUID> result = ids(searchQueryService.search(
            new SearchRequest(null, null, null, new BigDecimal("20.00"), new BigDecimal("30.00"),
                null, null, null, null)).content());

        assertThat(result).containsExactlyInAnyOrder(P2, P3, P5);
    }

    @Test
    @DisplayName("minRating filter excludes below-threshold and never-rated docs")
    void filter_minRating_excludesNeverRatedDocs() {
        List<UUID> result = ids(searchQueryService.search(
            new SearchRequest(null, null, null, null, null, new BigDecimal("4.0"),
                null, null, null)).content());

        assertThat(result).containsExactlyInAnyOrder(P1, P4);
    }

    // --- sorts ---

    @Test
    @DisplayName("sort=price_asc orders by price ascending")
    void sort_priceAsc() {
        List<UUID> result = ids(searchQueryService.search(
            new SearchRequest(null, null, null, null, null, null, "price_asc", null, null)).content());

        assertThat(result).containsExactly(P1, P3, P5, P2, P4);
    }

    @Test
    @DisplayName("sort=price_desc orders by price descending")
    void sort_priceDesc() {
        List<UUID> result = ids(searchQueryService.search(
            new SearchRequest(null, null, null, null, null, null, "price_desc", null, null)).content());

        assertThat(result).containsExactly(P4, P2, P5, P3, P1);
    }

    @Test
    @DisplayName("sort=rating_desc orders by avgRating desc with never-rated docs last")
    void sort_ratingDesc_placesNeverRatedLast() {
        List<UUID> result = ids(searchQueryService.search(
            new SearchRequest(null, null, null, null, null, null, "rating_desc", null, null)).content());

        assertThat(result).containsExactly(P4, P1, P2, P5, P3);
    }

    @Test
    @DisplayName("sort=newest orders by updatedAt desc")
    void sort_explicitNewest_ordersByUpdatedAtDesc() {
        List<UUID> result = ids(searchQueryService.search(
            new SearchRequest(null, null, null, null, null, null, "newest", null, null)).content());

        assertThat(result).containsExactly(P5, P4, P3, P2, P1);
    }

    // --- F3 sort branching ---

    @Test
    @DisplayName("F3: q present + no sort param orders by relevance")
    void f3_qPresent_noSort_ordersByRelevance() {
        List<UUID> result = ids(searchQueryService.search(
            new SearchRequest("laser", null, null, null, null, null, null, null, null)).content());

        assertThat(result).containsExactly(P1, P2);
    }

    @Test
    @DisplayName("F3: explicit sort param wins over the relevance default when q present")
    void f3_qPresent_explicitNewestWinsOverRelevance() {
        List<UUID> result = ids(searchQueryService.search(
            new SearchRequest("laser", null, null, null, null, null, "newest", null, null)).content());

        assertThat(result).containsExactly(P2, P1);
    }

    @Test
    @DisplayName("F3: empty q + explicit sort=relevance falls back to newest (never _score on match_all)")
    void f3_emptyQ_explicitRelevance_fallsBackToNewest() {
        List<UUID> result = ids(searchQueryService.search(
            new SearchRequest(null, null, null, null, null, null, "relevance", null, null)).content());

        assertThat(result).containsExactly(P5, P4, P3, P2, P1);
    }

    // --- browse + pagination ---

    @Test
    @DisplayName("F3 browse: empty q + no sort returns all docs in newest order")
    void browse_emptyQ_returnsAllInNewestOrder() {
        var page = searchQueryService.search(params());

        assertThat(page.totalElements()).isEqualTo(5);
        assertThat(ids(page.content())).containsExactly(P5, P4, P3, P2, P1);
    }

    @Test
    @DisplayName("pagination slices pages with correct totals and first/last flags")
    void pagination_slicesPagesAndTotals() {
        var first = searchQueryService.search(
            new SearchRequest(null, null, null, null, null, null, null, 0, 2));

        assertThat(ids(first.content())).containsExactly(P5, P4);
        assertThat(first.totalElements()).isEqualTo(5);
        assertThat(first.totalPages()).isEqualTo(3);
        assertThat(first.first()).isTrue();
        assertThat(first.last()).isFalse();

        var lastPage = searchQueryService.search(
            new SearchRequest(null, null, null, null, null, null, null, 2, 2));

        assertThat(ids(lastPage.content())).containsExactly(P1);
        assertThat(lastPage.first()).isFalse();
        assertThat(lastPage.last()).isTrue();
    }

    @Test
    @DisplayName("size over the 200 cap is clamped without error")
    void sizeCap_overLimit_isClampedWithoutError() {
        var page = searchQueryService.search(
            new SearchRequest(null, null, null, null, null, null, null, null, 1000));

        assertThat(page.totalElements()).isEqualTo(5);
        assertThat(page.content()).hasSize(5);
    }

    // --- controller (HTTP + security posture) ---

    @Test
    @DisplayName("P2-6 edge: unauthenticated request is rejected with 401")
    void controller_noAuth_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/search"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("authenticated request returns the ApiResponse<PageResponse> shape")
    void controller_authenticated_returns200WithPageShape() throws Exception {
        mockMvc.perform(get("/api/v1/search")
                .with(SecurityMockMvcRequestPostProcessors.jwt()
                    .jwt(j -> j.subject(P1.toString()))
                    .authorities(AuthorityUtils.createAuthorityList("ROLE_USER"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.content[0].id").value(P5.toString()))
            .andExpect(jsonPath("$.data.content[0].title").value("USB Hub"))
            .andExpect(jsonPath("$.data.content[0].brandName").value("Kestrel"))
            .andExpect(jsonPath("$.data.content[0].categoryName").value("Accessories"))
            .andExpect(jsonPath("$.data.content[0].slug").value("usb-hub-" + P5))
            .andExpect(jsonPath("$.data.content[0].imageUrl").value("http://img.example/usb-hub-" + P5 + ".png"))
            .andExpect(jsonPath("$.data.content[0].price").value(25.0))
            .andExpect(jsonPath("$.data.content[0].avgRating").value(2.0))
            .andExpect(jsonPath("$.data.content[0].ratingCount").value(1))
            .andExpect(jsonPath("$.data.page").value(0))
            .andExpect(jsonPath("$.data.size").value(20))
            .andExpect(jsonPath("$.data.totalElements").value(5));
    }

    // --- meter ---

    @Test
    @DisplayName("search_queries_total increments once per query with the resolved sort tag")
    void query_incrementsSearchQueriesMeterWithResolvedSortTag() {
        double newestBefore = counter("newest");
        searchQueryService.search(params());
        assertThat(counter("newest")).isEqualTo(newestBefore + 1.0d);

        double relevanceBefore = counter("relevance");
        searchQueryService.search(
            new SearchRequest("laser", null, null, null, null, null, null, null, null));
        assertThat(counter("relevance")).isEqualTo(relevanceBefore + 1.0d);
    }
}
