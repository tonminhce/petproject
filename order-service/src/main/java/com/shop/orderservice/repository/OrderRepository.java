package com.shop.orderservice.repository;

import com.shop.orderservice.constant.OrderStatus;
import com.shop.orderservice.entity.Order;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;
import java.util.List;
import java.time.Instant;

public interface OrderRepository extends JpaRepository<Order, UUID> {

    Page<Order> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    Page<Order> findByStatusOrderByCreatedAtDesc(OrderStatus status, Pageable pageable);

    Page<Order> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /**
     * Reconciliation candidates (hardening §6) — stuck PENDING orders older than
     * the cutoff. Served by the existing idx_orders_status_created (status, created_at)
     * — do NOT add a duplicate index.
     */
    List<Order> findByStatusAndCreatedAtBefore(OrderStatus status, Instant cutoff);

    /**
     * H38 — bounded chunk variant. The scheduler pulls at most {@code limit}
     * stuck orders per pass; a backlog larger than the limit is processed on
     * the next tick. Keeps memory and round-trips bounded.
     */
    List<Order> findByStatusAndCreatedAtBefore(OrderStatus status, Instant cutoff,
                                               Pageable pageable);

    long countByStatusAndCreatedAtBefore(OrderStatus status, Instant cutoff);
}
