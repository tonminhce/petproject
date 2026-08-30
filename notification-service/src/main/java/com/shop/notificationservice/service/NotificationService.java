package com.shop.notificationservice.service;

import com.shop.notificationservice.dto.OrderLifecycleEvent;

public interface NotificationService {

    void handle(OrderLifecycleEvent event);
}
