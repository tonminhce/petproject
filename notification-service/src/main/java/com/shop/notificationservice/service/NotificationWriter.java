package com.shop.notificationservice.service;

import com.shop.notificationservice.constant.NotificationStatus;
import com.shop.notificationservice.entity.Notification;
import com.shop.notificationservice.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class NotificationWriter {

    private final NotificationRepository repository;

    @Transactional
    public Notification insert(Notification notification) {
        return repository.saveAndFlush(notification);
    }

    @Transactional
    public void markFailed(UUID id) {
        repository.findById(id).ifPresent(notification -> {
            notification.setStatus(NotificationStatus.FAILED);
            repository.save(notification);
        });
    }
}
