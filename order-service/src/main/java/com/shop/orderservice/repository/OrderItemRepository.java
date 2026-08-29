package com.shop.orderservice.repository;

import com.shop.orderservice.entity.OrderItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface OrderItemRepository extends JpaRepository<OrderItem, UUID> {

    List<OrderItem> findByOrderId(UUID orderId);

    /** Batch loader for paginated reads (avoids the per-order N+1). */
    List<OrderItem> findByOrderIdIn(Collection<UUID> orderIds);
}
