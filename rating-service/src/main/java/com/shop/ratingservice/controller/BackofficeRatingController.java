package com.shop.ratingservice.controller;

import com.shop.common.core.constants.ApiPaths;
import com.shop.common.core.viewmodel.ApiResponse;
import com.shop.common.security.jwt.AuthenticatedUser;
import com.shop.ratingservice.dto.request.RatingHideRequest;
import com.shop.ratingservice.dto.response.RatingResponse;
import com.shop.ratingservice.service.RatingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Backoffice moderation surface (spec D2/D6) — the abuse lever behind the
 * auto-publish posture: fast hide/unhide beats any queue latency. Class-level
 * ADMIN gate per the fleet backoffice convention (BackofficePaymentController).
 */
@RestController
@RequestMapping(ApiPaths.BACKOFFICE_RATINGS)
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class BackofficeRatingController {

    private final RatingService ratingService;

    @PostMapping("/{id}/hide")
    public ApiResponse<RatingResponse> hide(@PathVariable UUID id,
                                            @Valid @RequestBody RatingHideRequest request) {
        UUID adminId = UUID.fromString(AuthenticatedUser.requireCurrent().id());
        return ApiResponse.ok(ratingService.hide(id, adminId, request.reason()));
    }

    @PostMapping("/{id}/unhide")
    public ApiResponse<RatingResponse> unhide(@PathVariable UUID id) {
        UUID adminId = UUID.fromString(AuthenticatedUser.requireCurrent().id());
        return ApiResponse.ok(ratingService.unhide(id, adminId));
    }
}
