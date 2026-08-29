package com.shop.orderservice.repository;

import com.shop.orderservice.entity.IdempotencyKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface IdempotencyKeyRepository
        extends JpaRepository<IdempotencyKey, IdempotencyKey.IdempotencyKeyId> {

    Optional<IdempotencyKey> findByUserIdAndKey(UUID userId, String key);

    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM IdempotencyKey ik WHERE ik.expiresAt < :cutoff")
    int deleteByExpiresAtBefore(@Param("cutoff") Instant cutoff);
}
