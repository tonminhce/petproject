package com.shop.notificationservice.service;

import com.shop.notificationservice.dto.OrderLifecycleEvent;
import com.shop.notificationservice.dto.response.NotificationResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface NotificationService {

    void handle(OrderLifecycleEvent event);

    Page<NotificationResponse> findAllByOrderId(UUID orderId, Pageable pageable);

    NotificationResponse findById(UUID id);
}
