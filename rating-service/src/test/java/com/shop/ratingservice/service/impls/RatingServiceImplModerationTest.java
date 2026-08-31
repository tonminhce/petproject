package com.shop.ratingservice.service.impls;

import com.shop.common.core.exception.BusinessException;
import com.shop.ratingservice.constant.RatingAction;
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

import java.time.Instant;
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
class RatingServiceImplModerationTest {

    @Mock private RatingRepository ratingRepository;
    @Mock private EligibilityClient eligibilityClient;
    @Mock private RatingEventService ratingEventService;

    @InjectMocks private RatingServiceImpl service;

    private final UUID adminId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID productId = UUID.randomUUID();
    private final UUID ratingId = UUID.randomUUID();

    private Rating visibleRating() {
        return Rating.builder()
                .id(ratingId)
                .productId(productId)
                .userId(userId)
                .rating(1)
                .comment("Terrible scam product, avoid")
                .verified(true)
                .hidden(false)
                .build();
    }

    private Rating hiddenRating() {
        return Rating.builder()
                .id(ratingId)
                .productId(productId)
                .userId(userId)
                .rating(1)
                .comment("Terrible scam product, avoid")
                .verified(true)
                .hidden(true)
                .hiddenAt(Instant.parse("2026-08-31T08:00:00Z"))
                .hiddenBy(adminId)
                .hiddenReason("Abusive comment")
                .build();
    }

    // --- hide ---

    @Test
    void hide_setsAuditTrio_recordsHiddenEventAfterFlush() {
        Rating rating = visibleRating();
        when(ratingRepository.findByIdAndDeletedFalse(ratingId)).thenReturn(Optional.of(rating));
        when(ratingRepository.saveAndFlush(any(Rating.class))).thenAnswer(inv -> inv.getArgument(0));

        service.hide(ratingId, adminId, "Abusive comment");

        assertThat(rating.isHidden()).isTrue();
        assertThat(rating.getHiddenAt()).isNotNull();
        assertThat(rating.getHiddenBy()).isEqualTo(adminId);
        assertThat(rating.getHiddenReason()).isEqualTo("Abusive comment");

        // NIT #1: flush lands before record() so the snapshot aggregate sees
        // the hidden row (count drops).
        InOrder inOrder = inOrder(ratingRepository, ratingEventService);
        inOrder.verify(ratingRepository).saveAndFlush(any(Rating.class));
        inOrder.verify(ratingEventService).record(any(Rating.class), eq(RatingAction.HIDDEN));
    }

    @Test
    void hide_hiddenRatingPassedToRecord_isHidden_soPayloadVisibleFalse() {
        Rating rating = visibleRating();
        when(ratingRepository.findByIdAndDeletedFalse(ratingId)).thenReturn(Optional.of(rating));
        when(ratingRepository.saveAndFlush(any(Rating.class))).thenAnswer(inv -> inv.getArgument(0));

        service.hide(ratingId, adminId, "Abusive comment");

        ArgumentCaptor<Rating> captor = ArgumentCaptor.forClass(Rating.class);
        verify(ratingEventService).record(captor.capture(), eq(RatingAction.HIDDEN));
        assertThat(captor.getValue().isHidden()).isTrue();
    }

    @Test
    void hide_unknownRating_throwsNotFound() {
        when(ratingRepository.findByIdAndDeletedFalse(ratingId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.hide(ratingId, adminId, "Abusive comment"))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo("RTG-11002");
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                });

        verify(ratingRepository, never()).saveAndFlush(any(Rating.class));
        verifyNoInteractions(ratingEventService);
    }

    @Test
    void hide_alreadyHidden_throwsConflict() {
        when(ratingRepository.findByIdAndDeletedFalse(ratingId)).thenReturn(Optional.of(hiddenRating()));

        assertThatThrownBy(() -> service.hide(ratingId, adminId, "Abusive comment"))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo("RTG-11003");
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                });

        verify(ratingRepository, never()).saveAndFlush(any(Rating.class));
        verifyNoInteractions(ratingEventService);
    }

    // --- unhide ---

    @Test
    void unhide_clearsHidden_retainsAuditTrio_recordsUnhiddenAfterFlush() {
        Rating rating = hiddenRating();
        when(ratingRepository.findByIdAndDeletedFalse(ratingId)).thenReturn(Optional.of(rating));
        when(ratingRepository.saveAndFlush(any(Rating.class))).thenAnswer(inv -> inv.getArgument(0));

        service.unhide(ratingId, adminId);

        // D2: audit trio retained as history; only the flag flips.
        assertThat(rating.isHidden()).isFalse();
        assertThat(rating.getHiddenAt()).isEqualTo(Instant.parse("2026-08-31T08:00:00Z"));
        assertThat(rating.getHiddenBy()).isEqualTo(adminId);
        assertThat(rating.getHiddenReason()).isEqualTo("Abusive comment");

        InOrder inOrder = inOrder(ratingRepository, ratingEventService);
        inOrder.verify(ratingRepository).saveAndFlush(any(Rating.class));
        inOrder.verify(ratingEventService).record(any(Rating.class), eq(RatingAction.UNHIDDEN));
    }

    @Test
    void unhide_notHidden_throwsConflict() {
        when(ratingRepository.findByIdAndDeletedFalse(ratingId)).thenReturn(Optional.of(visibleRating()));

        assertThatThrownBy(() -> service.unhide(ratingId, adminId))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo("RTG-11004");
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                });

        verify(ratingRepository, never()).saveAndFlush(any(Rating.class));
        verifyNoInteractions(ratingEventService);
    }

    @Test
    void unhide_unknownRating_throwsNotFound() {
        when(ratingRepository.findByIdAndDeletedFalse(ratingId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.unhide(ratingId, adminId))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo("RTG-11002");
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                });

        verify(ratingRepository, never()).saveAndFlush(any(Rating.class));
        verifyNoInteractions(ratingEventService);
    }
}
