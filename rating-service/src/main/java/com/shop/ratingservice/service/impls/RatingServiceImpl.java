package com.shop.ratingservice.service.impls;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.ratingservice.constant.RatingAction;
import com.shop.ratingservice.dto.request.RatingSubmitRequest;
import com.shop.ratingservice.dto.response.RatingResponse;
import com.shop.ratingservice.eligibility.EligibilityClient;
import com.shop.ratingservice.entity.Rating;
import com.shop.ratingservice.repository.RatingRepository;
import com.shop.ratingservice.service.RatingEventService;
import com.shop.ratingservice.service.RatingService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
}
