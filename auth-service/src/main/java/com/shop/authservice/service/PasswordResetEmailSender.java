package com.shop.authservice.service;

public interface PasswordResetEmailSender {

    void sendResetEmail(String email, String username, String resetToken);
}
