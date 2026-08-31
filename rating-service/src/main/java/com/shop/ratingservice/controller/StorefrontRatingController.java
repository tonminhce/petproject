package com.shop.ratingservice.controller;

import com.shop.common.core.constants.ApiPaths;
import com.shop.common.core.viewmodel.ApiResponse;
import com.shop.common.core.viewmodel.PageResponse;
import com.shop.common.security.jwt.AuthenticatedUser;
import com.shop.ratingservice.dto.request.RatingEditRequest;
import com.shop.ratingservice.dto.request.RatingSubmitRequest;
import com.shop.ratingservice.dto.response.RatingResponse;
import com.shop.ratingservice.service.RatingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Storefront rating surface (spec D2). Deliberately NOT annotated with
 * {@code @PreAuthorize} (fleet precedent P2-6, {@code OrderController}): Keycloak
 * users may lack an explicit USER realm role, which would surface as an unhelpful
 * 403. The fleet filter chain already enforces authentication; ownership is
 * resolved from the JWT userId in the service layer, so users only ever touch
 * their own ratings.
 */
@RestController
@RequestMapping(ApiPaths.RATINGS)
@RequiredArgsConstructor
public class StorefrontRatingController {

    private final RatingService ratingService;

    @GetMapping
    public ApiResponse<PageResponse<RatingResponse>> list(
            @RequestParam UUID productId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<RatingResponse> result = ratingService.findVisibleByProductId(productId, page, size);
        return ApiResponse.ok(PageResponse.of(
            result.getContent(), result.getNumber(), result.getSize(), result.getTotalElements()));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<RatingResponse> submit(@Valid @RequestBody RatingSubmitRequest request) {
        UUID userId = UUID.fromString(AuthenticatedUser.requireCurrent().id());
        return ApiResponse.ok(ratingService.submit(userId, request), "Rating submitted successfully");
    }

    @PutMapping("/{productId}")
    public ApiResponse<RatingResponse> edit(@PathVariable UUID productId,
                                            @Valid @RequestBody RatingEditRequest request) {
        UUID userId = UUID.fromString(AuthenticatedUser.requireCurrent().id());
        return ApiResponse.ok(ratingService.edit(userId, productId, request), "Rating updated successfully");
    }
}
