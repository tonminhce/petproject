package com.shop.notificationservice.service.sender;

import com.shop.notificationservice.constant.NotificationChannel;
import com.shop.notificationservice.entity.Notification;

public interface NotificationSender {

    NotificationChannel channel();

    void send(Notification n);
}
