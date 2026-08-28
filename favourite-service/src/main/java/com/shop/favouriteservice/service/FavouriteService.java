package com.shop.favouriteservice.service;

import com.shop.favouriteservice.dto.request.FavouriteCreateRequest;
import com.shop.favouriteservice.dto.response.FavouriteResponse;

import java.util.List;
import java.util.UUID;

public interface FavouriteService {

    List<FavouriteResponse> findAllByCurrentUser(UUID userId);

    FavouriteResponse findById(UUID id, UUID userId);

    FavouriteResponse create(UUID userId, FavouriteCreateRequest request);

    void deleteById(UUID id, UUID userId);

    void deleteByProductId(UUID userId, UUID productId);
}
