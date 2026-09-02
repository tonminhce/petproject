package com.shop.mediaservice.outbox;

import com.shop.common.core.constants.OutboxStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    List<OutboxEvent> findByStatusOrderByIdAsc(OutboxStatus status, Pageable pageable);

    List<OutboxEvent> findByStatusInOrderByIdAsc(Collection<OutboxStatus> statuses, Pageable pageable);

    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM OutboxEvent e WHERE e.status = :status AND e.sentAt < :cutoff")
    int deleteByStatusAndSentAtBefore(@Param("status") OutboxStatus status, @Param("cutoff") Instant cutoff);

    /**
     * H-5 relay aging: flips every FAILED row whose {@code failed_at} is past
     * the terminal window to DEAD (one bulk UPDATE — DEAD is terminal, the
     * relay never polls it again).
     *
     * @return number of rows aged to DEAD (drives the WARN + dead meter)
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE OutboxEvent e SET e.status = com.shop.common.core.constants.OutboxStatus.DEAD "
            + "WHERE e.status = com.shop.common.core.constants.OutboxStatus.FAILED AND e.failedAt < :cutoff")
    int ageDeadFailedBefore(@Param("cutoff") Instant cutoff);
}
