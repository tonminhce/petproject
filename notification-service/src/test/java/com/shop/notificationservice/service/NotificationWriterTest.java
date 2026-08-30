package com.shop.notificationservice.service;

import com.shop.notificationservice.constant.NotificationStatus;
import com.shop.notificationservice.entity.Notification;
import com.shop.notificationservice.repository.NotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationWriterTest {

    @Mock NotificationRepository repository;

    private NotificationWriter writer;

    @BeforeEach
    void setUp() {
        writer = new NotificationWriter(repository);
    }

    @Test
    void insert_flushesAndReturnsSavedRow() {
        Notification notification = Notification.builder().eventId(UUID.randomUUID()).build();
        when(repository.saveAndFlush(notification)).thenReturn(notification);

        Notification saved = writer.insert(notification);

        assertThat(saved).isSameAs(notification);
    }

    @Test
    void insert_duplicateRace_translatesToDataIntegrityViolation() {
        when(repository.saveAndFlush(any(Notification.class)))
                .thenThrow(new DataIntegrityViolationException("uk_notification_event_id"));

        assertThatThrownBy(() -> writer.insert(Notification.builder().build()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void markFailed_setsStatusAndSaves() {
        UUID id = UUID.randomUUID();
        Notification notification = Notification.builder().id(id).build();
        when(repository.findById(id)).thenReturn(Optional.of(notification));

        writer.markFailed(id);

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.FAILED);
        verify(repository).save(notification);
    }
}
