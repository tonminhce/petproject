package com.shop.searchservice.service.impls;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.ErrorCause;
import co.elastic.clients.elasticsearch._types.ErrorResponse;
import co.elastic.clients.transport.TransportException;
import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.viewmodel.PageResponse;
import com.shop.searchservice.dto.request.SearchRequest;
import com.shop.searchservice.dto.response.ProductSearchResponse;
import com.shop.searchservice.metrics.SearchMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ES-unavailability mapping (spec D6): any transport-level failure (I/O,
 * timeout, connection refused) AND any ES error response (e.g.
 * index_not_found_exception on a missing alias during a degraded provisioning
 * window) surfaces as 503 SRH-12002 — never a raw exception, never a 500.
 * Uses a mocked client so the failure is instant and deterministic instead of
 * waiting out real connect timeouts.
 */
class SearchQueryServiceTest {

    private final ElasticsearchClient client = mock(ElasticsearchClient.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final SearchQueryServiceImpl service = new SearchQueryServiceImpl(client, new SearchMetrics(registry));

    private SearchRequest params() {
        return new SearchRequest("mouse", null, null, null, null, null, null, null, null);
    }

    @Test
    void esIoFailure_mapsTo503Srh12002() throws IOException {
        doThrow(new IOException("connection reset")).when(client)
            .search(any(java.util.function.Function.class), eq(Map.class));

        assertThatThrownBy(() -> service.search(params()))
            .isInstanceOfSatisfying(BusinessException.class, ex -> {
                assertThat(ex.getErrorCode()).isEqualTo("SRH-12002");
                assertThat(ex.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            });
    }

    @Test
    void transportTimeout_mapsTo503Srh12002() throws IOException {
        TransportException timeout = mock(TransportException.class);
        doThrow(timeout).when(client)
            .search(any(java.util.function.Function.class), eq(Map.class));

        assertThatThrownBy(() -> service.search(params()))
            .isInstanceOfSatisfying(BusinessException.class, ex -> {
                assertThat(ex.getErrorCode()).isEqualTo("SRH-12002");
                assertThat(ex.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            });
    }

    @Test
    void esError_missingAlias_mapsTo503Srh12002() throws IOException {
        ElasticsearchException aliasMissing = new ElasticsearchException("search",
            ErrorResponse.of(e -> e.status(404)
                .error(ErrorCause.of(c -> c.type("index_not_found_exception")
                    .reason("no such index [products]")))));
        doThrow(aliasMissing).when(client)
            .search(any(java.util.function.Function.class), eq(Map.class));

        assertThatThrownBy(() -> service.search(params()))
            .isInstanceOfSatisfying(BusinessException.class, ex -> {
                assertThat(ex.getErrorCode()).isEqualTo("SRH-12002");
                assertThat(ex.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            });
    }

    @Test
    void deepPagination_usesPointInTimeAndSearchAfter() throws IOException {
        // page=50 * size=200 = from=10000 -> from+size=10200 > MAX_RESULT_WINDOW (10000).
        // The H37/Wave-C fix must NOT reject this; instead it must open a PIT, search
        // with search_after cursor pagination, and return the next page (not 503).
        // https://www.elastic.co/guide/en/elasticsearch/reference/9.4/paginate-search-results.html
        // https://www.elastic.co/guide/en/elasticsearch/reference/9.4/point-in-time-api.html
        SearchRequest request = new SearchRequest(null, null, null, null, null, null,
            "newest", 50, 200);

        // PIT open: a real id is returned so the service threads it into the search call.
        when(client.openPointInTime(any(co.elastic.clients.elasticsearch.core.OpenPointInTimeRequest.class)))
            .thenReturn(co.elastic.clients.elasticsearch.core.OpenPointInTimeResponse
                .of(b -> b.id("pit-id-abc")));

        // Stubbed search response: one hit with a sort cursor value.
        java.util.Map<String, Object> doc = java.util.Map.of(
            "id", "a1000000-0000-0000-0000-000000000099",
            "title", "Deep Page Item",
            "brandName", "LogiTech",
            "categoryName", "Peripherals",
            "slug", "deep-page-item",
            "imageUrl", "http://img.example/deep.png",
            "price", "42.00",
            "avgRating", "4.0",
            "ratingCount", 3);
        co.elastic.clients.elasticsearch.core.SearchResponse<Map> response =
            co.elastic.clients.elasticsearch.core.SearchResponse.of(b -> b
                .took(0L)
                .timedOut(false)
                .shards(s -> s.total(1).successful(1).skipped(0).failed(0))
                .hits(h -> h
                    .total(t -> t.value(1L).relation(
                        co.elastic.clients.elasticsearch.core.search.TotalHitsRelation.Eq))
                    .hits(co.elastic.clients.elasticsearch.core.search.Hit.of(hb -> hb
                        .index("products-v1")
                        .id("a1000000-0000-0000-0000-000000000099")
                        .source(doc)
                        .sort(java.util.List.of(
                            co.elastic.clients.elasticsearch._types.FieldValue.of("2026-09-01T00:00:00Z")))))
                    .maxScore(null))
                .pitId("pit-id-abc"));
        when(client.search(any(java.util.function.Function.class), eq(Map.class)))
            .thenReturn(response);

        PageResponse<ProductSearchResponse> page = service.search(request);

        assertThat(page.content()).hasSize(1);
        assertThat(page.content().get(0).id())
            .isEqualTo(UUID.fromString("a1000000-0000-0000-0000-000000000099"));
        assertThat(page.content().get(0).title()).isEqualTo("Deep Page Item");
        assertThat(page.page()).isEqualTo(50);
        assertThat(page.size()).isEqualTo(200);
        assertThat(page.totalElements()).isEqualTo(1L);

        // PIT was opened and closed.
        org.mockito.Mockito.verify(client).openPointInTime(any(co.elastic.clients.elasticsearch.core.OpenPointInTimeRequest.class));
        org.mockito.Mockito.verify(client).closePointInTime(any(co.elastic.clients.elasticsearch.core.ClosePointInTimeRequest.class));
    }

    @Test
    void deepPagination_pitOpenFailure_mapsTo503Srh12002() throws IOException {
        SearchRequest request = new SearchRequest(null, null, null, null, null, null,
            "newest", 50, 200);

        when(client.openPointInTime(any(co.elastic.clients.elasticsearch.core.OpenPointInTimeRequest.class)))
            .thenThrow(new IOException("pit unavailable"));

        assertThatThrownBy(() -> service.search(request))
            .isInstanceOfSatisfying(BusinessException.class, ex -> {
                assertThat(ex.getErrorCode()).isEqualTo("SRH-12002");
                assertThat(ex.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            });
    }

    @Test
    void shallowPagination_doesNotOpenPit() throws IOException {
        // page=0 * size=20 = from=0 < MAX_RESULT_WINDOW (10000) -> no PIT path.
        SearchRequest request = new SearchRequest(null, null, null, null, null, null,
            "newest", 0, 20);

        java.util.Map<String, Object> doc = java.util.Map.of(
            "id", "a1000000-0000-0000-0000-000000000001",
            "title", "Shallow",
            "brandName", "X", "categoryName", "Y",
            "slug", "shallow", "imageUrl", "http://img/x.png",
            "price", "1.00", "avgRating", "5.0", "ratingCount", 1);
        co.elastic.clients.elasticsearch.core.SearchResponse<Map> response =
            co.elastic.clients.elasticsearch.core.SearchResponse.of(b -> b
                .took(0L)
                .timedOut(false)
                .shards(s -> s.total(1).successful(1).skipped(0).failed(0))
                .hits(h -> h
                    .total(t -> t.value(1L).relation(
                        co.elastic.clients.elasticsearch.core.search.TotalHitsRelation.Eq))
                    .hits(co.elastic.clients.elasticsearch.core.search.Hit.of(hb -> hb
                        .index("products-v1")
                        .id("a1000000-0000-0000-0000-000000000001")
                        .source(doc)
                        .sort(java.util.List.of(
                            co.elastic.clients.elasticsearch._types.FieldValue.of("2026-09-01T00:00:00Z")))))
                    .maxScore(null)));
        when(client.search(any(java.util.function.Function.class), eq(Map.class)))
            .thenReturn(response);

        service.search(request);

        org.mockito.Mockito.verify(client, org.mockito.Mockito.never())
            .openPointInTime(any(co.elastic.clients.elasticsearch.core.OpenPointInTimeRequest.class));
        org.mockito.Mockito.verify(client, org.mockito.Mockito.never())
            .closePointInTime(any(co.elastic.clients.elasticsearch.core.ClosePointInTimeRequest.class));
    }

    @Test
    void failedSearch_doesNotIncrementQueryMeter() throws IOException {
        doThrow(new IOException("elasticsearch is down")).when(client)
            .search(any(java.util.function.Function.class), eq(Map.class));

        try {
            service.search(params());
        } catch (BusinessException ignored) {
            // expected
        }

        assertThat(registry.find("search_queries_total").counter()).isNull();
    }
}
