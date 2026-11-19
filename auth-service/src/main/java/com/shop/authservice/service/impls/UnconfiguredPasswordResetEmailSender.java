package com.shop.authservice.service.impls;

import com.shop.authservice.service.PasswordResetEmailSender;
import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * No SMTP channel is configured (F-054).
 *
 * <p>The previous implementation of this port only logged the dispatch, so
 * the endpoint reported "a password reset instruction has been sent" while no
 * message ever left the process. Without a channel the honest answer is a
 * failure: the caller is told that the reset could not be dispatched, and
 * the reset token that was generated for this request is rolled back with the
 * transaction.</p>
 */
@Component
@ConditionalOnProperty(name = "shop.auth.smtp.enabled", havingValue = "false", matchIfMissing = true)
public class UnconfiguredPasswordResetEmailSender implements PasswordResetEmailSender {

    @Override
    public void sendResetEmail(final String email, final String username, final String resetToken) {
        throw BusinessException.of(ErrorCode.AUTH_EMAIL_SEND_FAILED);
    }
}
