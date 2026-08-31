package com.shop.paymentservice.repository;

import com.shop.paymentservice.entity.PaymentEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PaymentEventRepository extends JpaRepository<PaymentEvent, UUID> {

    boolean existsByProviderAndProviderEventId(String provider, String providerEventId);
}
