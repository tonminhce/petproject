package com.shop.notificationservice.controller;

import com.shop.common.core.constants.ApiPaths;
import com.shop.common.core.constants.PageableConstant;
import com.shop.common.core.viewmodel.ApiResponse;
import com.shop.common.core.viewmodel.PageResponse;
import com.shop.notificationservice.dto.response.NotificationResponse;
import com.shop.notificationservice.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping(ApiPaths.BACKOFFICE_NOTIFICATIONS)
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class BackofficeNotificationController {

    private final NotificationService notificationService;

    @GetMapping
    public ApiResponse<PageResponse<NotificationResponse>> findAllByOrderId(
            @RequestParam(required = false) UUID orderId,
            @RequestParam(defaultValue = "" + PageableConstant.DEFAULT_PAGE_NUMBER) int page,
            @RequestParam(defaultValue = "" + PageableConstant.DEFAULT_PAGE_SIZE) int size) {
        Pageable pageable = PageRequest.of(page, Math.min(size, PageableConstant.MAX_PAGE_SIZE));
        Page<NotificationResponse> result = notificationService.findAllByOrderId(orderId, pageable);
        return ApiResponse.ok(PageResponse.of(
            result.getContent(), result.getNumber(), result.getSize(), result.getTotalElements()));
    }

    @GetMapping("/{id}")
    public ApiResponse<NotificationResponse> findById(@PathVariable UUID id) {
        return ApiResponse.ok(notificationService.findById(id));
    }
}
