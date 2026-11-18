package com.shop.paymentservice.repository;

import com.shop.paymentservice.entity.PaymentEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentEventRepository extends JpaRepository<PaymentEvent, UUID> {

    boolean existsByProviderAndProviderEventId(String provider, String providerEventId);

    Optional<PaymentEvent> findFirstByProviderAndProviderEventId(String provider, String providerEventId);

    List<PaymentEvent> findByStatusAndNextRetryAtLessThanEqualOrderByNextRetryAtAsc(
            String status, Instant cutoff, Pageable pageable);
}
