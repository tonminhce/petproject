package com.shop.mediaservice.service.impls;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.common.security.jwt.AuthenticatedUser;
import com.shop.mediaservice.repository.MediaRepository;
import com.shop.mediaservice.service.MediaLifecycleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Soft delete via the repository's conditional native UPDATE: the row flips
 * {@code deleted} only if still live, so the 0-rows outcome IS the
 * already-deleted conflict (409 MED-12005) — no load-then-mark race window.
 *
 * <p><strong>Task-3 binding ruling:</strong> this method writes NO outbox
 * row. {@code MediaDeleted} emission lands in Task 4 together with the
 * {@code OutboxEvent} entity, wired same-transaction. {@code deleted_at}
 * stamped by the UPDATE is what the {@code MediaPurgeJob} measures the
 * grace window against.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MediaLifecycleServiceImpl implements MediaLifecycleService {

    private final MediaRepository mediaRepository;

    @Override
    @Transactional
    public void softDelete(UUID mediaId) {
        if (!mediaRepository.existsIncludingDeleted(mediaId)) {
            throw BusinessException.of(ErrorCode.MEDIA_NOT_FOUND);
        }
        String actor = AuthenticatedUser.current()
                .map(AuthenticatedUser::username)
                .orElse("system");
        int deleted = mediaRepository.softDelete(mediaId, actor);
        if (deleted == 0) {
            log.info("Delete rejected: media {} is already soft-deleted", mediaId);
            throw BusinessException.of(ErrorCode.MEDIA_ALREADY_DELETED);
        }
        log.info("Media {} soft-deleted by {} — purgeable after the grace window", mediaId, actor);
    }
}
