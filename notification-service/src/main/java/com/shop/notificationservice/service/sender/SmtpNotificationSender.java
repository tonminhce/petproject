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
