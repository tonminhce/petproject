package com.shop.productservice.service;

import com.shop.productservice.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * H-4 unit proofs for the internal reference-count surface: the service is a
 * read-only passthrough to the indexed {@code countByMediaId} probe — never a
 * write, never the clear-set query.
 */
@ExtendWith(MockitoExtension.class)
class ProductMediaServiceTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private ProductEventPublisher productEventPublisher;

    private ProductMediaService service;

    private static final UUID MEDIA_ID = UUID.fromString("c1000000-0000-0000-0000-000000000002");

    @BeforeEach
    void setUp() {
        service = new ProductMediaService(productRepository, productEventPublisher);
    }

    @Test
    @DisplayName("referenceCount delegates to the indexed count probe — read-only")
    void referenceCount_delegatesToCountProbe() {
        when(productRepository.countByMediaId(MEDIA_ID)).thenReturn(5L);

        assertThat(service.referenceCount(MEDIA_ID)).isEqualTo(5L);
        verify(productRepository).countByMediaId(MEDIA_ID);
        verifyNoMoreInteractions(productRepository, productEventPublisher);
    }

    @Test
    @DisplayName("referenceCount zero — no products reference the media")
    void referenceCount_zeroWhenUnreferenced() {
        when(productRepository.countByMediaId(MEDIA_ID)).thenReturn(0L);

        assertThat(service.referenceCount(MEDIA_ID)).isZero();
    }

    @Test
    @DisplayName("clearReference is untouched by H-4 — still clears rows and publishes updates")
    void clearReference_stillClearsAndPublishes() {
        var product = new com.shop.productservice.entity.Product();
        when(productRepository.findByMediaId(MEDIA_ID)).thenReturn(List.of(product));
        when(productRepository.save(product)).thenReturn(product);

        int cleared = service.clearReference(MEDIA_ID);

        assertThat(cleared).isEqualTo(1);
        verify(productRepository).save(product);
        verify(productEventPublisher).publishUpdated(product);
    }
}
