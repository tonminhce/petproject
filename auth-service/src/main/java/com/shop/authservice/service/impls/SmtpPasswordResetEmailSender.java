package com.shop.authservice.service.impls;

import com.shop.authservice.service.PasswordResetEmailSender;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * The password reset email goes out over SMTP (F-054).
 *
 * <p>The previous sender only logged the dispatch, so the endpoint answered
 * "a password reset instruction has been sent" while no message ever left the
 * process - the flow was silently non-functional and the only signal was the
 * support load. The raw token is deliberately NOT logged; it only travels to
 * the mailbox of the account owner.</p>
 *
 * <p>A failed dispatch is an error, not a warning: the caller must not be
 * told that a message was sent when it was not.</p>
 */
@Component
@ConditionalOnProperty(name = "shop.auth.smtp.enabled", havingValue = "true")
public class SmtpPasswordResetEmailSender implements PasswordResetEmailSender {

    private final JavaMailSender mailSender;
    private final String from;
    private final String resetBaseUrl;

    public SmtpPasswordResetEmailSender(final JavaMailSender mailSender,
                                        final String from,
                                        final String resetBaseUrl) {
        if (from == null || from.isBlank()) {
            throw new IllegalStateException(
                    "shop.auth.smtp.from must be configured when shop.auth.smtp.enabled=true "
                            + "(set AUTH_MAIL_FROM)");
        }
        if (resetBaseUrl == null || resetBaseUrl.isBlank()) {
            throw new IllegalStateException(
                    "shop.auth.smtp.reset-base-url must be configured when shop.auth.smtp.enabled=true "
                            + "(set AUTH_RESET_BASE_URL)");
        }
        this.mailSender = mailSender;
        this.from = from;
        this.resetBaseUrl = resetBaseUrl;
    }

    @Override
    public void sendResetEmail(final String email, final String username, final String resetToken) {
        final String resetLink = resetBaseUrl + (resetBaseUrl.contains("?") ? "&" : "?") + "token=" + resetToken;
        try {
            final MimeMessage message = mailSender.createMimeMessage();
            final MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(from);
            helper.setTo(email);
            helper.setSubject("Password reset request");
            helper.setText(body(username, resetLink), false);
            mailSender.send(message);
        } catch (MessagingException e) {
            throw new MailSendException("Failed to build the password reset email", e);
        }
    }

    private String body(final String username, final String resetLink) {
        return "Hello " + username + ",\n\n"
                + "A password reset was requested for your account. Open the link below to set a new password.\n"
                + "The link is valid for 15 minutes and can be used once.\n\n"
                + resetLink + "\n\n"
                + "If you did not request this, you can ignore this message.\n";
    }
}
