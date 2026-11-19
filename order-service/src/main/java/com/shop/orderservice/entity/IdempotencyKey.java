package com.shop.orderservice.entity;

import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "idempotency_keys")
@IdClass(IdempotencyKey.IdempotencyKeyId.class)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class IdempotencyKey {

    /**
     * Owner label (H-6): ADMIN sub, {@code service:<azp>}, or the customer sub —
     * a string by design so machine actors are never forced into a UUID shape.
     */
    @Id
    @Column(name = "actor", nullable = false)
    private String actor;

    @Id
    @Column(name = "key", nullable = false, length = 64)
    private String key;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    /** 0 = in-flight, 200/201 = complete. See spec §3.7. */
    @Column(name = "response_status", nullable = false)
    private Integer responseStatus;

    @Column(name = "response_body", nullable = false, columnDefinition = "TEXT")
    private String responseBody;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class IdempotencyKeyId implements Serializable {
        private String actor;
        private String key;

        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof IdempotencyKeyId that)) return false;
            return Objects.equals(actor, that.actor) && Objects.equals(key, that.key);
        }
        @Override public int hashCode() { return Objects.hash(actor, key); }
    }
}
