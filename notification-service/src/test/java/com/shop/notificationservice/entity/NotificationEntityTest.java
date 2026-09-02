package com.shop.notificationservice.entity;

import org.hibernate.annotations.SQLRestriction;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C16 — fleet soft-delete convention: every business entity extending
 * {@code AbstractMappedEntity} carries {@code @SQLRestriction("deleted = false")}
 * (Payment, Order, Media, User, …) so Hibernate auto-filters tombstoned rows.
 * Notification was the only entity missing it.
 */
class NotificationEntityTest {

    @Test
    void carriesFleetSoftDeleteRestriction() {
        SQLRestriction restriction = Notification.class.getAnnotation(SQLRestriction.class);

        assertThat(restriction).isNotNull();
        assertThat(restriction.value()).isEqualTo("deleted = false");
    }
}
