package com.shop.notificationservice.service.sender;

import jakarta.mail.internet.AddressException;
import org.springframework.mail.MailSendException;
import org.springframework.stereotype.Component;

/**
 * C17 — maps a thrown delivery failure to {@link NotificationFailureKind}.
 *
 * <p>PERMANENT means the message can never be delivered to these recipients:
 * an {@link AddressException} (syntax/unknown address) anywhere in the cause
 * chain, or the JavaMail invalid-recipient aggregate — providers throw
 * SendFailedException/MailSendException carrying the "Invalid Addresses"
 * text when RCPT TO is rejected with a 5xx (user unknown). Everything else —
 * connection drops, timeouts, DNS, auth glitches, unexpected runtime errors —
 * is TRANSIENT and belongs to the bounded retry path. The SMTP sender wraps
 * failures in {@link IllegalStateException}, so the chain walk is mandatory.</p>
 */
@Component
public class NotificationFailureClassifier {

    private static final String INVALID_ADDRESSES = "invalid addresses";
    private static final int MAX_CHAIN_DEPTH = 16;

    public NotificationFailureKind classify(Throwable failure) {
        Throwable t = failure;
        int depth = 0;
        while (t != null && depth++ < MAX_CHAIN_DEPTH) {
            if (t instanceof AddressException) {
                return NotificationFailureKind.PERMANENT;
            }
            if (t.getMessage() != null && t.getMessage().toLowerCase().contains(INVALID_ADDRESSES)) {
                return NotificationFailureKind.PERMANENT;
            }
            t = t.getCause() == t ? null : t.getCause();
        }
        return NotificationFailureKind.TRANSIENT;
    }
}
