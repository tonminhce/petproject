package com.shop.orderservice.service;

/**
 * Result of {@link OrderCommitCoordinator#commitForConfirm}. SUCCESS or throws —
 * never PARTIAL: compensations are best-effort, failures counted on the
 * {@code order.commit.rollback.failed} meter (hardening §5.2).
 */
public enum CommitOutcome { SUCCESS }
