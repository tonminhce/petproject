package com.shop.inventoryservice.service;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.inventoryservice.constant.ReservationStatus;
import com.shop.inventoryservice.dto.request.ReserveRequest;
import com.shop.inventoryservice.dto.response.ReservationResponse;
import com.shop.inventoryservice.entity.Inventory;
import com.shop.inventoryservice.entity.Reservation;
import com.shop.inventoryservice.repository.InventoryRepository;
import com.shop.inventoryservice.repository.OutboxEventRepository;
import com.shop.inventoryservice.repository.ReservationRepository;
import com.shop.inventoryservice.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InventoryReserveIdempotencyTest extends AbstractIntegrationTest {

    private static final String RESERVED_EVENT = "inventory.reserved.v1";
    private static final String COMMITTED_EVENT = "inventory.committed.v1";
    private static final String RELEASED_EVENT = "inventory.released.v1";

    @Autowired InventoryRepository inventories;
    @Autowired ReservationRepository reservations;
    @Autowired OutboxEventRepository outboxEvents;
    @Autowired InventoryService inventoryService;
    @Autowired ReservationService reservationService;
    @Autowired ReservationCleanupScheduler cleanupScheduler;
    @Autowired JdbcTemplate jdbc;

    private UUID seedInventory(int availableQuantity) {
        UUID productId = UUID.randomUUID();
        inventories.save(Inventory.builder()
            .productId(productId)
            .availableQuantity(availableQuantity)
            .reservedQuantity(0)
            .lastUpdated(Instant.now())
            .build());
        return productId;
    }

    private String newKey() {
        return "reserve-" + UUID.randomUUID();
    }

    private List<Reservation> reservationsOf(UUID productId) {
        return reservations.findAll().stream()
            .filter(row -> row.getProductId().equals(productId))
            .toList();
    }

    private long eventCount(UUID productId, String eventType) {
        Long count = jdbc.queryForObject(
            "SELECT count(*) FROM outbox_events WHERE event_type = ? AND aggregate_id = ?",
            Long.class, eventType, productId);
        return count == null ? 0L : count;
    }

    private List<String> eventPayloads(UUID productId, String eventType) {
        return jdbc.queryForList(
            "SELECT payload FROM outbox_events WHERE event_type = ? AND aggregate_id = ?",
            String.class, eventType, productId);
    }

    private void insertReservationRow(UUID productId, String idempotencyKey) {
        jdbc.update("""
            INSERT INTO reservations (id, product_id, quantity, status, created_at, expires_at, version, idempotency_key)
            VALUES (?, ?, 1, 'PENDING', now(), now() + interval '15 minutes', 0, ?)
            """, UUID.randomUUID(), productId, idempotencyKey);
    }

    @Test
    void reserveWithAnIdempotencyKey_createsOneReservationOneDecrementAndOneReservedEvent() {
        UUID productId = seedInventory(10);
        UUID orderId = UUID.randomUUID();
        String key = newKey();

        ReservationResponse response = reservationService.reserveWithRetry(
            productId, new ReserveRequest(2, orderId), key);

        assertThat(response.reservationId()).isNotNull();
        assertThat(response.productId()).isEqualTo(productId);
        assertThat(response.quantity()).isEqualTo(2);
        assertThat(response.orderId()).isEqualTo(orderId);
        assertThat(response.status()).isEqualTo(ReservationStatus.PENDING);

        List<Reservation> rows = reservationsOf(productId);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getId()).isEqualTo(response.reservationId());
        assertThat(rows.get(0).getIdempotencyKey()).isEqualTo(key);
        assertThat(rows.get(0).getQuantity()).isEqualTo(2);
        assertThat(rows.get(0).getStatus()).isEqualTo(ReservationStatus.PENDING);

        Inventory inventory = inventories.findByProductId(productId).orElseThrow();
        assertThat(inventory.getAvailableQuantity()).isEqualTo(10);
        assertThat(inventory.getReservedQuantity()).isEqualTo(2);

        assertThat(eventCount(productId, RESERVED_EVENT)).isEqualTo(1);
        assertThat(eventPayloads(productId, RESERVED_EVENT))
            .hasSize(1)
            .allSatisfy(payload -> assertThat(payload)
                .contains("\"reservationId\":\"" + response.reservationId() + "\"")
                .contains("\"quantity\":2")
                .contains("\"orderId\":\"" + orderId + "\""));
    }

    @Test
    void reserveWithTheSameKeyAndTheSameBinding_returnsTheExistingReservationWithoutASecondHold() {
        UUID productId = seedInventory(10);
        UUID orderId = UUID.randomUUID();
        String key = newKey();
        ReserveRequest request = new ReserveRequest(2, orderId);

        ReservationResponse first = reservationService.reserveWithRetry(productId, request, key);
        Inventory afterFirst = inventories.findByProductId(productId).orElseThrow();
        long reservedEventsAfterFirst = eventCount(productId, RESERVED_EVENT);

        ReservationResponse second = reservationService.reserveWithRetry(productId, request, key);

        assertThat(second.reservationId()).isEqualTo(first.reservationId());
        assertThat(second.quantity()).isEqualTo(2);
        assertThat(reservationsOf(productId)).hasSize(1);

        Inventory afterSecond = inventories.findByProductId(productId).orElseThrow();
        assertThat(afterSecond.getReservedQuantity()).isEqualTo(afterFirst.getReservedQuantity());
        assertThat(afterSecond.getReservedQuantity()).isEqualTo(2);
        assertThat(afterSecond.getAvailableQuantity()).isEqualTo(afterFirst.getAvailableQuantity());
        assertThat(eventCount(productId, RESERVED_EVENT)).isEqualTo(reservedEventsAfterFirst);
    }

    @Test
    void reserveWithTheSameKeyAndADifferentQuantity_isRejected() {
        UUID productId = seedInventory(10);
        String key = newKey();

        reservationService.reserveWithRetry(productId, new ReserveRequest(2, UUID.randomUUID()), key);

        assertThatThrownBy(() -> reservationService.reserveWithRetry(
            productId, new ReserveRequest(5, UUID.randomUUID()), key))
            .isInstanceOfSatisfying(BusinessException.class, ex -> {
                assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CONFLICT.getCode());
            });

        assertThat(reservationsOf(productId)).hasSize(1);
        Inventory inventory = inventories.findByProductId(productId).orElseThrow();
        assertThat(inventory.getReservedQuantity()).isEqualTo(2);
        assertThat(eventCount(productId, RESERVED_EVENT)).isEqualTo(1);
    }

    @Test
    void reserveWithTheSameKeyAndADifferentProduct_isRejected() {
        UUID productId = seedInventory(10);
        UUID otherProductId = seedInventory(10);
        String key = newKey();

        reservationService.reserveWithRetry(productId, new ReserveRequest(2, UUID.randomUUID()), key);

        assertThatThrownBy(() -> reservationService.reserveWithRetry(
            otherProductId, new ReserveRequest(2, UUID.randomUUID()), key))
            .isInstanceOfSatisfying(BusinessException.class, ex -> {
                assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CONFLICT.getCode());
            });

        assertThat(reservationsOf(productId)).hasSize(1);
        assertThat(reservationsOf(otherProductId)).isEmpty();
        assertThat(inventories.findByProductId(otherProductId).orElseThrow().getReservedQuantity()).isZero();
        assertThat(eventCount(productId, RESERVED_EVENT)).isEqualTo(1);
        assertThat(eventCount(otherProductId, RESERVED_EVENT)).isZero();
    }

    @Test
    void reserveWithoutAnIdempotencyKey_keepsCreatingANewReservation() {
        UUID productId = seedInventory(10);
        ReserveRequest request = new ReserveRequest(2, UUID.randomUUID());

        ReservationResponse first = reservationService.reserveWithRetry(productId, request);
        ReservationResponse second = reservationService.reserveWithRetry(productId, request);

        assertThat(second.reservationId()).isNotEqualTo(first.reservationId());
        assertThat(reservationsOf(productId)).hasSize(2);

        Inventory inventory = inventories.findByProductId(productId).orElseThrow();
        assertThat(inventory.getReservedQuantity()).isEqualTo(4);
        assertThat(inventory.getAvailableQuantity()).isEqualTo(10);
        assertThat(eventCount(productId, RESERVED_EVENT)).isEqualTo(2);
    }

    @Test
    void concurrentReservesWithTheSameKey_createExactlyOneReservationAndOneDecrement() throws Exception {
        UUID productId = seedInventory(50);
        UUID orderId = UUID.randomUUID();
        String key = newKey();
        ReserveRequest request = new ReserveRequest(3, orderId);

        int callers = 2;
        ExecutorService executor = Executors.newFixedThreadPool(callers);
        CyclicBarrier startLine = new CyclicBarrier(callers);
        CountDownLatch finished = new CountDownLatch(callers);
        List<ReservationResponse> responses = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < callers; i++) {
            executor.submit(() -> {
                try {
                    startLine.await(15, TimeUnit.SECONDS);
                    responses.add(reservationService.reserveWithRetry(productId, request, key));
                } catch (Throwable ex) {
                    failures.add(ex);
                } finally {
                    finished.countDown();
                }
            });
        }

        assertThat(finished.await(60, TimeUnit.SECONDS)).isTrue();
        executor.shutdownNow();

        assertThat(failures).isEmpty();
        List<ReservationResponse> results = List.copyOf(responses);
        assertThat(results).hasSize(2);
        assertThat(results.get(0).reservationId()).isEqualTo(results.get(1).reservationId());

        assertThat(reservationsOf(productId)).hasSize(1);
        Inventory inventory = inventories.findByProductId(productId).orElseThrow();
        assertThat(inventory.getReservedQuantity()).isEqualTo(3);
        assertThat(inventory.getAvailableQuantity()).isEqualTo(50);
        assertThat(eventCount(productId, RESERVED_EVENT)).isEqualTo(1);
    }

    @Test
    void concurrentReservesOfTheLastUnitWithDifferentKeys_yieldOneSuccessAndOneInsufficientStock()
        throws Exception {
        UUID productId = seedInventory(1);

        int callers = 2;
        ExecutorService executor = Executors.newFixedThreadPool(callers);
        CyclicBarrier startLine = new CyclicBarrier(callers);
        CountDownLatch finished = new CountDownLatch(callers);
        List<ReservationResponse> responses = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < callers; i++) {
            String key = newKey();
            executor.submit(() -> {
                try {
                    startLine.await(15, TimeUnit.SECONDS);
                    responses.add(reservationService.reserveWithRetry(
                        productId, new ReserveRequest(1, UUID.randomUUID()), key));
                } catch (Throwable ex) {
                    failures.add(ex);
                } finally {
                    finished.countDown();
                }
            });
        }

        assertThat(finished.await(60, TimeUnit.SECONDS)).isTrue();
        executor.shutdownNow();

        assertThat(responses).hasSize(1);
        assertThat(failures).hasSize(1);
        assertThat(failures.get(0))
            .isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.STOCK_INSUFFICIENT.getCode()));

        assertThat(reservationsOf(productId)).hasSize(1);
        Inventory inventory = inventories.findByProductId(productId).orElseThrow();
        assertThat(inventory.getAvailableQuantity()).isEqualTo(1);
        assertThat(inventory.getReservedQuantity()).isEqualTo(1);
        assertThat(eventCount(productId, RESERVED_EVENT)).isEqualTo(1);
    }

    @Test
    void commitStaysIdempotentForAHoldCreatedWithAnIdempotencyKey() {
        UUID productId = seedInventory(10);
        String key = newKey();
        ReservationResponse response = reservationService.reserveWithRetry(
            productId, new ReserveRequest(2, UUID.randomUUID()), key);

        inventoryService.commit(response.reservationId());
        Inventory afterCommit = inventories.findByProductId(productId).orElseThrow();
        long committedEvents = eventCount(productId, COMMITTED_EVENT);

        inventoryService.commit(response.reservationId());

        Inventory afterRetry = inventories.findByProductId(productId).orElseThrow();
        assertThat(afterRetry.getAvailableQuantity()).isEqualTo(afterCommit.getAvailableQuantity());
        assertThat(afterRetry.getReservedQuantity()).isEqualTo(afterCommit.getReservedQuantity());
        assertThat(afterRetry.getAvailableQuantity()).isEqualTo(8);
        assertThat(afterRetry.getReservedQuantity()).isZero();
        assertThat(eventCount(productId, COMMITTED_EVENT)).isEqualTo(committedEvents);
        assertThat(eventCount(productId, COMMITTED_EVENT)).isEqualTo(1);
    }

    @Test
    void releaseStaysIdempotentForAHoldCreatedWithAnIdempotencyKey() {
        UUID productId = seedInventory(10);
        String key = newKey();
        ReservationResponse response = reservationService.reserveWithRetry(
            productId, new ReserveRequest(2, UUID.randomUUID()), key);

        inventoryService.release(response.reservationId());
        Inventory afterRelease = inventories.findByProductId(productId).orElseThrow();
        long releasedEvents = eventCount(productId, RELEASED_EVENT);

        inventoryService.release(response.reservationId());

        Inventory afterRetry = inventories.findByProductId(productId).orElseThrow();
        assertThat(afterRetry.getReservedQuantity()).isEqualTo(afterRelease.getReservedQuantity());
        assertThat(afterRetry.getReservedQuantity()).isZero();
        assertThat(afterRetry.getAvailableQuantity()).isEqualTo(10);
        assertThat(eventCount(productId, RELEASED_EVENT)).isEqualTo(releasedEvents);
        assertThat(eventCount(productId, RELEASED_EVENT)).isEqualTo(1);
    }

    @Test
    void theExpirySweeperReleasesAnExpiredHoldCreatedWithAnIdempotencyKey() {
        UUID productId = seedInventory(10);
        String key = newKey();
        ReservationResponse response = reservationService.reserveWithRetry(
            productId, new ReserveRequest(2, UUID.randomUUID()), key);

        jdbc.update("UPDATE reservations SET expires_at = ? WHERE id = ?",
            Timestamp.from(Instant.now().minusSeconds(60)), response.reservationId());

        cleanupScheduler.releaseAllExpiredReservations();

        Reservation swept = reservations.findById(response.reservationId()).orElseThrow();
        assertThat(swept.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
        Inventory inventory = inventories.findByProductId(productId).orElseThrow();
        assertThat(inventory.getReservedQuantity()).isZero();
        assertThat(inventory.getAvailableQuantity()).isEqualTo(10);
        assertThat(eventCount(productId, RELEASED_EVENT)).isEqualTo(1);
    }

    @Test
    void theIdempotencyKeyIsEnforcedByADatabaseUniqueConstraint() {
        Integer constraintCount = jdbc.queryForObject("""
            SELECT count(*) FROM pg_constraint
            WHERE conname = 'uk_reservations_idempotency_key' AND contype = 'u'
            """, Integer.class);

        assertThat(constraintCount).isEqualTo(1);
    }

    @Test
    void aSecondReservationRowWithTheSameIdempotencyKeyIsRejectedByTheDatabase() {
        UUID productId = seedInventory(5);
        UUID otherProductId = seedInventory(5);
        String key = newKey();

        insertReservationRow(productId, key);

        assertThatThrownBy(() -> insertReservationRow(otherProductId, key))
            .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(reservationsOf(productId)).hasSize(1);
        assertThat(reservationsOf(otherProductId)).isEmpty();
    }
}
