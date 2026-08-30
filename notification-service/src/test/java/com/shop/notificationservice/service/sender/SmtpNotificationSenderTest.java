package com.shop.notificationservice.service.sender;

import com.shop.notificationservice.constant.NotificationChannel;
import com.shop.notificationservice.entity.Notification;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SmtpNotificationSenderTest {

    private static final String FALLBACK_RECIPIENT = "ops@example.com";

    private final JavaMailSender mailSender = mock(JavaMailSender.class);
    private final JavaMailSenderImpl messageFactory = new JavaMailSenderImpl();
    private final SmtpNotificationSender sender = new SmtpNotificationSender(mailSender, FALLBACK_RECIPIENT);

    private Notification notification() {
        return Notification.builder()
                .subject("Order 1111 created")
                .body("status=NEW, items=2")
                .build();
    }

    @Test
    void channel_isSmtp() {
        assertThat(sender.channel()).isEqualTo(NotificationChannel.SMTP);
    }

    @Test
    void send_buildsTextMessageToFallbackRecipient() throws Exception {
        MimeMessage message = messageFactory.createMimeMessage();
        given(mailSender.createMimeMessage()).willReturn(message);

        sender.send(notification());

        verify(mailSender).send(message);
        assertThat(message.getAllRecipients()[0].toString()).isEqualTo(FALLBACK_RECIPIENT);
        assertThat(message.getSubject()).isEqualTo("Order 1111 created");
        assertThat(message.getContent()).isEqualTo("status=NEW, items=2");
    }

    @Test
    void sendFailure_wrapsInIllegalState() {
        given(mailSender.createMimeMessage()).willReturn(messageFactory.createMimeMessage());
        willThrow(new MailSendException("smtp down")).given(mailSender).send(any(MimeMessage.class));

        assertThatThrownBy(() -> sender.send(notification()))
                .isInstanceOf(IllegalStateException.class)
                .hasCauseInstanceOf(MailSendException.class);
    }
}
