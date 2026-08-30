package com.shop.notificationservice.service.sender;

import com.shop.notificationservice.constant.NotificationChannel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class NotificationSenderConfigTest {

    private final NotificationSenderConfig config = new NotificationSenderConfig();

    @Test
    void onlyLogSender_resolvesLog() {
        LoggingNotificationSender logSender = new LoggingNotificationSender();

        NotificationSender resolved = config.primary(List.of(logSender));

        assertThat(resolved.channel()).isEqualTo(NotificationChannel.LOG);
    }

    @Test
    void logAndSmtpSenders_resolvesSmtp() {
        LoggingNotificationSender logSender = new LoggingNotificationSender();
        SmtpNotificationSender smtpSender =
                new SmtpNotificationSender(mock(org.springframework.mail.javamail.JavaMailSender.class), "ops@example.com");

        NotificationSender resolved = config.primary(List.of(logSender, smtpSender));

        assertThat(resolved).isSameAs(smtpSender);
        assertThat(resolved.channel()).isEqualTo(NotificationChannel.SMTP);
    }
}
