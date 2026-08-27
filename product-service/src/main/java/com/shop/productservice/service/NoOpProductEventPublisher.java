package com.shop.productservice.service;

import com.shop.productservice.entity.Product;
import org.springframework.stereotype.Service;

/**
 * Placeholder publisher used until the transactional outbox relay ships (Task 26).
 * Replace with {@code TransactionalProductEventPublisher} once OutboxEventRepository is wired.
 */
@Service
public class NoOpProductEventPublisher implements ProductEventPublisher {

    @Override public void publishCreated(Product product) {}
    @Override public void publishUpdated(Product product) {}
    @Override public void publishDeleted(Product product) {}
}