package com.shop.notificationservice.service.sender;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.notificationservice.constant.NotificationChannel;
import com.shop.notificationservice.entity.Notification;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final ObjectMapper objectMapper;

    @Autowired
    public SmtpNotificationSender(JavaMailSender mailSender,
                                  @Value("${shop.notification.smtp.fallback-recipient}") String fallbackRecipient,
                                  @Autowired(required = false) ObjectMapper objectMapper) {
        if (fallbackRecipient == null || fallbackRecipient.isBlank()) {
            throw new IllegalStateException(
                    "shop.notification.smtp.fallback-recipient must be configured when "
                            + "shop.notification.smtp.enabled=true (set SHOP_NOTIFICATION_SMTP_FALLBACK_RECIPIENT)");
        }
        this.mailSender = mailSender;
        this.fallbackRecipient = fallbackRecipient;
        this.objectMapper = (objectMapper != null) ? objectMapper : new ObjectMapper();
    }

    public SmtpNotificationSender(JavaMailSender mailSender, String fallbackRecipient) {
        this(mailSender, fallbackRecipient, new ObjectMapper());
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
            String recipient = resolveRecipient(n);
            helper.setTo(recipient);
            helper.setSubject(n.getSubject());
            helper.setText(n.getBody(), false);
            mailSender.send(message);
        } catch (MessagingException | MailException e) {
            throw new IllegalStateException("Failed to send SMTP notification", e);
        }
    }

    private String resolveRecipient(Notification n) {
        if (n.getPayload() != null && !n.getPayload().isBlank()) {
            try {
                JsonNode node = objectMapper.readTree(n.getPayload());
                if (node.hasNonNull("recipientEmail")) {
                    String email = node.get("recipientEmail").asText();
                    if (!email.isBlank()) {
                        return email;
                    }
                }
                if (node.hasNonNull("email")) {
                    String email = node.get("email").asText();
                    if (!email.isBlank()) {
                        return email;
                    }
                }
            } catch (Exception ignored) {
                // fall back to fallbackRecipient
            }
        }
        return fallbackRecipient;
    }
}
