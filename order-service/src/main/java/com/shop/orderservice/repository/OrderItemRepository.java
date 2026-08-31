package com.shop.orderservice.repository;

import com.shop.orderservice.entity.OrderItem;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface OrderItemRepository extends JpaRepository<OrderItem, UUID> {

    List<OrderItem> findByOrderId(UUID orderId);

    /** Batch loader for paginated reads (avoids the per-order N+1). */
    List<OrderItem> findByOrderIdIn(Collection<UUID> orderIds);

    /**
     * Rating-eligibility probe (Task 7, rating-service epic): items of the given
     * product whose parent order is the user's, DELIVERED, and not soft-deleted.
     */
    @Query("""
        SELECT i FROM OrderItem i
        WHERE i.productId = :productId
          AND EXISTS (SELECT o FROM Order o WHERE o.id = i.orderId
                      AND o.userId = :userId AND o.status = com.shop.orderservice.constant.OrderStatus.DELIVERED
                      AND o.deleted = false)
        ORDER BY i.id
        """)
    Page<OrderItem> findDeliveredByUserAndProduct(@Param("userId") UUID userId,
        @Param("productId") UUID productId, Pageable pageable);
}
