package com.shop.promotionservice.service;

import com.shop.common.core.constants.OutboxStatus;
import com.shop.common.core.exception.BusinessException;
import com.shop.promotionservice.constant.CampaignStatus;
import com.shop.promotionservice.constant.UsageStatus;
import com.shop.promotionservice.dto.request.ReserveRequest;
import com.shop.promotionservice.dto.response.ReservationResponse;
import com.shop.promotionservice.entity.Campaign;
import com.shop.promotionservice.entity.CouponUsageReservation;
import com.shop.promotionservice.entity.OutboxEvent;
import com.shop.promotionservice.repository.CampaignRepository;
import com.shop.promotionservice.repository.CouponUsageReservationRepository;
import com.shop.promotionservice.repository.OutboxEventRepository;
import com.shop.promotionservice.service.ReservationCleanupScheduler;
import com.shop.promotionservice.service.ReservationRetryService;
import com.shop.promotionservice.support.AbstractIntegrationTest;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Task 13 — lifecycle + events integration suite: the production entry path
 * ({@link ReservationRetryService} → transactional
 * {@code CampaignReservationServiceImpl}) against real Postgres, plus the
 * background machinery ({@link ReservationCleanupScheduler} retention purge,
 * {@link PromotionOutboxRelay} drain) and the full outbox → Kafka hop asserted
 * with a real {@link KafkaConsumer} on {@code shop.promotion.lifecycle.v1}
 * (Kafka container is live — no stubbing).
 *
 * <p>The context disables the 60s TTL-sweep timer (see
 * {@link #schedulerOverrides}): the sweep's first startup run happens on an
 * empty DB, and afterwards only {@link #commitAfterExpiryRejected7009}
 * depends on a PENDING row staying PENDING despite a past {@code expiresAt} —
 * the scheduled sweep must not race it into EXPIRED (PRO-7010) before the
 * commit call lands.</p>
 */
class LifecycleAndEventsIT extends AbstractIntegrationTest {

    private static final long TTL_SECONDS = 900L;
    private static final String TOPIC = "shop.promotion.lifecycle.v1";

    @DynamicPropertySource
    static void schedulerOverrides(DynamicPropertyRegistry registry) {
        // Neutralize the sweep's 60s fixedDelay timer so it cannot flip the
        // commit-after-expiry fixture from PENDING to EXPIRED mid-test (which
        // would surface as PRO-7010 instead of PRO-7009). The immediate first
        // run at context start is a no-op on an empty DB.
        registry.add("shop.promotion.reservation-cleanup-interval-ms", () -> "3600000");
        // Neutralize the @Scheduled outbox relay timer too: the test drains
        // manually below, and a background tick claiming the head row would
        // lock the manual relay() out via SKIP LOCKED (C14) mid-drain.
        registry.add("promotion.outbox.poll-interval-ms", () -> "3600000");
    }

    @Autowired
    private CampaignRepository campaignRepository;
    @Autowired
    private CouponUsageReservationRepository reservationRepository;
    @Autowired
    private OutboxEventRepository outboxRepository;
    @Autowired
    private ReservationRetryService reservationService;
    @Autowired
    private ReservationCleanupScheduler cleanupScheduler;
    @Autowired
    private PromotionOutboxRelay outboxRelay;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Bound through the harness's DynamicPropertySource — the singleton Kafka container. */
    @Value("${shop.kafka.bootstrap-servers}")
    private String bootstrapServers;

    // --- helpers -------------------------------------------------------------

    private Campaign seedCampaign(String code) {
        Instant now = Instant.now();
        return campaignRepository.save(Campaign.builder()
            .code(code)
            .name("IT campaign " + code)
            .discountType(DiscountCalculator.TYPE_FIXED)
            .discountValue(new BigDecimal("30.00"))
            .startsAt(now.minusSeconds(3600))
            .endsAt(now.plusSeconds(86_400))
            .perUserLimit(0)
            .status(CampaignStatus.ACTIVE)
            .build());
    }

    private ReservationResponse reserve(String code) {
        return reservationService.reserveWithRetry(code,
            new ReserveRequest(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("500.00")));
    }

    private CouponUsageReservation reload(UUID reservationId) {
        return reservationRepository.findById(reservationId).orElseThrow();
    }

    private List<String> outboxPayloads(String eventType) {
        return jdbcTemplate.queryForList(
            "select payload from outbox_events where event_type = ? order by id",
            String.class, eventType);
    }

    // --- 5. reserve → commit ---------------------------------------------------

    @Test
    @DisplayName("commit → COMMITTED + committedAt + committed outbox row")
    void commitPersistsCommittedStatusAndOutboxEvent() {
        seedCampaign("COMMIT-IT");
        ReservationResponse reserved = reserve("COMMIT-IT");

        reservationService.commitWithRetry(reserved.reservationId());

        CouponUsageReservation row = reload(reserved.reservationId());
        assertThat(row.getStatus()).isEqualTo(UsageStatus.COMMITTED);
        assertThat(row.getCommittedAt()).isNotNull();

        assertThat(outboxPayloads("promotion.committed.v1"))
            .anySatisfy(payload -> {
                assertThat(payload).contains(reserved.reservationId().toString());
                assertThat(payload).contains("\"eventType\":\"promotion.committed.v1\"");
                assertThat(payload).contains("\"committedAt\":\"");
            });
    }

    // --- 6. reserve → release (previousStatus=PENDING) -------------------------

    @Test
    @DisplayName("release → RELEASED + releasedAt + released event with previousStatus=\"PENDING\"")
    void releasePersistsReleasedStatusAndPendingPreviousStatus() {
        seedCampaign("RELEASE-IT");
        ReservationResponse reserved = reserve("RELEASE-IT");

        reservationService.releaseWithRetry(reserved.reservationId());

        CouponUsageReservation row = reload(reserved.reservationId());
        assertThat(row.getStatus()).isEqualTo(UsageStatus.RELEASED);
        assertThat(row.getReleasedAt()).isNotNull();

        assertThat(outboxPayloads("promotion.released.v1"))
            .anySatisfy(payload -> {
                assertThat(payload).contains(reserved.reservationId().toString());
                assertThat(payload).contains("\"previousStatus\":\"PENDING\"");
            });
    }

    // --- 7. reserve → commit → releaseCommitted (previousStatus=COMMITTED) -----

    @Test
    @DisplayName("releaseCommitted → RELEASED + released event with previousStatus=\"COMMITTED\"")
    void releaseCommittedPersistsReleasedStatusAndCommittedPreviousStatus() {
        seedCampaign("ROLLBACK-IT");
        ReservationResponse reserved = reserve("ROLLBACK-IT");
        reservationService.commitWithRetry(reserved.reservationId());

        reservationService.releaseCommittedWithRetry(reserved.reservationId());

        CouponUsageReservation row = reload(reserved.reservationId());
        assertThat(row.getStatus()).isEqualTo(UsageStatus.RELEASED);
        assertThat(row.getReleasedAt()).isNotNull();

        assertThat(outboxPayloads("promotion.released.v1"))
            .anySatisfy(payload -> {
                assertThat(payload).contains(reserved.reservationId().toString());
                assertThat(payload).contains("\"previousStatus\":\"COMMITTED\"");
            });
    }

    // --- 8. commit-after-expiry -------------------------------------------------

    @Test
    @DisplayName("commit on expired PENDING reservation → PROMOTION_RESERVATION_EXPIRED (PRO-7009)")
    void commitAfterExpiryRejected7009() {
        seedCampaign("EXPIRY-IT");
        ReservationResponse reserved = reserve("EXPIRY-IT");

        // Rewind the TTL directly through the real repository — the sweep timer
        // is disabled for this context, so the row stays PENDING (the state the
        // service itself re-checks on commit).
        CouponUsageReservation row = reload(reserved.reservationId());
        row.setExpiresAt(Instant.now().minusSeconds(1));
        reservationRepository.save(row);

        assertThatThrownBy(() -> reservationService.commitWithRetry(reserved.reservationId()))
            .isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo("PRO-7009"));

        // Fail-closed: no state change, no committed event.
        assertThat(reload(reserved.reservationId()).getStatus()).isEqualTo(UsageStatus.PENDING);
        assertThat(outboxPayloads("promotion.committed.v1"))
            .noneSatisfy(payload -> assertThat(payload)
                .contains(reserved.reservationId().toString()));
    }

    // --- 9. retention purge ------------------------------------------------------

    @Test
    @DisplayName("retention purge deletes terminal rows older than 30d, keeps recent terminal rows")
    void retentionPurgeDeletesOnlyOldTerminalRows() {
        // FK fk_cur_campaign: usage rows must reference a persisted campaign.
        Campaign campaign = seedCampaign("RETENTION-IT");
        UUID campaignId = campaign.getId();
        Instant now = Instant.now();
        CouponUsageReservation oldReleased = seedTerminal(campaignId, UsageStatus.RELEASED,
            now.minusSeconds(40L * 86_400), now.minusSeconds(40L * 86_400));
        CouponUsageReservation recentReleased = seedTerminal(campaignId, UsageStatus.RELEASED,
            now.minusSeconds(2L * 86_400), now.minusSeconds(86_400));
        // EXPIRED rows have no releasedAt — effective end falls back to reservedAt.
        CouponUsageReservation oldExpired = seedTerminal(campaignId, UsageStatus.EXPIRED,
            now.minusSeconds(40L * 86_400), null);
        CouponUsageReservation recentExpired = seedTerminal(campaignId, UsageStatus.EXPIRED,
            now.minusSeconds(2L * 86_400), null);

        cleanupScheduler.purgeOldTerminalReservations();

        assertThat(reservationRepository.findById(oldReleased.getId())).isEmpty();
        assertThat(reservationRepository.findById(oldExpired.getId())).isEmpty();
        assertThat(reservationRepository.findById(recentReleased.getId())).isPresent();
        assertThat(reservationRepository.findById(recentExpired.getId())).isPresent();
    }

    private CouponUsageReservation seedTerminal(UUID campaignId, UsageStatus status,
                                                Instant reservedAt, Instant releasedAt) {
        CouponUsageReservation row = CouponUsageReservation.builder()
            .campaignId(campaignId)
            .userId(UUID.randomUUID())
            .orderId(UUID.randomUUID())
            .orderAmount(new BigDecimal("500.00"))
            .discountAmount(new BigDecimal("30.00"))
            .status(status)
            .reservedAt(reservedAt)
            .expiresAt(reservedAt.plusSeconds(TTL_SECONDS))
            .build();
        row.setReleasedAt(releasedAt);
        return reservationRepository.save(row);
    }

    // --- 10. outbox relay → real Kafka -------------------------------------------

    @Test
    @DisplayName("relay drains seeded PENDING row → SENT + record lands on shop.promotion.lifecycle.v1")
    void relayPublishesPendingOutboxRowToKafkaAndMarksItSent() {
        // event_id is varchar(36) — a plain UUID is the marker.
        String marker = UUID.randomUUID().toString();
        Campaign campaign = seedCampaign("RELAY-IT");
        OutboxEvent seeded = outboxRepository.save(OutboxEvent.builder()
            .eventId(marker)
            .aggregateType("Campaign")
            .aggregateId(campaign.getId())
            .eventType("promotion.reserved.v1")
            .topic(TOPIC)
            .payload("{\"eventId\":\"" + marker + "\","
                + "\"eventType\":\"promotion.reserved.v1\","
                + "\"code\":\"RELAY-IT\"}")
            .status(OutboxStatus.PENDING)
            .retryCount(0)
            .build());

        // Drain directly (same as product-service's relay IT — no timer wait).
        outboxRelay.relay();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            OutboxEvent reloaded = outboxRepository.findById(seeded.getId()).orElseThrow();
            assertThat(reloaded.getStatus()).isEqualTo(OutboxStatus.SENT);
            assertThat(reloaded.getSentAt()).isNotNull();
        });

        // Full consumer assert against the real Kafka container. The producer
        // JSON-encodes the payload String (quotes escaped), so unwrap the
        // backslashes before matching the raw payload fragments.
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "lifecycle-it-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
            org.apache.kafka.common.serialization.StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
            org.apache.kafka.common.serialization.StringDeserializer.class.getName());

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(TOPIC));
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                boolean delivered = false;
                for (var record : records.records(TOPIC)) {
                    if (record.key().equals(campaign.getId().toString())
                        && record.value().replace("\\", "").contains(marker)) {
                        delivered = true;
                    }
                }
                assertThat(delivered)
                    .as("expected the seeded outbox record (eventId=%s) on %s", marker, TOPIC)
                    .isTrue();
            });
        }
    }
}
