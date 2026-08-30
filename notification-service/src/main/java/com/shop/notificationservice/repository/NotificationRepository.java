package com.shop.notificationservice.repository;

import com.shop.notificationservice.entity.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    boolean existsByEventId(UUID eventId);

    Page<Notification> findAllByOrderIdOrderByCreatedAtDesc(UUID orderId, Pageable pageable);

    Optional<Notification> findById(UUID id);
}
