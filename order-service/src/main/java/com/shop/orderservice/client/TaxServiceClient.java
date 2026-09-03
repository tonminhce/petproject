package com.shop.orderservice.client;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.common.core.viewmodel.ApiResponse;
import com.shop.orderservice.config.ShopServicesProperties;
import com.shop.orderservice.dto.internal.TaxCalculateRequest;
import com.shop.orderservice.dto.internal.TaxCalculateResponse;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;

@Component
@RequiredArgsConstructor
@Slf4j
public class TaxServiceClient {

    private final ShopServicesProperties props;

    @Qualifier("taxRestClient")
    private final RestClient restClient;

    private static final ParameterizedTypeReference<ApiResponse<TaxCalculateResponse>> RESPONSE_TYPE =
        new ParameterizedTypeReference<>() {};

    @CircuitBreaker(name = "taxService")
    public TaxCalculateResponse calculate(TaxCalculateRequest request) {
        if (!props.tax().isEnabled()) {
            // MVP default: tax disabled → return 0 (fail-closed only when enabled and down)
            return new TaxCalculateResponse(BigDecimal.ZERO, BigDecimal.ZERO);
        }
        try {
            ApiResponse<TaxCalculateResponse> resp = restClient.post()
                .uri("/api/v1/backoffice/tax-rates/calculate")
                .body(request)
                .retrieve()
                .body(RESPONSE_TYPE);
            return resp.data();
        } catch (HttpServerErrorException ex) {
            log.error("Tax service 5xx — failing closed", ex);
            throw BusinessException.of(ErrorCode.SERVICE_UNAVAILABLE, "tax");
        } catch (HttpClientErrorException ex) {
            log.warn("Tax calculation rejected: {}", ex.getMessage());
            throw BusinessException.of(ErrorCode.ORDER_TAX_CALCULATION_FAILED, ex.getMessage());
        }
    }
}
