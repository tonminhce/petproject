package com.shop.searchservice.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.Result;
import co.elastic.clients.elasticsearch.core.DeleteResponse;
import com.shop.searchservice.kafka.dto.ProductLifecycleEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Dumb upsert/delete of the FULL-snapshot product document behind the
 * {@code products} alias (spec D1/D2): the payload is copied as-is, no
 * recompute, no source lookups.
 *
 * <p>Status handling is BIDIRECTIONAL (F1): an ACTIVE payload upserts the
 * doc, any non-ACTIVE payload deletes it — which covers every transition
 * including DRAFT re-published to ACTIVE.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProductSearchService {

    static final String INDEX_ALIAS = "products";

    private static final String STATUS_ACTIVE = "ACTIVE";

    private final ElasticsearchClient client;

    public void index(ProductLifecycleEvent event) {
        if (!STATUS_ACTIVE.equals(event.status())) {
            delete(event.productId());
            return;
        }
        Instant updatedAt = parseInstant(event.updatedAt());
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("id", event.productId().toString());
        document.put("title", event.title());
        document.put("description", event.description());
        document.put("brandName", event.brandName());
        document.put("brandId", event.brandId() != null ? event.brandId().toString() : null);
        document.put("categoryId", event.categoryId() != null ? event.categoryId().toString() : null);
        document.put("categoryName", event.categoryName());
        document.put("slug", event.slug());
        document.put("imageUrl", event.imageUrl());
        document.put("price", event.price());
        document.put("avgRating", event.avgRating());
        document.put("ratingCount", event.ratingCount());
        document.put("status", event.status());
        // ES client's JacksonJsonpMapper has no JavaTimeModule — the Instant
        // is serialized as an ISO-8601 string, which the `date` mapping accepts.
        document.put("updatedAt", updatedAt != null ? updatedAt.toString() : null);
        try {
            client.index(i -> i
                .index(INDEX_ALIAS)
                .id(event.productId().toString())
                .document(document));
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to index product " + event.productId(), ex);
        }
    }

    public void delete(UUID productId) {
        try {
            DeleteResponse response = client.delete(d -> d.index(INDEX_ALIAS).id(productId.toString()));
            if (response.result() == Result.NotFound) {
                log.debug("Delete for missing product doc {} — nothing to do", productId);
            }
        } catch (ElasticsearchException ex) {
            if (ex.status() != 404) {
                throw ex;
            }
            log.debug("Delete for missing product doc {} — nothing to do", productId);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to delete product doc " + productId, ex);
        }
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ex) {
            log.warn("Unparseable updatedAt '{}' — indexed without it", value);
            return null;
        }
    }
}
