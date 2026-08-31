package com.shop.productservice.service;

import com.shop.productservice.entity.Product;
import com.shop.productservice.kafka.RatingLifecycleEvent;
import com.shop.productservice.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductRatingServiceTest {

    private static final UUID PRODUCT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID RATING_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Mock ProductRepository repository;
    @Mock ProductEventPublisher publisher;

    private ProductRatingService service;

    @BeforeEach
    void setUp() {
        service = new ProductRatingService(repository, publisher);
    }

    private RatingLifecycleEvent event(String action, String avgRating, int ratingCount) {
        return new RatingLifecycleEvent(
            "44444444-4444-4444-4444-444444444444",
            "rating.submitted.v1",
            "2026-08-31T10:00:00Z",
            RATING_ID,
            PRODUCT_ID,
            UUID.fromString("55555555-5555-5555-5555-555555555555"),
            5,
            "Great product, highly recommend",
            true,
            action,
            true,
            avgRating == null ? null : new BigDecimal(avgRating),
            ratingCount);
    }

    private Product product() {
        Product p = new Product();
        p.setId(PRODUCT_ID);
        return p;
    }

    @Test
    void apply_createdEvent_copiesSnapshotOntoProduct() {
        Product p = product();
        when(repository.findById(PRODUCT_ID)).thenReturn(Optional.of(p));

        service.apply(event("CREATED", "4.50", 2));

        ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getAvgRating()).isEqualByComparingTo("4.50");
        assertThat(captor.getValue().getRatingCount()).isEqualTo(2);
    }

    @Test
    void apply_sameEventReplayed_sameFinalState() {
        when(repository.findById(PRODUCT_ID)).thenReturn(Optional.of(product()), Optional.of(product()));

        service.apply(event("CREATED", "4.50", 2));
        service.apply(event("CREATED", "4.50", 2));

        ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
        verify(repository, org.mockito.Mockito.times(2)).save(captor.capture());
        assertThat(captor.getAllValues().getLast().getAvgRating()).isEqualByComparingTo("4.50");
        assertThat(captor.getAllValues().getLast().getRatingCount()).isEqualTo(2);
    }

    @Test
    void apply_unknownProductId_noSaveNoThrow() {
        when(repository.findById(PRODUCT_ID)).thenReturn(Optional.empty());

        assertThatCode(() -> service.apply(event("CREATED", "4.50", 2)))
            .doesNotThrowAnyException();

        verify(repository, never()).save(any());
        verify(publisher, never()).publishUpdated(any());
    }

    @Test
    void apply_publishesProductUpdatedWithTheSavedEntityReturnedByRepoSave() {
        // F4: the publisher must receive the entity returned by save() — the
        // persisted instance (audit fields filled), not the pre-save detached one.
        Product managed = product();
        Product saved = product();
        saved.setTitle("SAVED-INSTANCE");
        when(repository.findById(PRODUCT_ID)).thenReturn(Optional.of(managed));
        when(repository.save(any(Product.class))).thenReturn(saved);

        service.apply(event("CREATED", "4.50", 2));

        verify(publisher, times(1)).publishUpdated(saved);
        verify(publisher, never()).publishCreated(any());
        verify(publisher, never()).publishDeleted(any());
    }

    @Test
    void apply_hiddenEventWithLoweredSnapshot_copiedVerbatim_noActionFiltering() {
        Product p = product();
        when(repository.findById(PRODUCT_ID)).thenReturn(Optional.of(p));

        service.apply(event("HIDDEN", "4.00", 1));

        ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getAvgRating()).isEqualByComparingTo("4.00");
        assertThat(captor.getValue().getRatingCount()).isEqualTo(1);
    }

    @Test
    void apply_updatedEvent_overwritesPreviousSnapshot() {
        when(repository.findById(PRODUCT_ID)).thenReturn(Optional.of(product()), Optional.of(product()));

        service.apply(event("CREATED", "4.50", 2));
        service.apply(event("UPDATED", "3.00", 3));

        ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
        verify(repository, org.mockito.Mockito.times(2)).save(captor.capture());
        assertThat(captor.getAllValues().getLast().getAvgRating()).isEqualByComparingTo("3.00");
        assertThat(captor.getAllValues().getLast().getRatingCount()).isEqualTo(3);
    }

    @Test
    void apply_nullAvgRating_copiedAsIs() {
        // Defensive decision: producer always sends avgRating; if a null ever
        // arrives we copy it verbatim (column is nullable per spec D5) — no
        // guard, no branching, dumb copy all the way down.
        Product p = product();
        p.setAvgRating(new BigDecimal("4.50"));
        p.setRatingCount(2);
        when(repository.findById(PRODUCT_ID)).thenReturn(Optional.of(p));

        service.apply(event("UPDATED", null, 0));

        ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getAvgRating()).isNull();
        assertThat(captor.getValue().getRatingCount()).isZero();
    }
}
