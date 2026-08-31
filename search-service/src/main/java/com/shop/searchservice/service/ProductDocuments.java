package com.shop.searchservice.service;

import com.shop.searchservice.client.ProductBackofficeClient;
import com.shop.searchservice.kafka.ProductLifecycleEvent;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Shared D3 document mapping — the single source of the 14-field product
 * document shape indexed behind the {@code products} alias. Both ingestion
 * paths converge here: the Kafka consumer ({@link ProductLifecycleEvent}) and
 * the reindex stream ({@link ProductBackofficeClient.ProductSnapshot}).
 */
@Slf4j
public final class ProductDocuments {

    private ProductDocuments() {
    }

    public static Map<String, Object> of(ProductLifecycleEvent event) {
        return of(event.productId(), event.title(), event.description(), event.brandId(),
            event.brandName(), event.categoryId(), event.categoryName(), event.slug(),
            event.imageUrl(), event.price(), event.avgRating(), event.ratingCount(),
            event.status(), parseInstant(event.updatedAt()));
    }

    public static Map<String, Object> of(ProductBackofficeClient.ProductSnapshot snapshot) {
        return of(snapshot.id(), snapshot.title(), snapshot.description(), snapshot.brandId(),
            snapshot.brandName(), snapshot.categoryId(), snapshot.categoryTitle(), snapshot.slug(),
            snapshot.imageUrl(), snapshot.priceUnit(), snapshot.avgRating(), snapshot.ratingCount(),
            snapshot.status(), parseInstant(snapshot.updatedAt()));
    }

    private static Map<String, Object> of(UUID id, String title, String description,
            UUID brandId, String brandName, UUID categoryId, String categoryName,
            String slug, String imageUrl, BigDecimal price, BigDecimal avgRating,
            Integer ratingCount, String status, Instant updatedAt) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("id", id.toString());
        document.put("title", title);
        document.put("description", description);
        document.put("brandName", brandName);
        document.put("brandId", brandId != null ? brandId.toString() : null);
        document.put("categoryId", categoryId != null ? categoryId.toString() : null);
        document.put("categoryName", categoryName);
        document.put("slug", slug);
        document.put("imageUrl", imageUrl);
        document.put("price", price);
        document.put("avgRating", avgRating);
        document.put("ratingCount", ratingCount);
        document.put("status", status);
        // ES client's JacksonJsonpMapper has no JavaTimeModule — the Instant
        // is serialized as an ISO-8601 string, which the `date` mapping accepts.
        document.put("updatedAt", updatedAt != null ? updatedAt.toString() : null);
        return document;
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
