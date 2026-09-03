package com.shop.authservice.repository;

import com.shop.authservice.entity.PasswordResetToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, UUID> {

    Optional<PasswordResetToken> findByTokenHashAndUsedFalse(String tokenHash);

    void deleteByUserId(UUID userId);
}
