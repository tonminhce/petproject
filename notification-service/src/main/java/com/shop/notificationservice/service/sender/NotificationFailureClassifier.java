package com.shop.notificationservice.service.sender;

import jakarta.mail.internet.AddressException;
import org.springframework.mail.MailSendException;
import org.springframework.stereotype.Component;

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
