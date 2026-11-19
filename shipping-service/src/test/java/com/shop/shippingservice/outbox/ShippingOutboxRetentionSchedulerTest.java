package com.shop.shippingservice.outbox;

import com.shop.common.core.constants.OutboxStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShippingOutboxRetentionSchedulerTest {

    @Mock
    private OutboxEventRepository outboxRepository;

    @Test
    void sentAndDeadRowsAreBothPurgedAfterTheRetentionWindow() {
        when(outboxRepository.deleteByStatusAndSentAtBefore(eq(OutboxStatus.SENT), any(Instant.class)))
                .thenReturn(3);
        when(outboxRepository.deleteByStatusAndClaimedAtBefore(eq(OutboxStatus.DEAD), any(Instant.class)))
                .thenReturn(1);
        ShippingOutboxRetentionScheduler scheduler = new ShippingOutboxRetentionScheduler(outboxRepository);

        scheduler.purgeOldSentEvents();

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(outboxRepository).deleteByStatusAndSentAtBefore(eq(OutboxStatus.SENT), cutoff.capture());
        verify(outboxRepository).deleteByStatusAndClaimedAtBefore(eq(OutboxStatus.DEAD), any(Instant.class));
        assertThat(cutoff.getValue()).isBefore(Instant.now().minus(6, java.time.temporal.ChronoUnit.DAYS));
    }
}
