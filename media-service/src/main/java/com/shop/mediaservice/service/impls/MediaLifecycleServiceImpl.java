package com.shop.mediaservice.service.impls;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.common.security.jwt.AuthenticatedUser;
import com.shop.mediaservice.entity.Media;
import com.shop.mediaservice.outbox.MediaEventService;
import com.shop.mediaservice.outbox.MediaEventType;
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
 * <p><strong>D4 (Task 4):</strong> the row is loaded (pre-delete snapshot) and
 * a {@code MediaDeleted} outbox row is written by {@link MediaEventService} in
 * the SAME transaction as the {@code deleted_at} update — a 409 on the
 * conditional UPDATE writes no row, and any failure rolls both back.
 * {@code deleted_at} stamped by the UPDATE is what the {@code MediaPurgeJob}
 * measures the grace window against.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MediaLifecycleServiceImpl implements MediaLifecycleService {

    private final MediaRepository mediaRepository;
    private final MediaEventService mediaEvents;

    @Override
    @Transactional
    public void softDelete(UUID mediaId) {
        if (!mediaRepository.existsIncludingDeleted(mediaId)) {
            throw BusinessException.of(ErrorCode.MEDIA_NOT_FOUND);
        }
        // Pre-delete snapshot source: @SQLRestriction hides deleted rows, so a
        // concurrent delete between the exists-check and here surfaces as an
        // empty load — the same 409 conflict, just raced.
        Media media = mediaRepository.findById(mediaId)
                .orElseThrow(() -> BusinessException.of(ErrorCode.MEDIA_ALREADY_DELETED));
        String actor = AuthenticatedUser.current()
                .map(AuthenticatedUser::username)
                .orElse("system");
        int deleted = mediaRepository.softDelete(mediaId, actor);
        if (deleted == 0) {
            log.info("Delete rejected: media {} is already soft-deleted", mediaId);
            throw BusinessException.of(ErrorCode.MEDIA_ALREADY_DELETED);
        }
        mediaEvents.record(media, MediaEventType.MediaDeleted);
        log.info("Media {} soft-deleted by {} — purgeable after the grace window", mediaId, actor);
    }
}
