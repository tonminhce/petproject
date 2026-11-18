package com.shop.authservice.service.impls;

import com.shop.authservice.service.PasswordResetEmailSender;
import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "shop.auth.smtp.enabled", havingValue = "false", matchIfMissing = true)
public class UnconfiguredPasswordResetEmailSender implements PasswordResetEmailSender {

    @Override
    public void sendResetEmail(final String email, final String username, final String resetToken) {
        throw BusinessException.of(ErrorCode.AUTH_EMAIL_SEND_FAILED);
    }
}
