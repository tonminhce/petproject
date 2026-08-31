package com.shop.productservice.kafka;

import com.shop.productservice.service.ProductRatingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.MessageHeaders;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ProductRatingConsumerTest {

    private static final UUID PRODUCT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock ProductRatingService productRatingService;

    private ProductRatingConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new ProductRatingConsumer(productRatingService);
    }

    private RatingLifecycleEvent event() {
        return new RatingLifecycleEvent(
            "44444444-4444-4444-4444-444444444444",
            "rating.submitted.v1",
            "2026-08-31T10:00:00Z",
            UUID.fromString("33333333-3333-3333-3333-333333333333"),
            PRODUCT_ID,
            UUID.fromString("55555555-5555-5555-5555-555555555555"),
            5,
            "Great product, highly recommend",
            true,
            "CREATED",
            true,
            new BigDecimal("4.50"),
            2);
    }

    @Test
    void onMessage_delegatesEventToHandler() {
        RatingLifecycleEvent event = event();

        consumer.onMessage(event, new MessageHeaders(new HashMap<>()));

        verify(productRatingService).apply(event);
    }

    @Test
    void onMessage_handlerThrows_exceptionContainedNeverEscapesListener() {
        RatingLifecycleEvent event = event();
        doThrow(new RuntimeException("db down")).when(productRatingService).apply(any());

        assertThatCode(() -> consumer.onMessage(event, new MessageHeaders(new HashMap<>())))
            .doesNotThrowAnyException();

        verify(productRatingService).apply(event);
    }
}
