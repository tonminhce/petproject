package com.shop.paymentservice.controller;

import com.shop.common.core.constants.ApiPaths;
import com.shop.common.core.constants.PageableConstant;
import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.common.core.viewmodel.ApiResponse;
import com.shop.common.core.viewmodel.PageResponse;
import com.shop.common.logging.audit.Audited;
import com.shop.common.security.service.ServiceCallerGuard;
import com.shop.paymentservice.dto.CreatePaymentRequest;
import com.shop.paymentservice.dto.PaymentResponse;
import com.shop.paymentservice.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping(ApiPaths.PAYMENTS)
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;
    private final ServiceCallerGuard serviceCallerGuard;

    @PostMapping
    @PreAuthorize("hasAnyRole('SERVICE','ADMIN')")
    @Audited(action = "payment.create", resourceType = "payment")
    public ApiResponse<PaymentResponse> create(@Valid @RequestBody CreatePaymentRequest request) {

        serviceCallerGuard.requireCaller("order-service");
        return ApiResponse.ok(paymentService.create(request));
    }

    @PostMapping("/{id}/capture")
    @PreAuthorize("hasAnyRole('SERVICE','ADMIN')")
    @Audited(action = "payment.capture", resourceType = "payment")
    public ApiResponse<PaymentResponse> capture(@PathVariable UUID id) {

        serviceCallerGuard.requireCaller("order-service");
        return ApiResponse.ok(paymentService.capture(id));
    }

    @PostMapping("/{id}/refund")
    @PreAuthorize("hasAnyRole('SERVICE','ADMIN')")
    @Audited(action = "payment.refund", resourceType = "payment")
    public ApiResponse<PaymentResponse> refund(@PathVariable UUID id) {

        serviceCallerGuard.requireCaller("order-service");
        return ApiResponse.ok(paymentService.refund(id));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('SERVICE','ADMIN')")
    public ApiResponse<PageResponse<PaymentResponse>> findAllByOrderId(
            @RequestParam UUID orderId,
            @RequestParam(defaultValue = "" + PageableConstant.DEFAULT_PAGE_NUMBER) int page,
            @RequestParam(defaultValue = "" + PageableConstant.DEFAULT_PAGE_SIZE) int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, PageableConstant.MAX_PAGE_SIZE));
        Pageable pageable = PageRequest.of(safePage, safeSize);
        Page<PaymentResponse> result = paymentService.findAllByOrderId(orderId, pageable);
        return ApiResponse.ok(PageResponse.of(
            result.getContent(), result.getNumber(), result.getSize(), result.getTotalElements()));
    }

    /**
     * The captured payment of an order - the answer the order-service confirm
     * gate needs.
     *
     * <p>The paged list endpoint above cannot answer it: the gate must see
     * EVERY payment of the order, and a captured payment beyond the first page
     * is invisible to a caller that reads one page. The gate then fails closed
     * (ORD-4012), the order can never be confirmed and is auto-cancelled
     * later - a customer who paid loses the order.</p>
     */
    @GetMapping("/by-order/{orderId}/captured")
    @PreAuthorize("hasAnyRole('SERVICE','ADMIN')")
    public ApiResponse<PaymentResponse> findCapturedByOrderId(@PathVariable UUID orderId) {
        return paymentService.findCapturedByOrderId(orderId)
                .map(ApiResponse::ok)
                .orElseThrow(() -> BusinessException.of(ErrorCode.PAYMENT_NOT_FOUND, orderId));
    }
}
