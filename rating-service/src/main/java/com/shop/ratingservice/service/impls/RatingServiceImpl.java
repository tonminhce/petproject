package com.shop.ratingservice.service.impls;

import com.shop.common.core.constants.PageableConstant;
import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.ratingservice.constant.RatingAction;
import com.shop.ratingservice.dto.request.RatingEditRequest;
import com.shop.ratingservice.dto.request.RatingSubmitRequest;
import com.shop.ratingservice.dto.response.RatingResponse;
import com.shop.ratingservice.eligibility.EligibilityClient;
import com.shop.ratingservice.entity.Rating;
import com.shop.ratingservice.repository.RatingRepository;
import com.shop.ratingservice.service.RatingEventService;
import com.shop.ratingservice.service.RatingService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RatingServiceImpl implements RatingService {

    private final RatingRepository ratingRepository;
    private final EligibilityClient eligibilityClient;
    private final RatingEventService ratingEventService;

    @Override
    @Transactional
    public RatingResponse submit(UUID jwtUserId, RatingSubmitRequest request) {
        // D6: one rating per (user, product) EVER — edit goes through PUT.
        ratingRepository.findByUserIdAndProductIdAndDeletedFalse(jwtUserId, request.productId())
                .ifPresent(existing -> {
                    throw BusinessException.of(ErrorCode.RATING_ALREADY_EXISTS);
                });

        // D1: fail-closed eligibility gate — the client returns false on ANY
        // verification failure, ineligible is a hard 403 (no unverified rows).
        boolean eligible = eligibilityClient.isEligible(jwtUserId, request.productId());
        if (!eligible) {
            throw BusinessException.of(ErrorCode.RATING_NOT_ELIGIBLE);
        }

        Rating rating = Rating.builder()
                .productId(request.productId())
                .userId(jwtUserId)
                .rating(request.rating())
                .comment(request.comment())
                .verified(eligible)
                .hidden(false)
                .build();

        // NIT #1: flush REQUIRED before record() — the snapshot aggregate JPQL
        // only sees this row once it is flushed in the current transaction.
        Rating saved = ratingRepository.saveAndFlush(rating);
        ratingEventService.record(saved, RatingAction.CREATED);
        return RatingResponse.from(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<RatingResponse> findVisibleByProductId(UUID productId, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, PageableConstant.MAX_PAGE_SIZE));
        PageRequest pageRequest = PageRequest.of(safePage, safeSize,
                Sort.by(Sort.Direction.DESC, "createdAt"));
        return ratingRepository.findByProductIdAndHiddenFalseAndDeletedFalse(productId, pageRequest)
                .map(RatingResponse::from);
    }

    @Override
    @Transactional
    public RatingResponse edit(UUID jwtUserId, UUID productId, RatingEditRequest request) {
        Rating rating = ratingRepository.findByUserIdAndProductIdAndDeletedFalse(jwtUserId, productId)
                .orElseThrow(() -> BusinessException.of(ErrorCode.RATING_NOT_FOUND));

        // D6: verified stamped at submit time only — edit preserves it and never
        // re-checks eligibility; hidden/audit fields are the backoffice's alone.
        rating.setRating(request.rating());
        rating.setComment(request.comment());
        rating.setEditedAt(Instant.now());

        // NIT #1: flush REQUIRED before record() — same discipline as submit.
        Rating saved = ratingRepository.saveAndFlush(rating);
        ratingEventService.record(saved, RatingAction.UPDATED);
        return RatingResponse.from(saved);
    }

    @Override
    @Transactional
    public RatingResponse hide(UUID id, UUID adminId, String reason) {
        Rating rating = ratingRepository.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> BusinessException.of(ErrorCode.RATING_NOT_FOUND));

        if (rating.isHidden()) {
            throw BusinessException.of(ErrorCode.RATING_ALREADY_HIDDEN);
        }

        rating.setHidden(true);
        rating.setHiddenAt(Instant.now());
        rating.setHiddenBy(adminId);
        rating.setHiddenReason(reason);

        // NIT #1: flush REQUIRED before record() — the snapshot aggregate must
        // see the hidden row (count drops) once record() recomputes it.
        Rating saved = ratingRepository.saveAndFlush(rating);
        ratingEventService.record(saved, RatingAction.HIDDEN);
        return RatingResponse.from(saved);
    }

    @Override
    @Transactional
    public RatingResponse unhide(UUID id, UUID adminId) {
        Rating rating = ratingRepository.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> BusinessException.of(ErrorCode.RATING_NOT_FOUND));

        if (!rating.isHidden()) {
            throw BusinessException.of(ErrorCode.RATING_NOT_HIDDEN);
        }

        // D2: hiddenAt/hiddenBy/hiddenReason RETAINED as history — only the
        // flag flips.
        rating.setHidden(false);

        // NIT #1: flush REQUIRED before record() — same discipline as hide.
        Rating saved = ratingRepository.saveAndFlush(rating);
        ratingEventService.record(saved, RatingAction.UNHIDDEN);
        return RatingResponse.from(saved);
    }
}
