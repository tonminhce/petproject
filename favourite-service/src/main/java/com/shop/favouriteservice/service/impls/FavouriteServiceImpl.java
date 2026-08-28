package com.shop.favouriteservice.service.impls;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.favouriteservice.dto.request.FavouriteCreateRequest;
import com.shop.favouriteservice.dto.response.FavouriteResponse;
import com.shop.favouriteservice.entity.Favourite;
import com.shop.favouriteservice.mapper.FavouriteMapper;
import com.shop.favouriteservice.repository.FavouriteRepository;
import com.shop.favouriteservice.service.FavouriteService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.AuditorAware;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class FavouriteServiceImpl implements FavouriteService {

    private final FavouriteRepository repo;
    private final FavouriteMapper mapper;
    private final AuditorAware<String> auditorAware;

    @Override
    @Transactional(readOnly = true)
    public List<FavouriteResponse> findAllByCurrentUser(UUID userId) {
        return repo.findByUserIdOrderByCreatedAtDesc(userId)
                .stream()
                .map(mapper::toResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public FavouriteResponse findById(UUID id, UUID userId) {
        return mapper.toResponse(findOwnedOrThrow(id, userId));
    }

    @Override
    @Transactional
    public FavouriteResponse create(UUID userId, FavouriteCreateRequest request) {
        if (repo.existsByUserIdAndProductId(userId, request.productId())) {
            throw BusinessException.of(ErrorCode.FAVOURITE_ALREADY_EXISTS);
        }
        Favourite favourite = Favourite.builder()
                .userId(userId)
                .productId(request.productId())
                .build();
        Favourite saved = repo.save(favourite);
        return mapper.toResponse(saved);
    }

    @Override
    @Transactional
    public void deleteById(UUID id, UUID userId) {
        String actor = auditorAware.getCurrentAuditor().orElse("system");
        int affected = repo.softDeleteByIdAndUserId(id, userId, actor);
        if (affected == 0) {
            throw BusinessException.of(ErrorCode.FAVOURITE_NOT_FOUND, id);
        }
    }

    @Override
    @Transactional
    public void deleteByProductId(UUID userId, UUID productId) {
        String actor = auditorAware.getCurrentAuditor().orElse("system");
        int affected = repo.softDeleteByUserIdAndProductId(userId, productId, actor);
        if (affected == 0) {
            // FAV-6003 — message đúng ngữ cảnh product (không tái dùng FAV-6001)
            throw BusinessException.of(ErrorCode.FAVOURITE_PRODUCT_NOT_FOUND, productId);
        }
    }

    private Favourite findOwnedOrThrow(UUID id, UUID userId) {
        return repo.findByIdAndUserId(id, userId)
                .orElseThrow(() -> BusinessException.of(ErrorCode.FAVOURITE_NOT_FOUND, id));
    }
}
