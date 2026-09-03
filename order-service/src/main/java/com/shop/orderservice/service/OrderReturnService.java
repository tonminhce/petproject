package com.shop.orderservice.service;

import com.shop.common.core.viewmodel.PageResponse;
import com.shop.orderservice.dto.request.OrderReturnCreateRequest;
import com.shop.orderservice.dto.request.OrderReturnReviewRequest;
import com.shop.orderservice.dto.response.OrderReturnResponse;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

public interface OrderReturnService {

    OrderReturnResponse requestReturn(UUID userId, UUID orderId, OrderReturnCreateRequest request);

    List<OrderReturnResponse> findByOrderId(UUID userId, UUID orderId);

    PageResponse<OrderReturnResponse> findMyReturns(UUID userId, Pageable pageable);

    OrderReturnResponse reviewReturn(UUID returnId, String adminUsername, OrderReturnReviewRequest request);
}
