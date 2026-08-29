package com.shop.favouriteservice.service;

import com.shop.common.core.viewmodel.PageResponse;
import com.shop.favouriteservice.dto.request.FavouriteCreateRequest;
import com.shop.favouriteservice.dto.response.FavouriteResponse;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface FavouriteService {

    PageResponse<FavouriteResponse> findAllByCurrentUser(UUID userId, Pageable pageable);

    FavouriteResponse findById(UUID id, UUID userId);

    FavouriteResponse create(UUID userId, FavouriteCreateRequest request);

    void deleteById(UUID id, UUID userId);

    void deleteByProductId(UUID userId, UUID productId);
}
