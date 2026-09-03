package com.shop.notificationservice.service.sender;

import com.shop.notificationservice.constant.NotificationChannel;
import com.shop.notificationservice.entity.Notification;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
@ConditionalOnProperty(name = "shop.notification.smtp.enabled", havingValue = "true")
public class SmtpNotificationSender implements NotificationSender {

    private final JavaMailSender mailSender;
    private final String fallbackRecipient;

    public SmtpNotificationSender(JavaMailSender mailSender,
                                  @Value("${shop.notification.smtp.fallback-recipient}") String fallbackRecipient) {
        // H22 — fail-fast on a missing/blank property. Spring's @Value without
        // a default would still throw BeanCreationException when the property
        // is unset, but an explicitly empty string (e.g. SHOP_NOTIFICATION_SMTP_FALLBACK_RECIPIENT=)
        // would silently resolve to "" and the SMTP path would dispatch to an
        // empty recipient. This guard catches that case at construction time
        // before the bean enters the application context. See Spring's
        // @ConfigurationProperties / @Value failure-mode docs:
        // https://docs.spring.io/spring-framework/reference/core/beans/annotation-config/value-annotations.html
        if (fallbackRecipient == null || fallbackRecipient.isBlank()) {
            throw new IllegalStateException(
                    "shop.notification.smtp.fallback-recipient must be configured when "
                            + "shop.notification.smtp.enabled=true (set SHOP_NOTIFICATION_SMTP_FALLBACK_RECIPIENT)");
        }
        this.mailSender = mailSender;
        this.fallbackRecipient = fallbackRecipient;
    }

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.SMTP;
    }

    @Override
    public void send(Notification n) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setTo(fallbackRecipient);
            helper.setSubject(n.getSubject());
            helper.setText(n.getBody(), false);
            mailSender.send(message);
        } catch (MessagingException | MailException e) {
            throw new IllegalStateException("Failed to send SMTP notification", e);
        }
    }
}
