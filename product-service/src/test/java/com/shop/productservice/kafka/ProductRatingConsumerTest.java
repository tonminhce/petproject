package com.shop.productservice.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.productservice.service.ProductRatingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.MessageHeaders;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ProductRatingConsumerTest {

    private static final UUID PRODUCT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String DOUBLE_ENCODED_TOKEN =
        "\"{\\\"eventId\\\":\\\"44444444-4444-4444-4444-444444444444\\\",\\\"eventType\\\":\\\"rating.submitted.v1\\\","
            + "\\\"occurredAt\\\":\\\"2026-08-31T10:00:00Z\\\",\\\"ratingId\\\":\\\"33333333-3333-3333-3333-333333333333\\\","
            + "\\\"productId\\\":\\\"22222222-2222-2222-2222-222222222222\\\",\\\"userId\\\":\\\"55555555-5555-5555-5555-555555555555\\\","
            + "\\\"rating\\\":5,\\\"comment\\\":\\\"Great product\\\",\\\"verified\\\":true,\\\"action\\\":\\\"CREATED\\\","
            + "\\\"visible\\\":true,\\\"avgRating\\\":4.50,\\\"ratingCount\\\":2}\"";
    private static final String SINGLE_ENCODED_JSON =
        "{\"eventId\":\"44444444-4444-4444-4444-444444444444\",\"eventType\":\"rating.submitted.v1\","
            + "\"occurredAt\":\"2026-08-31T10:00:00Z\",\"ratingId\":\"33333333-3333-3333-3333-333333333333\","
            + "\"productId\":\"22222222-2222-2222-2222-222222222222\",\"userId\":\"55555555-5555-5555-5555-555555555555\","
            + "\"rating\":5,\"comment\":\"Great product\",\"verified\":true,\"action\":\"CREATED\","
            + "\"visible\":true,\"avgRating\":4.50,\"ratingCount\":2}";

    @Mock ProductRatingService productRatingService;

    private ProductRatingConsumer consumer;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        consumer = new ProductRatingConsumer(productRatingService);
    }

    @Test
    @DisplayName("H-1: the sanctioned double-encoded fleet wire token binds to the typed handler")
    void onMessage_doubleEncodedToken_bindsTypedEventAndDelegates() {
        consumer.onMessage(DOUBLE_ENCODED_TOKEN, new MessageHeaders(new HashMap<>()));

        ArgumentCaptor<RatingLifecycleEvent> captor = ArgumentCaptor.forClass(RatingLifecycleEvent.class);
        verify(productRatingService).apply(captor.capture());
        assertThat(captor.getValue().productId()).isEqualTo(PRODUCT_ID);
        assertThat(captor.getValue().eventType()).isEqualTo("rating.submitted.v1");
        assertThat(captor.getValue().avgRating()).isEqualByComparingTo("4.50");
        assertThat(captor.getValue().ratingCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("H-1 tolerance: a future single-encoded relay payload also binds")
    void onMessage_singleEncodedJson_alsoAccepted() {
        consumer.onMessage(SINGLE_ENCODED_JSON, new MessageHeaders(new HashMap<>()));

        ArgumentCaptor<RatingLifecycleEvent> captor = ArgumentCaptor.forClass(RatingLifecycleEvent.class);
        verify(productRatingService).apply(captor.capture());
        assertThat(captor.getValue().productId()).isEqualTo(PRODUCT_ID);
    }

    @Test
    @DisplayName("H-1 containment: poison payload is an ack-skip — handler never invoked, no throw")
    void onMessage_poisonPayload_containedAckSkip() {
        assertThatCode(() -> consumer.onMessage("{ this is not json }", new MessageHeaders(new HashMap<>())))
            .doesNotThrowAnyException();

        verify(productRatingService, never()).apply(any());
    }

    @Test
    void onMessage_handlerThrows_exceptionContainedNeverEscapesListener() throws JsonProcessingException {
        doThrow(new RuntimeException("db down")).when(productRatingService).apply(any());

        assertThatCode(() -> consumer.onMessage(SINGLE_ENCODED_JSON, new MessageHeaders(new HashMap<>())))
            .doesNotThrowAnyException();

        verify(productRatingService).apply(any());
    }
}
