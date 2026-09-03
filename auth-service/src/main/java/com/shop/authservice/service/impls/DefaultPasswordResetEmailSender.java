package com.shop.authservice.service.impls;

import com.shop.authservice.service.PasswordResetEmailSender;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class DefaultPasswordResetEmailSender implements PasswordResetEmailSender {

    @Override
    public void sendResetEmail(String email, String username, String resetToken) {
        log.info("Dispatched password reset instruction to email {} for username {}", email, username);
    }
}
