package com.shop.orderservice.repository;

import com.shop.orderservice.entity.OrderReturn;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderReturnRepository extends JpaRepository<OrderReturn, UUID> {

    List<OrderReturn> findByOrderId(UUID orderId);

    Page<OrderReturn> findByUserId(UUID userId, Pageable pageable);

    Optional<OrderReturn> findByIdAndUserId(UUID id, UUID userId);
}
