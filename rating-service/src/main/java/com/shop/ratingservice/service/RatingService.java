package com.shop.ratingservice.service;

import com.shop.ratingservice.dto.request.RatingSubmitRequest;
import com.shop.ratingservice.dto.response.RatingResponse;

import java.util.UUID;

public interface RatingService {

    RatingResponse submit(UUID jwtUserId, RatingSubmitRequest request);
}
