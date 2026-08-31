package com.shop.ratingservice.service.impls;

import com.shop.common.core.exception.BusinessException;
import com.shop.ratingservice.constant.RatingAction;
import com.shop.ratingservice.dto.request.RatingSubmitRequest;
import com.shop.ratingservice.dto.response.RatingResponse;
import com.shop.ratingservice.eligibility.EligibilityClient;
import com.shop.ratingservice.entity.Rating;
import com.shop.ratingservice.repository.RatingRepository;
import com.shop.ratingservice.service.RatingEventService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RatingServiceImplTest {

    @Mock private RatingRepository ratingRepository;
    @Mock private EligibilityClient eligibilityClient;
    @Mock private RatingEventService ratingEventService;

    @InjectMocks private RatingServiceImpl service;

    private final UUID userId = UUID.randomUUID();
    private final UUID productId = UUID.randomUUID();
    private final UUID ratingId = UUID.randomUUID();

    private RatingSubmitRequest request() {
        return new RatingSubmitRequest(productId, 5, "Great product, really enjoyed it");
    }

    @Test
    void submit_ineligible_failsClosed() {
        when(ratingRepository.findByUserIdAndProductIdAndDeletedFalse(userId, productId)).thenReturn(Optional.empty());
        when(eligibilityClient.isEligible(userId, productId)).thenReturn(false);

        assertThatThrownBy(() -> service.submit(userId, request()))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo("RTG-11001");
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                });

        verify(ratingRepository, never()).saveAndFlush(any(Rating.class));
        verifyNoInteractions(ratingEventService);
    }

    @Test
    void submit_clientFailureSeam_propagatesWithoutSave() {
        // EligibilityClient fails closed internally (never throws by contract) —
        // but if that seam ever leaks, the service must not swallow it or save.
        when(ratingRepository.findByUserIdAndProductIdAndDeletedFalse(userId, productId)).thenReturn(Optional.empty());
        when(eligibilityClient.isEligible(userId, productId)).thenThrow(new IllegalStateException("downstream boom"));

        assertThatThrownBy(() -> service.submit(userId, request()))
                .isInstanceOf(IllegalStateException.class);

        verify(ratingRepository, never()).saveAndFlush(any(Rating.class));
        verifyNoInteractions(ratingEventService);
    }

    @Test
    void submit_eligible_createsVerifiedRating_recordsCreatedEvent() {
        when(ratingRepository.findByUserIdAndProductIdAndDeletedFalse(userId, productId)).thenReturn(Optional.empty());
        when(eligibilityClient.isEligible(userId, productId)).thenReturn(true);
        when(ratingRepository.saveAndFlush(any(Rating.class))).thenAnswer(inv -> {
            Rating r = inv.getArgument(0);
            r.setId(ratingId);
            return r;
        });

        RatingResponse response = service.submit(userId, request());

        ArgumentCaptor<Rating> captor = ArgumentCaptor.forClass(Rating.class);
        verify(ratingRepository).saveAndFlush(captor.capture());
        Rating persisted = captor.getValue();
        assertThat(persisted.getUserId()).isEqualTo(userId);
        assertThat(persisted.getProductId()).isEqualTo(productId);
        assertThat(persisted.getRating()).isEqualTo(5);
        assertThat(persisted.getComment()).isEqualTo("Great product, really enjoyed it");
        assertThat(persisted.isVerified()).isTrue();
        assertThat(persisted.isHidden()).isFalse();

        // NIT #1 — flush MUST land before record(): the snapshot aggregate JPQL
        // inside record() only sees the new row after the flush.
        InOrder inOrder = inOrder(ratingRepository, ratingEventService);
        inOrder.verify(ratingRepository).saveAndFlush(any(Rating.class));
        inOrder.verify(ratingEventService).record(any(Rating.class), eq(RatingAction.CREATED));

        assertThat(response.id()).isEqualTo(ratingId);
        assertThat(response.productId()).isEqualTo(productId);
        assertThat(response.userId()).isEqualTo(userId);
        assertThat(response.rating()).isEqualTo(5);
        assertThat(response.verified()).isTrue();
        assertThat(response.hidden()).isFalse();
    }

    @Test
    void submit_duplicate_conflict() {
        when(ratingRepository.findByUserIdAndProductIdAndDeletedFalse(userId, productId))
                .thenReturn(Optional.of(Rating.builder().id(ratingId).userId(userId).productId(productId).build()));

        assertThatThrownBy(() -> service.submit(userId, request()))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo("RTG-11005");
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                });

        verifyNoInteractions(eligibilityClient);
        verify(ratingRepository, never()).saveAndFlush(any(Rating.class));
        verifyNoInteractions(ratingEventService);
    }
}
