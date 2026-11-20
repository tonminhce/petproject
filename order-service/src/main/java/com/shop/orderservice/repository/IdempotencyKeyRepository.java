package com.shop.orderservice.repository;

import com.shop.orderservice.entity.IdempotencyKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface IdempotencyKeyRepository
        extends JpaRepository<IdempotencyKey, IdempotencyKey.IdempotencyKeyId> {

    Optional<IdempotencyKey> findByActorAndKey(String actor, String key);

    @Modifying(clearAutomatically = true)
    @Query(value = "INSERT INTO idempotency_keys "
            + "(actor, key, request_hash, response_status, response_body, created_at, expires_at) "
            + "VALUES (:actor, :key, :requestHash, 0, '', now(), :expiresAt) "
            + "ON CONFLICT (actor, key) DO NOTHING", nativeQuery = true)
    int insertIfAbsent(@Param("actor") String actor,
                       @Param("key") String key,
                       @Param("requestHash") String requestHash,
                       @Param("expiresAt") Instant expiresAt);

    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM IdempotencyKey ik WHERE ik.expiresAt < :cutoff")
    int deleteByExpiresAtBefore(@Param("cutoff") Instant cutoff);
}
