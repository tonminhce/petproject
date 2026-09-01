package com.shop.mediaservice.service.impls;

import com.shop.common.core.exception.BusinessException;
import com.shop.mediaservice.entity.Media;
import com.shop.mediaservice.outbox.MediaEventService;
import com.shop.mediaservice.outbox.MediaEventType;
import com.shop.mediaservice.repository.MediaRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Delete guard unit proofs: unknown id → 404 MED-12004, repeat delete (the
 * conditional UPDATE returns 0 rows) → 409 MED-12005, fresh delete → ok +
 * MediaDeleted outbox row in the same tx (D4). The DB-level proof (real
 * conditional UPDATE + deleted_at stamp) lives in {@code MediaControllerIT};
 * the same-tx outbox proof lives in {@code MediaOutboxIT}.
 */
@ExtendWith(MockitoExtension.class)
class MediaLifecycleServiceImplTest {

    @Mock
    private MediaRepository mediaRepository;

    @Mock
    private MediaEventService mediaEvents;

    @InjectMocks
    private MediaLifecycleServiceImpl lifecycleService;

    private static final UUID MEDIA_ID = UUID.fromString("e1000000-0000-0000-0000-000000000001");

    @Test
    void softDelete_unknownMedia_throws404() {
        when(mediaRepository.existsIncludingDeleted(MEDIA_ID)).thenReturn(false);

        assertThatThrownBy(() -> lifecycleService.softDelete(MEDIA_ID))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo("MED-12004");
                    assertThat(ex.getStatus().value()).isEqualTo(404);
                });
        verify(mediaRepository).existsIncludingDeleted(MEDIA_ID);
        verify(mediaEvents, never()).record(any(), any());
    }

    @Test
    void softDelete_liveMedia_updatesRowWithActorAndRecordsDeletedEvent() {
        when(mediaRepository.existsIncludingDeleted(MEDIA_ID)).thenReturn(true);
        when(mediaRepository.findById(MEDIA_ID)).thenReturn(Optional.of(Media.builder().id(MEDIA_ID).build()));
        when(mediaRepository.softDelete(MEDIA_ID, "system")).thenReturn(1);

        assertThatCode(() -> lifecycleService.softDelete(MEDIA_ID)).doesNotThrowAnyException();

        verify(mediaRepository).softDelete(MEDIA_ID, "system");
        verify(mediaEvents).record(any(Media.class), eq(MediaEventType.MediaDeleted));
    }

    @Test
    void softDelete_repeatDelete_zeroRowsMeans409_noEvent() {
        when(mediaRepository.existsIncludingDeleted(MEDIA_ID)).thenReturn(true);
        when(mediaRepository.findById(MEDIA_ID)).thenReturn(Optional.of(Media.builder().id(MEDIA_ID).build()));
        when(mediaRepository.softDelete(MEDIA_ID, "system")).thenReturn(0);

        assertThatThrownBy(() -> lifecycleService.softDelete(MEDIA_ID))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo("MED-12005");
                    assertThat(ex.getStatus().value()).isEqualTo(409);
                });
        verify(mediaEvents, never()).record(any(), any());
    }

    @Test
    void softDelete_racedConcurrentDelete_loadGoneMeans409_noEvent() {
        // Row soft-deleted between the exists-check and the load — @SQLRestriction
        // hides it, the snapshot load comes back empty: same 409 conflict.
        when(mediaRepository.existsIncludingDeleted(MEDIA_ID)).thenReturn(true);
        when(mediaRepository.findById(MEDIA_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> lifecycleService.softDelete(MEDIA_ID))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo("MED-12005"));
        verify(mediaRepository, never()).softDelete(any(UUID.class), any(String.class));
        verify(mediaEvents, never()).record(any(), any());
    }
}
