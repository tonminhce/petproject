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

/**
 * H22 — fail-fast: misconfigured deploys must NOT silently fall through to
 * a hardcoded default recipient. Spring's {@code @Value} without a default
 * would already fail bean instantiation when the property is absent, but a
 * blank value (e.g. {@code SHOP_NOTIFICATION_SMTP_FALLBACK_RECIPIENT=}) would
 * silently resolve to empty and the SMTP sender would dispatch to "". The
 * explicit guard below catches that case at construction time, before the
 * bean enters the application context.
 */
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
    void send_usesRecipientEmailFromPayloadWhenPresent() throws Exception {
        MimeMessage message = messageFactory.createMimeMessage();
        given(mailSender.createMimeMessage()).willReturn(message);

        Notification n = Notification.builder()
                .subject("Order 1111 created")
                .body("status=NEW, items=2")
                .payload("{\"recipientEmail\":\"customer@example.com\"}")
                .build();
        sender.send(n);

        verify(mailSender).send(message);
        assertThat(message.getAllRecipients()[0].toString()).isEqualTo("customer@example.com");
    }

    @Test
    void sendFailure_wrapsInIllegalState() {
        given(mailSender.createMimeMessage()).willReturn(messageFactory.createMimeMessage());
        willThrow(new MailSendException("smtp down")).given(mailSender).send(any(MimeMessage.class));

        assertThatThrownBy(() -> sender.send(notification()))
                .isInstanceOf(IllegalStateException.class)
                .hasCauseInstanceOf(MailSendException.class);
    }

    @Test
    void constructor_nullFallbackRecipient_rejects() {
        assertThatThrownBy(() -> new SmtpNotificationSender(mailSender, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("shop.notification.smtp.fallback-recipient");
    }

    @Test
    void constructor_blankFallbackRecipient_rejects() {
        assertThatThrownBy(() -> new SmtpNotificationSender(mailSender, "   "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("shop.notification.smtp.fallback-recipient");
    }
}
