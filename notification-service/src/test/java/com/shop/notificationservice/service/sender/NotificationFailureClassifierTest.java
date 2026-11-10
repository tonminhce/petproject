package com.shop.notificationservice.service.sender;

import jakarta.mail.internet.AddressException;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;

import java.io.IOException;
import java.net.UnknownHostException;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationFailureClassifierTest {

    private final NotificationFailureClassifier classifier = new NotificationFailureClassifier();

    @Test
    void addressExceptionInChain_isPermanent() {
        Exception e = new IllegalStateException("Failed to send SMTP notification",
                new MailSendException("Failed messages: jakarta.mail.SendFailedException",
                        new AddressException("Illegal address")));

        assertThat(classifier.classify(e)).isEqualTo(NotificationFailureKind.PERMANENT);
    }

    @Test
    void sendFailedExceptionInvalidAddresses_isPermanent_unknownRecipient() {
        // JavaMail aggregates rejected RCPT TO (550 user unknown) into a
        // SendFailedException "Invalid Addresses" — the canonical
        // unknown-recipient signal.
        Exception e = new IllegalStateException("Failed to send SMTP notification",
                new MailSendException("Failed messages: jakarta.mail.SendFailedException: Invalid Addresses"));

        assertThat(classifier.classify(e)).isEqualTo(NotificationFailureKind.PERMANENT);
    }

    @Test
    void mailSendExceptionInvalidAddressesText_isPermanent() {
        Exception e = new IllegalStateException("wrapper",
                new MailSendException("Invalid Addresses"));

        assertThat(classifier.classify(e)).isEqualTo(NotificationFailureKind.PERMANENT);
    }

    @Test
    void mailSendExceptionWithoutAddressSignal_isTransient() {
        Exception e = new IllegalStateException("wrapper",
                new MailSendException("Mail server connection failed"));

        assertThat(classifier.classify(e)).isEqualTo(NotificationFailureKind.TRANSIENT);
    }

    @Test
    void connectionFailure_isTransient() {
        Exception e = new IllegalStateException("wrapper", new MailSendException(
                "Mail server connection failed", new UnknownHostException("smtp.example.com")));

        assertThat(classifier.classify(e)).isEqualTo(NotificationFailureKind.TRANSIENT);
    }

    @Test
    void authenticationFailure_isTransient() {
        Exception e = new IllegalStateException("wrapper", new MailAuthenticationException("535 auth failed"));

        assertThat(classifier.classify(e)).isEqualTo(NotificationFailureKind.TRANSIENT);
    }

    @Test
    void plainIoFailure_isTransient() {
        Exception e = new IllegalStateException("wrapper", new IOException("socket timeout"));

        assertThat(classifier.classify(e)).isEqualTo(NotificationFailureKind.TRANSIENT);
    }

    @Test
    void bareRuntimeException_isTransient() {
        assertThat(classifier.classify(new RuntimeException("smtp down")))
                .isEqualTo(NotificationFailureKind.TRANSIENT);
    }

    @Test
    void nullFailure_isTransient() {
        assertThat(classifier.classify(null)).isEqualTo(NotificationFailureKind.TRANSIENT);
    }
}
