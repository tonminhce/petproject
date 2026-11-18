package com.shop.notificationservice.repository;

import com.shop.notificationservice.constant.NotificationStatus;
import com.shop.notificationservice.entity.Notification;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    boolean existsByEventId(UUID eventId);

    Page<Notification> findAllByOrderIdOrderByCreatedAtDesc(UUID orderId, Pageable pageable);

    Page<Notification> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Optional<Notification> findById(UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select n from Notification n
            where (n.status in :statuses and n.nextRetryAt <= :now)
               or n.status = :legacyFailed
            order by n.nextRetryAt asc
            """)
    List<Notification> findRetryCandidates(@Param("now") Instant now,
                                           @Param("statuses") Collection<NotificationStatus> statuses,
                                           @Param("legacyFailed") NotificationStatus legacyFailed,
                                           Pageable pageable);
}
