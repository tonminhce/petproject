package com.shop.paymentservice.entity;

import com.shop.common.core.data.AbstractMappedEntity;
import com.shop.paymentservice.constant.PaymentStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SQLRestriction;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "payments")
@SQLRestriction("deleted = false")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Payment extends AbstractMappedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    // Types.CHAR so ddl-auto=validate matches Liquibase's char(3) column (Postgres bpchar,
    // Types#CHAR) — Hibernate derives the validation type from the mapping's JDBC code,
    // not from columnDefinition, which would expect VARCHAR and fail at boot.
    @JdbcTypeCode(java.sql.Types.CHAR)
    @Column(name = "currency", nullable = false, columnDefinition = "char(3)")
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private PaymentStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_status", length = 16)
    private PaymentStatus previousStatus;

    @Column(name = "provider", nullable = false, length = 16)
    private String provider;

    @Column(name = "idempotency_key", nullable = false, length = 64)
    private String idempotencyKey;

    @Column(name = "receipt_key")
    private String receiptKey;

    @Version
    @Column(name = "version")
    @Builder.Default
    private Long version = 0L;
}
