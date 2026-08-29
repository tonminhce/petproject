package com.shop.orderservice.repository;

import com.shop.orderservice.entity.Cart;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CartRepository extends JpaRepository<Cart, UUID> {

    Optional<Cart> findByUserIdAndDeletedFalse(UUID userId);

    Optional<Cart> findByIdAndUserIdAndDeletedFalse(UUID id, UUID userId);
}
