package com.shop.orderservice.repository;

import com.shop.orderservice.entity.OrderReturn;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderReturnRepository extends JpaRepository<OrderReturn, UUID> {

    List<OrderReturn> findByOrderId(UUID orderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "10000")})
    @Query("SELECT r FROM OrderReturn r WHERE r.order.id = :orderId ORDER BY r.id")
    List<OrderReturn> findByOrderIdForUpdate(@Param("orderId") UUID orderId);

    Page<OrderReturn> findByUserId(UUID userId, Pageable pageable);

    Optional<OrderReturn> findByIdAndUserId(UUID id, UUID userId);
}
