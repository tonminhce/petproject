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

class LifecycleAndEventsIT extends AbstractIntegrationTest {

    private static final long TTL_SECONDS = 900L;
    private static final String TOPIC = "shop.promotion.lifecycle.v1";

    @DynamicPropertySource
    static void schedulerOverrides(DynamicPropertyRegistry registry) {
        registry.add("shop.promotion.reservation-cleanup-interval-ms", () -> "3600000");
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

    @Value("${shop.kafka.bootstrap-servers}")
    private String bootstrapServers;


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


    @Test
    @DisplayName("commit on expired PENDING reservation → PROMOTION_RESERVATION_EXPIRED (PRO-7009)")
    void commitAfterExpiryRejected7009() {
        seedCampaign("EXPIRY-IT");
        ReservationResponse reserved = reserve("EXPIRY-IT");

        CouponUsageReservation row = reload(reserved.reservationId());
        row.setExpiresAt(Instant.now().minusSeconds(1));
        reservationRepository.save(row);

        assertThatThrownBy(() -> reservationService.commitWithRetry(reserved.reservationId()))
            .isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo("PRO-7009"));

        assertThat(reload(reserved.reservationId()).getStatus()).isEqualTo(UsageStatus.PENDING);
        assertThat(outboxPayloads("promotion.committed.v1"))
            .noneSatisfy(payload -> assertThat(payload)
                .contains(reserved.reservationId().toString()));
    }


    @Test
    @DisplayName("retention purge deletes terminal rows older than 30d, keeps recent terminal rows")
    void retentionPurgeDeletesOnlyOldTerminalRows() {
        Campaign campaign = seedCampaign("RETENTION-IT");
        UUID campaignId = campaign.getId();
        Instant now = Instant.now();
        CouponUsageReservation oldReleased = seedTerminal(campaignId, UsageStatus.RELEASED,
            now.minusSeconds(40L * 86_400), now.minusSeconds(40L * 86_400));
        CouponUsageReservation recentReleased = seedTerminal(campaignId, UsageStatus.RELEASED,
            now.minusSeconds(2L * 86_400), now.minusSeconds(86_400));
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


    @Test
    @DisplayName("relay drains seeded PENDING row → SENT + record lands on shop.promotion.lifecycle.v1")
    void relayPublishesPendingOutboxRowToKafkaAndMarksItSent() {
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

        outboxRelay.relay();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            OutboxEvent reloaded = outboxRepository.findById(seeded.getId()).orElseThrow();
            assertThat(reloaded.getStatus()).isEqualTo(OutboxStatus.SENT);
            assertThat(reloaded.getSentAt()).isNotNull();
        });

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
