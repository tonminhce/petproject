package com.shop.orderservice.controller;

import com.shop.common.core.constants.ApiPaths;
import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.viewmodel.ApiResponse;
import com.shop.orderservice.dto.response.OrderTrackingResponse;
import com.shop.orderservice.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping(ApiPaths.ORDERS)
@RequiredArgsConstructor
public class OrderTrackingController {

    private final OrderService orderService;

    /**
     * Order tracking.
     *
     * <p>The endpoint stays unauthenticated - a customer who has not signed
     * in yet must be able to follow their order - but it is gated by the
     * order's TRACKING TOKEN, a random capability issued with the order. The
     * phone number remains as the legacy secondary factor for callers that
     * have no token yet; that path is rate-limited and locked out after a few
     * wrong numbers, because a phone number is low entropy and the order id
     * leaks routinely.</p>
     */
    @GetMapping("/track")
    public ApiResponse<OrderTrackingResponse> trackOrder(
            @RequestParam UUID orderId,
            @RequestParam(required = false) String token,
            @RequestParam(required = false) String phone) {
        if ((token == null || token.isBlank()) && (phone == null || phone.isBlank())) {
            // rejected at the edge: the service would refuse it anyway, but
            // the endpoint must not even look up an order without a factor.
            throw BusinessException.badRequest("order.track.credentials_required");
        }
        return ApiResponse.ok(
                orderService.trackOrder(orderId, token, phone),
                "Order tracking details retrieved successfully");
    }
}
