package com.shop.ratingservice.service;

import com.shop.ratingservice.dto.request.RatingEditRequest;
import com.shop.ratingservice.dto.request.RatingSubmitRequest;
import com.shop.ratingservice.dto.response.RatingResponse;
import org.springframework.data.domain.Page;

import java.util.UUID;

public interface RatingService {

    RatingResponse submit(UUID jwtUserId, RatingSubmitRequest request);

    Page<RatingResponse> findVisibleByProductId(UUID productId, int page, int size);

    RatingResponse edit(UUID jwtUserId, UUID productId, RatingEditRequest request);

    RatingResponse hide(UUID id, UUID adminId, String reason);

    RatingResponse unhide(UUID id, UUID adminId);
}
