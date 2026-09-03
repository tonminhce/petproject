package com.shop.orderservice.repository;

import com.shop.orderservice.entity.Cart;
import org.springframework.data.jpa.repository.JpaRepository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CartRepository extends JpaRepository<Cart, UUID> {

    Optional<Cart> findByUserIdAndDeletedFalse(UUID userId);

    Optional<Cart> findByIdAndUserIdAndDeletedFalse(UUID id, UUID userId);

    @Query("SELECT c FROM Cart c WHERE c.updatedAt < :cutoff")
    List<Cart> findStaleCarts(@Param("cutoff") Instant cutoff);
}
