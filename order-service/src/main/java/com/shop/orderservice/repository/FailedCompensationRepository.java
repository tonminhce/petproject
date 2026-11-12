package com.shop.orderservice.repository;

import com.shop.orderservice.entity.FailedCompensation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FailedCompensationRepository extends JpaRepository<FailedCompensation, UUID> {

    Optional<FailedCompensation> findFirstByReservationIdAndKindAndStatus(
            UUID reservationId, String kind, String status);

    List<FailedCompensation> findTop50ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
            String status, Instant now);
}
