package com.shop.notificationservice.service.sender;

import com.shop.notificationservice.constant.NotificationChannel;
import com.shop.notificationservice.entity.Notification;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class LoggingNotificationSender implements NotificationSender {

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.LOG;
    }

    @Override
    public void send(Notification n) {
        log.info("[notification] subject='{}', body='{}'", n.getSubject(), n.getBody());
    }
}
