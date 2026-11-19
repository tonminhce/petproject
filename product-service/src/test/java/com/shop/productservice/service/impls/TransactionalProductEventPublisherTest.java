package com.shop.productservice.service.impls;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.common.core.constants.OutboxStatus;
import com.shop.productservice.constant.ProductStatus;
import com.shop.productservice.entity.Brand;
import com.shop.productservice.entity.Category;
import com.shop.productservice.entity.OutboxEvent;
import com.shop.productservice.entity.Product;
import com.shop.productservice.repository.OutboxEventRepository;
import com.shop.productservice.service.ProductMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.verify;

/**
 * Payload-parity pins for the outbox envelope (spec D2, binding contract):
 * every field name and value of the JSON snapshot, the exact key set (no
 * extras), envelope/eventType stability, and null-safety for never-rated
 * products without brand/category relations.
 */
@ExtendWith(MockitoExtension.class)
class TransactionalProductEventPublisherTest {

    private static final UUID PRODUCT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID BRAND_ID = UUID.fromString("66666666-6666-6666-6666-666666666666");
    private static final UUID CATEGORY_ID = UUID.fromString("77777777-7777-7777-7777-777777777777");
    private static final UUID MEDIA_ID = UUID.fromString("88888888-8888-8888-8888-888888888888");
    private static final Instant UPDATED_AT = Instant.parse("2026-08-31T10:00:00Z");

    private static final List<String> PAYLOAD_FIELDS = List.of(
        "eventId", "eventType", "occurredAt", "productId",
        "slug", "status",
        "title", "description", "brandId", "brandName", "categoryId", "categoryName",
        "price", "imageUrl", "avgRating", "ratingCount", "updatedAt");

    @Mock OutboxEventRepository outboxRepository;
    @Mock ProductMetrics metrics;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private TransactionalProductEventPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new TransactionalProductEventPublisher(outboxRepository, objectMapper, metrics);
    }

    private Product fullProduct() {
        Brand brand = Brand.builder().id(BRAND_ID).name("Acme").slug("acme").build();
        Category category = Category.builder().id(CATEGORY_ID).title("Electronics").slug("electronics").build();
        Product p = Product.builder()
            .id(PRODUCT_ID)
            .title("iPhone 15")
            .slug("iphone-15")
            .description("Flagship phone")
            .sku("IP15-001")
            .priceUnit(new BigDecimal("999.00"))
            .quantity(10)
            .status(ProductStatus.ACTIVE)
            .imageUrl("http://img.example/ip15.png")
            .avgRating(new BigDecimal("4.50"))
            .ratingCount(2)
            .brand(brand)
            .category(category)
            .build();
        p.setUpdatedAt(UPDATED_AT);
        return p;
    }

    private OutboxEvent capturedEvent() {
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(captor.capture());
        return captor.getValue();
    }

    private JsonNode payloadOf(OutboxEvent event) throws Exception {
        return objectMapper.readTree(event.getPayload());
    }

    private List<String> fieldNames(JsonNode payload) {
        List<String> names = new ArrayList<>();
        payload.fieldNames().forEachRemaining(names::add);
        return names;
    }

    @Test
    void publishUpdated_fullyLoadedProduct_payloadPinsEveryFieldNameAndValue() throws Exception {
        publisher.publishUpdated(fullProduct());

        OutboxEvent event = capturedEvent();
        assertThat(event.getAggregateType()).isEqualTo("Product");
        assertThat(event.getAggregateId()).isEqualTo(PRODUCT_ID);
        assertThat(event.getEventType()).isEqualTo("ProductUpdated");
        assertThat(event.getTopic()).isEqualTo("shop.product.lifecycle.v1");
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event.getRetryCount()).isZero();

        JsonNode payload = payloadOf(event);
        assertThat(fieldNames(payload)).containsExactlyInAnyOrderElementsOf(PAYLOAD_FIELDS);

        assertThat(payload.get("eventId").asText()).isEqualTo(event.getEventId());
        assertThat(payload.get("eventType").asText()).isEqualTo("ProductUpdated");
        assertThat(Instant.parse(payload.get("occurredAt").asText()))
            .isCloseTo(Instant.now(), within(30, ChronoUnit.SECONDS));
        assertThat(payload.get("productId").asText()).isEqualTo(PRODUCT_ID.toString());
        assertThat(payload.get("slug").asText()).isEqualTo("iphone-15");
        assertThat(payload.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(payload.get("title").asText()).isEqualTo("iPhone 15");
        assertThat(payload.get("description").asText()).isEqualTo("Flagship phone");
        assertThat(payload.get("brandId").asText()).isEqualTo(BRAND_ID.toString());
        assertThat(payload.get("brandName").asText()).isEqualTo("Acme");
        assertThat(payload.get("categoryId").asText()).isEqualTo(CATEGORY_ID.toString());
        assertThat(payload.get("categoryName").asText()).isEqualTo("Electronics");
        assertThat(payload.get("price").decimalValue()).isEqualByComparingTo("999.00");
        assertThat(payload.get("imageUrl").asText()).isEqualTo("http://img.example/ip15.png");
        assertThat(payload.get("avgRating").decimalValue()).isEqualByComparingTo("4.50");
        assertThat(payload.get("ratingCount").asInt()).isEqualTo(2);
        assertThat(payload.get("updatedAt").asText()).isEqualTo(UPDATED_AT.toString());

        verify(metrics).recordEventPublished("ProductUpdated");
    }

    @Test
    void publishCreated_neverRatedProductWithoutRelations_nullsSerializedAsJsonNull() throws Exception {
        Product p = Product.builder()
            .id(PRODUCT_ID)
            .title("Bare Product")
            .slug("bare-product")
            .sku("BARE-001")
            .priceUnit(new BigDecimal("5.00"))
            .quantity(1)
            .status(ProductStatus.DRAFT)
            .build();
        p.setUpdatedAt(UPDATED_AT);

        publisher.publishCreated(p);

        OutboxEvent event = capturedEvent();
        assertThat(event.getEventType()).isEqualTo("ProductCreated");

        JsonNode payload = payloadOf(event);
        assertThat(fieldNames(payload)).containsExactlyInAnyOrderElementsOf(PAYLOAD_FIELDS);
        assertThat(payload.get("avgRating").isNull()).isTrue();
        assertThat(payload.get("brandId").isNull()).isTrue();
        assertThat(payload.get("brandName").isNull()).isTrue();
        assertThat(payload.get("categoryId").isNull()).isTrue();
        assertThat(payload.get("categoryName").isNull()).isTrue();
        assertThat(payload.get("ratingCount").asInt()).isZero();
        assertThat(payload.get("status").asText()).isEqualTo("DRAFT");
        assertThat(payload.get("description").isNull()).isTrue();
        assertThat(payload.get("imageUrl").isNull()).isTrue();
    }

    @Test
    void publishDeleted_eventTypeStringUnchanged() throws Exception {
        publisher.publishDeleted(fullProduct());

        OutboxEvent event = capturedEvent();
        assertThat(event.getEventType()).isEqualTo("ProductDeleted");
        assertThat(payloadOf(event).get("eventType").asText()).isEqualTo("ProductDeleted");
    }

    @Test
    void publishUpdated_mediaIdPresent_imageUrlIsDerivedCanonicalPath_17NamesUnchanged() throws Exception {
        // Media epic spec D5: same 17-name contract, new imageUrl VALUE SOURCE —
        // the derived canonical path replaces the stored free-string when the
        // product references a media.
        Product p = fullProduct();
        p.setMediaId(MEDIA_ID);

        publisher.publishUpdated(p);

        JsonNode payload = payloadOf(capturedEvent());
        assertThat(fieldNames(payload)).containsExactlyInAnyOrderElementsOf(PAYLOAD_FIELDS);
        assertThat(payload.get("imageUrl").asText()).isEqualTo("/api/v1/medias/" + MEDIA_ID);
    }

    @Test
    void publishUpdated_mediaIdNull_imageUrlStaysLegacyFreeString() throws Exception {
        Product p = fullProduct();
        p.setMediaId(null);

        publisher.publishUpdated(p);

        JsonNode payload = payloadOf(capturedEvent());
        assertThat(fieldNames(payload)).containsExactlyInAnyOrderElementsOf(PAYLOAD_FIELDS);
        assertThat(payload.get("imageUrl").asText()).isEqualTo("http://img.example/ip15.png");
    }
}
