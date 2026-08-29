package com.shop.productservice.repository;

import com.shop.productservice.entity.OutboxEvent;
import com.shop.common.core.constants.OutboxStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    List<OutboxEvent> findByStatusOrderByIdAsc(OutboxStatus status, Pageable pageable);

    long countByStatus(OutboxStatus status);
}