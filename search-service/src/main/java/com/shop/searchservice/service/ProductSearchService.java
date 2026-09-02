package com.shop.searchservice.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.Result;
import co.elastic.clients.elasticsearch.core.DeleteResponse;
import com.shop.searchservice.kafka.ProductLifecycleEvent;
import com.shop.searchservice.search.IndexProvisioner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

/**
 * Dumb upsert/delete of the FULL-snapshot product document behind the
 * {@link IndexProvisioner#ALIAS} alias (spec D1/D2): the payload is copied
 * as-is, no recompute, no source lookups.
 *
 * <p>Status handling is BIDIRECTIONAL (F1): an ACTIVE payload upserts the
 * doc, any non-ACTIVE payload deletes it — which covers every transition
 * including DRAFT re-published to ACTIVE.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProductSearchService {

    private static final String STATUS_ACTIVE = "ACTIVE";

    private final ElasticsearchClient client;

    public void index(ProductLifecycleEvent event) {
        if (!STATUS_ACTIVE.equals(event.status())) {
            delete(event.productId());
            return;
        }
        Map<String, Object> document = ProductDocuments.of(event);
        try {
            client.index(i -> i
                .index(IndexProvisioner.ALIAS)
                .id(event.productId().toString())
                .document(document));
        } catch (IOException | ElasticsearchException ex) {
            // C18 fix — surface a domain exception so ApiExceptionHandler maps it
            // to a 503 with the canonical envelope instead of leaking the raw
            // IllegalStateException (which would be 500 with a stack trace).
            throw com.shop.common.core.exception.BusinessException.of(
                com.shop.common.core.exception.ErrorCode.SEARCH_INDEX_FAILED,
                event.productId());
        }
    }

    public void delete(UUID productId) {
        try {
            DeleteResponse response = client.delete(d -> d.index(IndexProvisioner.ALIAS).id(productId.toString()));
            if (response.result() == Result.NotFound) {
                log.debug("Delete for missing product doc {} — nothing to do", productId);
            }
        } catch (ElasticsearchException ex) {
            if (ex.status() != 404) {
                throw com.shop.common.core.exception.BusinessException.of(
                    com.shop.common.core.exception.ErrorCode.SEARCH_DELETE_FAILED, productId);
            }
            log.debug("Delete for missing product doc {} — nothing to do", productId);
        } catch (IOException ex) {
            throw com.shop.common.core.exception.BusinessException.of(
                com.shop.common.core.exception.ErrorCode.SEARCH_DELETE_FAILED, productId);
        }
    }
}
