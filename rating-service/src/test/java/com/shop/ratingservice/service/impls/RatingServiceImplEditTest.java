package com.shop.ratingservice.service.impls;

import com.shop.common.core.exception.BusinessException;
import com.shop.ratingservice.constant.RatingAction;
import com.shop.ratingservice.dto.request.RatingEditRequest;
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
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;

import java.util.List;
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
class RatingServiceImplEditTest {

    @Mock private RatingRepository ratingRepository;
    @Mock private EligibilityClient eligibilityClient;
    @Mock private RatingEventService ratingEventService;

    @InjectMocks private RatingServiceImpl service;

    private final UUID userId = UUID.randomUUID();
    private final UUID productId = UUID.randomUUID();
    private final UUID ratingId = UUID.randomUUID();

    private RatingEditRequest editRequest() {
        return new RatingEditRequest(2, "Edited comment, still decent value");
    }

    private Rating existingRating(boolean verified) {
        return Rating.builder()
                .id(ratingId)
                .productId(productId)
                .userId(userId)
                .rating(5)
                .comment("Original comment, was excited then")
                .verified(verified)
                .hidden(false)
                .build();
    }

    // --- edit ---

    @Test
    void edit_updatesOwnRow_setsEditedAt_andPreservesVerified() {
        Rating own = existingRating(true);
        when(ratingRepository.findByUserIdAndProductIdAndDeletedFalse(userId, productId)).thenReturn(Optional.of(own));
        when(ratingRepository.saveAndFlush(any(Rating.class))).thenAnswer(inv -> inv.getArgument(0));

        RatingResponse response = service.edit(userId, productId, editRequest());

        // D6: edit sets rating+comment+editedAt; verified/hidden untouched.
        assertThat(own.getRating()).isEqualTo(2);
        assertThat(own.getComment()).isEqualTo("Edited comment, still decent value");
        assertThat(own.getEditedAt()).isNotNull();
        assertThat(own.isVerified()).isTrue();
        assertThat(own.isHidden()).isFalse();
        assertThat(response.id()).isEqualTo(ratingId);
        assertThat(response.rating()).isEqualTo(2);
        assertThat(response.editedAt()).isNotNull();
        assertThat(response.verified()).isTrue();
        verifyNoInteractions(eligibilityClient);
    }

    @Test
    void edit_unverifiedRow_staysUnverified() {
        Rating own = existingRating(false);
        when(ratingRepository.findByUserIdAndProductIdAndDeletedFalse(userId, productId)).thenReturn(Optional.of(own));
        when(ratingRepository.saveAndFlush(any(Rating.class))).thenAnswer(inv -> inv.getArgument(0));

        RatingResponse response = service.edit(userId, productId, editRequest());

        assertThat(own.isVerified()).isFalse();
        assertThat(response.verified()).isFalse();
    }

    @Test
    void edit_flushLandsBeforeUpdatedEvent_inOrder() {
        Rating own = existingRating(true);
        when(ratingRepository.findByUserIdAndProductIdAndDeletedFalse(userId, productId)).thenReturn(Optional.of(own));
        when(ratingRepository.saveAndFlush(any(Rating.class))).thenAnswer(inv -> inv.getArgument(0));

        service.edit(userId, productId, editRequest());

        // NIT #1 — the snapshot aggregate JPQL inside record() only sees the
        // updated values once saveAndFlush has landed them.
        InOrder inOrder = inOrder(ratingRepository, ratingEventService);
        inOrder.verify(ratingRepository).saveAndFlush(any(Rating.class));
        inOrder.verify(ratingEventService).record(any(Rating.class), eq(RatingAction.UPDATED));
    }

    @Test
    void edit_noOwnRow_throwsNotFound() {
        when(ratingRepository.findByUserIdAndProductIdAndDeletedFalse(userId, productId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.edit(userId, productId, editRequest()))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo("RTG-11002");
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                });

        verify(ratingRepository, never()).saveAndFlush(any(Rating.class));
        verifyNoInteractions(ratingEventService);
    }

    // --- list ---

    @Test
    void findVisibleByProductId_sortsNewestFirst_andQueriesVisibleOnly() {
        Rating a = existingRating(true);
        Rating b = existingRating(false);
        PageRequest expected = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"));
        when(ratingRepository.findByProductIdAndHiddenFalseAndDeletedFalse(eq(productId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(a, b), expected, 2));

        var result = service.findVisibleByProductId(productId, 0, 20);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(ratingRepository).findByProductIdAndHiddenFalseAndDeletedFalse(eq(productId), captor.capture());
        Pageable used = captor.getValue();
        assertThat(used.getSort()).isEqualTo(Sort.by(Sort.Direction.DESC, "createdAt"));
        assertThat(used.getPageNumber()).isZero();
        assertThat(used.getPageSize()).isEqualTo(20);
        assertThat(result.getTotalElements()).isEqualTo(2);
        assertThat(result.getContent()).allSatisfy(r -> {
            assertThat(r.rating()).isEqualTo(5);
            assertThat(r.hidden()).isFalse();
        });
    }

    @Test
    void findVisibleByProductId_capsSizeAtFleetMax() {
        PageRequest capped = PageRequest.of(0, 200, Sort.by(Sort.Direction.DESC, "createdAt"));
        when(ratingRepository.findByProductIdAndHiddenFalseAndDeletedFalse(eq(productId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), capped, 0));

        service.findVisibleByProductId(productId, 0, 500);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(ratingRepository).findByProductIdAndHiddenFalseAndDeletedFalse(eq(productId), captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(200);
    }
}
