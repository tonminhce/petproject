package com.shop.searchservice.service.impls;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.ErrorCause;
import co.elastic.clients.elasticsearch._types.ErrorResponse;
import co.elastic.clients.elasticsearch.indices.ElasticsearchIndicesClient;
import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.viewmodel.PageResponse;
import com.shop.searchservice.client.ProductBackofficeClient;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * dryRun alias-lookup failure mapping (T5 review F1): a non-404 ES error from
 * the alias lookup surfaces as 503 SRH-12002 — never a raw 500. Uses a mocked
 * client so the failure is instant and deterministic (SearchQueryServiceTest
 * precedent — a real-client failure would wait out connect timeouts).
 */
class ReindexServiceTest {

    private final ElasticsearchClient client = mock(ElasticsearchClient.class);
    private final ProductBackofficeClient productClient = mock(ProductBackofficeClient.class);
    private final ReindexServiceImpl service = new ReindexServiceImpl(client, productClient);

    @Test
    void dryRun_esErrorOnAliasLookup_mapsTo503Srh12002() throws IOException {
        when(productClient.fetchPage(anyInt(), eq(ReindexServiceImpl.SOURCE_PAGE_SIZE)))
            .thenReturn(PageResponse.of(List.of(), 0, ReindexServiceImpl.SOURCE_PAGE_SIZE, 0));

        ElasticsearchException esError = new ElasticsearchException("indices.get_alias",
            ErrorResponse.of(e -> e.status(502)
                .error(ErrorCause.of(c -> c.reason("upstream failure")))));
        ElasticsearchIndicesClient indices = mock(ElasticsearchIndicesClient.class);
        when(client.indices()).thenReturn(indices);
        doThrow(esError).when(indices).getAlias(any(Function.class));

        assertThatThrownBy(() -> service.reindex(true))
            .isInstanceOfSatisfying(BusinessException.class, ex -> {
                assertThat(ex.getErrorCode()).isEqualTo("SRH-12002");
                assertThat(ex.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            });
    }
}
