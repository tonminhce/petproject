package com.shop.orderservice.service;

/**
 * Result of {@link OrderCommitCoordinator#commitForConfirm}. SUCCESS or throws —
 * never PARTIAL: compensations are best-effort, failures counted on the
 * {@code order.confirm.commit.outcome{result=rollback_failed}} meter (hardening §5.2).
 */
public enum CommitOutcome { SUCCESS }
