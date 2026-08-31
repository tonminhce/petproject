package com.shop.searchservice.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.transport.TransportException;
import com.shop.common.core.exception.BusinessException;
import com.shop.searchservice.dto.request.SearchParams;
import com.shop.searchservice.metrics.SearchMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;

/**
 * ES-unavailability mapping (spec D6): any transport-level failure (I/O,
 * timeout, connection refused) surfaces as 503 SRH-12002 — never a raw
 * exception, never a 500. Uses a mocked client so the failure is instant
 * and deterministic instead of waiting out real connect timeouts.
 */
class SearchQueryServiceTest {

    private final ElasticsearchClient client = mock(ElasticsearchClient.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final SearchQueryService service = new SearchQueryService(client, new SearchMetrics(registry));

    private SearchParams params() {
        SearchParams params = new SearchParams();
        params.setQ("mouse");
        return params;
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
