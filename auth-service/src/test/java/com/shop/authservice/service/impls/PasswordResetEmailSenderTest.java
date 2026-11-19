package com.shop.authservice.service.impls;

import com.shop.common.core.exception.BusinessException;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The password reset email really leaves the process (F-054).
 *
 * <p>The previous sender only logged the dispatch, so the endpoint reported
 * "a password reset instruction has been sent" while no message ever left the
 * process.</p>
 */
class PasswordResetEmailSenderTest {

    @Test
    @DisplayName("the reset token reaches the mailbox of the account owner")
    void theResetTokenReachesTheMailbox() throws Exception {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        MimeMessage message = new MimeMessage((jakarta.mail.Session) null);
        when(mailSender.createMimeMessage()).thenReturn(message);

        var sender = new SmtpPasswordResetEmailSender(mailSender, "no-reply@shop.example",
                "https://shop.example/reset-password");

        sender.sendResetEmail("alice@example.com", "alice", "raw-token-123");

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        MimeMessage sent = captor.getValue();
        assertThat(sent.getAllRecipients()[0].toString()).isEqualTo("alice@example.com");
        assertThat(sent.getSubject()).isEqualTo("Password reset request");
        assertThat(sent.getContent().toString()).contains("raw-token-123")
                .contains("https://shop.example/reset-password?token=raw-token-123");
    }

    @Test
    @DisplayName("a failing dispatch is an error, not a warning")
    void aFailingDispatchIsAnError() throws Exception {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        MimeMessage message = new MimeMessage((jakarta.mail.Session) null);
        when(mailSender.createMimeMessage()).thenReturn(message);
        doThrow(new MailSendException("smtp down")).when(mailSender).send(any(MimeMessage.class));

        var sender = new SmtpPasswordResetEmailSender(mailSender, "no-reply@shop.example",
                "https://shop.example/reset-password");

        assertThatThrownBy(() -> sender.sendResetEmail("alice@example.com", "alice", "raw-token-123"))
                .isInstanceOf(MailSendException.class);
    }

    @Test
    @DisplayName("the sender refuses to start without a from address or a reset link base")
    void theSenderRefusesToStartWithoutItsConfiguration() {
        JavaMailSender mailSender = mock(JavaMailSender.class);

        assertThatThrownBy(() -> new SmtpPasswordResetEmailSender(mailSender, "", "https://shop.example"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("shop.auth.smtp.from");
        assertThatThrownBy(() -> new SmtpPasswordResetEmailSender(mailSender, "no-reply@shop.example", " "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("shop.auth.smtp.reset-base-url");
    }

    @Test
    @DisplayName("without a channel the reset request FAILS instead of claiming success")
    void withoutAChannelTheRequestFails() {
        var sender = new UnconfiguredPasswordResetEmailSender();

        assertThatThrownBy(() -> sender.sendResetEmail("alice@example.com", "alice", "raw-token-123"))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo("AUTH-1010");
                    assertThat(ex.getStatus().value()).isEqualTo(503);
                });
    }
}
