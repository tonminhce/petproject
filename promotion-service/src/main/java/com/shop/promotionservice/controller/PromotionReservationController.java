package com.shop.promotionservice.controller;

import com.shop.common.core.constants.ApiPaths;
import com.shop.common.core.viewmodel.ApiResponse;
import com.shop.common.logging.audit.Audited;
import com.shop.promotionservice.dto.request.ReserveRequest;
import com.shop.promotionservice.dto.response.ReservationResponse;
import com.shop.promotionservice.service.ReservationRetryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping(ApiPaths.PROMOTIONS)
@RequiredArgsConstructor
public class PromotionReservationController {

    private final ReservationRetryService reservationService;

    @PostMapping("/{code}/reserve")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('SERVICE') or hasRole('ADMIN')")
    @Audited(action = "promotion.reserve", resourceType = "reservation")
    public ApiResponse<ReservationResponse> reserve(@PathVariable String code,
                                                     @Valid @RequestBody ReserveRequest request) {
        return ApiResponse.ok(reservationService.reserveWithRetry(code, request));
    }

    @PostMapping("/reservations/{reservationId}/commit")
    @PreAuthorize("hasRole('SERVICE') or hasRole('ADMIN')")
    @Audited(action = "promotion.commit", resourceType = "reservation")
    public ApiResponse<Void> commit(@PathVariable UUID reservationId) {
        reservationService.commitWithRetry(reservationId);
        return ApiResponse.message("Reservation committed successfully");
    }

    @PostMapping("/reservations/{reservationId}/release")
    @PreAuthorize("hasRole('SERVICE') or hasRole('ADMIN')")
    @Audited(action = "promotion.release", resourceType = "reservation")
    public ApiResponse<Void> release(@PathVariable UUID reservationId) {
        reservationService.releaseWithRetry(reservationId);
        return ApiResponse.message("Reservation released successfully");
    }

    @PostMapping("/reservations/{reservationId}/release-committed")
    @PreAuthorize("hasRole('SERVICE') or hasRole('ADMIN')")
    @Audited(action = "promotion.release-committed", resourceType = "reservation")
    public ApiResponse<Void> releaseCommitted(@PathVariable UUID reservationId) {
        reservationService.releaseCommittedWithRetry(reservationId);
        return ApiResponse.ok(null);
    }

    @GetMapping("/reservations/{reservationId}/state")
    @PreAuthorize("hasRole('SERVICE') or hasRole('ADMIN')")
    public ApiResponse<ReservationResponse> state(@PathVariable UUID reservationId) {
        return ApiResponse.ok(reservationService.getStateWithRetry(reservationId));
    }
}
