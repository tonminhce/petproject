package com.shop.mediaservice.service;

import java.util.UUID;

/**
 * D3 delete side — soft delete (deleted_at marker) of an uploaded media.
 *
 * <p><strong>Task-3 binding ruling:</strong> NO outbox row is written here.
 * The {@code MediaDeleted} emission wires up in Task 4, when the
 * {@code OutboxEvent} entity lands and the lifecycle write can carry the
 * event in the SAME transaction. Until then delete is marker-only.</p>
 */
public interface MediaLifecycleService {

    /**
     * Soft-deletes the media: a repeat delete of an already-deleted media is
     * 409 MED-12005 (not idempotent-success — the D3 contract), an unknown
     * id is 404 MED-12004. The row keeps its variants and S3 objects until
     * the {@code MediaPurgeJob} hard-purges them after the grace window.
     *
     * @throws com.shop.common.core.exception.BusinessException
     *         404 MED-12004 or 409 MED-12005
     */
    void softDelete(UUID mediaId);
}
