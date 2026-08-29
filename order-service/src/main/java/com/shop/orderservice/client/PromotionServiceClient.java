package com.shop.orderservice.client;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.common.core.viewmodel.ApiResponse;
import com.shop.orderservice.config.ShopServicesProperties;
import com.shop.orderservice.dto.internal.PromotionApplyRequest;
import com.shop.orderservice.dto.internal.PromotionApplyResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

@Component
@RequiredArgsConstructor
@Slf4j
public class PromotionServiceClient {

    private final ShopServicesProperties props;

    @Qualifier("promotionRestClient")
    private final RestClient restClient;

    private static final ParameterizedTypeReference<ApiResponse<PromotionApplyResponse>> RESPONSE_TYPE =
        new ParameterizedTypeReference<>() {};

    /**
     * Called by {@link com.shop.orderservice.service.impls.PricingServiceImpl} BEFORE invoking {@code apply()} — if false
     * and user provided a couponCode, we reject with 400 (P1-5 fix).
     */
    public boolean isEnabled() {
        return props.promotion().isEnabled();
    }

    public PromotionApplyResponse apply(PromotionApplyRequest request) {
        if (!props.promotion().isEnabled()) {
            // Caller (PricingService) should have already rejected user-supplied coupon when
            // promotion is disabled — see PricingServiceImpl.calculate for the check.
            // Defensive fallback: no discount.
            return new PromotionApplyResponse(java.math.BigDecimal.ZERO, request.orderAmount());
        }
        try {
            ApiResponse<PromotionApplyResponse> resp = restClient.post()
                .uri("/api/v1/backoffice/promotions/apply")
                .body(request)
                .retrieve()
                .body(RESPONSE_TYPE);
            return resp.data();
        } catch (HttpClientErrorException ex) {
            log.warn("Promotion apply rejected: {}", ex.getMessage());
            throw BusinessException.of(ErrorCode.ORDER_PROMOTION_INVALID, request.code());
        } catch (HttpServerErrorException ex) {
            log.error("Promotion service 5xx — failing closed", ex);
            throw BusinessException.of(ErrorCode.SERVICE_UNAVAILABLE, "promotion");
        }
    }
}
