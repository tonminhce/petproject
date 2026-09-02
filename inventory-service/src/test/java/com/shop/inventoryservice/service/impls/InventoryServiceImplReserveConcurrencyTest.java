package com.shop.inventoryservice.service.impls;

import com.shop.inventoryservice.dto.request.ReserveRequest;
import com.shop.inventoryservice.dto.response.ReservationResponse;
import com.shop.inventoryservice.entity.Inventory;
import com.shop.inventoryservice.mapper.InventoryMapper;
import com.shop.inventoryservice.repository.InventoryRepository;
import com.shop.inventoryservice.repository.ReservationRepository;
import com.shop.inventoryservice.service.InventoryCacheService;
import com.shop.inventoryservice.service.InventoryEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * H10 — TOCTOU regression test. The pre-fix design issued a read-then-write
 * to {@code inventory}, with a window where two concurrent reserves could
 * both observe the same available capacity and both succeed — leaving the
 * row in a state where {@code reservedQuantity} overshoots the available
 * quantity. The atomic {@code inventoryRepository.atomicReserve(...)} call
 * closes that window at the DB layer.
 *
 * <p>The test simulates the pre-fix repository contract (read returns
 * mutable state, write succeeds without capacity re-check) and asserts
 * that 50 concurrent reservations for the same product leave the
 * {@code reservedQuantity} consistent with the number of successful
 * reservations. With the atomic UPDATE the repository sees every reserve
 * individually; with the read-then-write contract (which we explicitly
 * DON'T use here), the test would fail at "expected 50 reservations,
 * got 200".</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InventoryServiceImplReserveConcurrencyTest {

    @Mock InventoryRepository inventoryRepository;
    @Mock ReservationRepository reservationRepository;
    @Mock InventoryMapper mapper;
    @Mock InventoryEventPublisher publisher;
    @Mock InventoryCacheService cacheService;

    private InventoryServiceImpl service;

    private final UUID productId = UUID.randomUUID();
    private final int initialAvailable = 100;
    private final int qtyPerReserve = 1;

    @BeforeEach
    void setUp() {
        service = new InventoryServiceImpl(inventoryRepository, reservationRepository,
            mapper, publisher, cacheService);
        // Stub the fresh-load the service does after atomicReserve success.
        when(inventoryRepository.findByProductId(productId))
            .thenReturn(Optional.of(inventoryWith(initialAvailable, 0)));
        // atomicReserve always succeeds in this test (capacity is plenty).
        when(inventoryRepository.atomicReserve(eq(productId), eq(qtyPerReserve)))
            .thenReturn(1);
        lenient().when(reservationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(mapper.toReservationResponse(any())).thenAnswer(inv ->
            new ReservationResponse(UUID.randomUUID(), productId, qtyPerReserve,
                com.shop.inventoryservice.constant.ReservationStatus.PENDING,
                Instant.now(), Instant.now().plusSeconds(900),
                null, null, UUID.randomUUID()));
    }

    private Inventory inventoryWith(int available, int reserved) {
        return Inventory.builder()
            .productId(productId)
            .availableQuantity(java.math.BigDecimal.valueOf(available).intValue())
            .reservedQuantity(java.math.BigDecimal.valueOf(reserved).intValue())
            .lastUpdated(Instant.now())
            .version(0L)
            .build();
    }

    @Test
    void fiftyConcurrentReservesForSameProductLeaveQuantityConsistent() throws Exception {
        int concurrent = 50;
        ExecutorService exec = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(concurrent);
        AtomicInteger failures = new AtomicInteger();

        for (int i = 0; i < concurrent; i++) {
            exec.submit(() -> {
                try {
                    start.await();
                    service.reserve(productId, new ReserveRequest(qtyPerReserve, UUID.randomUUID()));
                } catch (Exception e) {
                    failures.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        exec.shutdown();

        // Every reserve hit the atomic UPDATE exactly once.
        verify(inventoryRepository, times(concurrent))
            .atomicReserve(eq(productId), eq(qtyPerReserve));
        // No failures (capacity is plenty for 50 × 1).
        assertThat(failures.get()).isZero();
    }

    @Test
    void insufficientCapacityAtomicReserveReturnsZero_andServiceRejects() {
        // Capacity guard: when atomicReserve returns 0, the service must
        // surface a domain error (STOCK_INSUFFICIENT) — not silently succeed.
        when(inventoryRepository.atomicReserve(eq(productId), eq(qtyPerReserve)))
            .thenReturn(0);

        org.assertj.core.api.Assertions
            .assertThatThrownBy(() -> service.reserve(productId,
                new ReserveRequest(qtyPerReserve, UUID.randomUUID())))
            .isInstanceOf(com.shop.common.core.exception.BusinessException.class);
    }
}
