package com.shop.mediaservice.service.impls;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.common.security.jwt.AuthenticatedUser;
import com.shop.mediaservice.entity.Media;
import com.shop.mediaservice.outbox.MediaEventService;
import com.shop.mediaservice.outbox.MediaEventType;
import com.shop.mediaservice.repository.MediaRepository;
import com.shop.mediaservice.service.MediaLifecycleService;
import com.shop.mediaservice.service.MediaReferenceChecker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class MediaLifecycleServiceImpl implements MediaLifecycleService {

    private final MediaRepository mediaRepository;
    private final MediaEventService mediaEvents;
    private final MediaReferenceChecker referenceChecker;

    @Override
    @Transactional
    public void softDelete(UUID mediaId) {
        if (!mediaRepository.existsIncludingDeleted(mediaId)) {
            throw BusinessException.of(ErrorCode.MEDIA_NOT_FOUND);
        }

        Media media = mediaRepository.findById(mediaId)
                .orElseThrow(() -> BusinessException.of(ErrorCode.MEDIA_ALREADY_DELETED));
        if (isReferenced(mediaId)) {
            log.info("Delete rejected: media {} is still referenced by a live product", mediaId);
            throw BusinessException.of(ErrorCode.MEDIA_IN_USE, mediaId);
        }
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

    private boolean isReferenced(UUID mediaId) {
        try {
            return referenceChecker.isReferenced(mediaId);
        } catch (RuntimeException checkerFailure) {
            log.warn("Reference check FAILED for media {} — FAIL-SAFE: treating as REFERENCED, delete refused",
                    mediaId, checkerFailure);
            return true;
        }
    }
}
