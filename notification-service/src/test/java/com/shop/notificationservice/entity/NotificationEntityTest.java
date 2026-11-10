package com.shop.notificationservice.entity;

import org.hibernate.annotations.SQLRestriction;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationEntityTest {

    @Test
    void carriesFleetSoftDeleteRestriction() {
        SQLRestriction restriction = Notification.class.getAnnotation(SQLRestriction.class);

        assertThat(restriction).isNotNull();
        assertThat(restriction.value()).isEqualTo("deleted = false");
    }
}
