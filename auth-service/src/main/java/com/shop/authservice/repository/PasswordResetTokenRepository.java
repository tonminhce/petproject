package com.shop.authservice.repository;

import com.shop.authservice.entity.PasswordResetToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, UUID> {

    Optional<PasswordResetToken> findByTokenHashAndUsedFalse(String tokenHash);

    void deleteByUserId(UUID userId);

    /**
     * Consumes the token atomically.
     *
     * <p>F-053: the token used to be marked used AFTER the password was
     * changed, so two concurrent redemptions both passed the
     * {@code used = false} read, both changed the password and the second
     * silently replaced the password the owner had just chosen. This update
     * only succeeds for the caller that wins the race: exactly one redemption
     * can move the row from {@code used = false} to {@code used = true}.</p>
     *
     * @return the number of rows consumed - 1 for the winner, 0 for the loser
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update PasswordResetToken t set t.used = true where t.tokenHash = :tokenHash and t.used = false")
    @Transactional
    int consumeIfUnused(@Param("tokenHash") String tokenHash);
}
