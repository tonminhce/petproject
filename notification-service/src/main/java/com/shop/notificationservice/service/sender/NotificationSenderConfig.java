package com.shop.notificationservice.service.sender;

import com.shop.notificationservice.constant.NotificationChannel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.List;

@Configuration
public class NotificationSenderConfig {

    @Bean
    @Primary
    public NotificationSender primary(List<NotificationSender> all) {
        return all.stream()
                .filter(s -> s.channel() == NotificationChannel.SMTP)
                .findFirst()
                .orElseGet(LoggingNotificationSender::new);
    }
}
