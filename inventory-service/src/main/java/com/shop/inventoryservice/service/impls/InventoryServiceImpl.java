package com.shop.inventoryservice.service.impls;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.common.core.viewmodel.PageResponse;
import com.shop.inventoryservice.dto.request.InventoryUpsertRequest;
import com.shop.inventoryservice.dto.request.ReserveRequest;
import com.shop.inventoryservice.dto.response.InventoryResponse;
import com.shop.inventoryservice.dto.response.ReservationResponse;
import com.shop.inventoryservice.entity.Inventory;
import com.shop.inventoryservice.entity.Reservation;
import com.shop.inventoryservice.constant.ReservationStatus;
import com.shop.inventoryservice.mapper.InventoryMapper;
import com.shop.inventoryservice.repository.InventoryRepository;
import com.shop.inventoryservice.repository.ReservationRepository;
import com.shop.inventoryservice.service.InventoryCacheService;
import com.shop.inventoryservice.service.InventoryEventPublisher;
import com.shop.inventoryservice.service.InventoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class InventoryServiceImpl implements InventoryService {

    private final InventoryRepository inventoryRepository;
    private final ReservationRepository reservationRepository;
    private final InventoryMapper mapper;
    private final InventoryEventPublisher publisher;
    private final InventoryCacheService cacheService;

    @Value("${inventory.reservation-ttl-seconds:900}")
    private long reservationTtlSeconds;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<InventoryResponse> findAll(Pageable pageable) {
        Page<Inventory> page = inventoryRepository.findAll(pageable);
        return PageResponse.of(
            page.map(mapper::toResponse).getContent(),
            page.getNumber(),
            page.getSize(),
            page.getTotalElements());
    }

    /**
     * Cache-aside read — populated here, invalidated by
     * {@code InventoryCacheService.evictAfterCommit} on every write path
     * (create/update/delete/reserve/commit/release), so a hot read never
     * outlives the write that stale-dated it.
     */
    @Override
    @Transactional(readOnly = true)
    @Cacheable(value = "inventory", key = "#productId")
    public InventoryResponse findById(UUID productId) {
        return inventoryRepository.findByProductId(productId)
            .map(mapper::toResponse)
            .orElseThrow(() -> BusinessException.of(ErrorCode.INVENTORY_NOT_FOUND, productId));
    }

    @Override
    @Transactional
    public InventoryResponse create(InventoryUpsertRequest request) {
        if (inventoryRepository.existsByProductId(request.productId())) {
            throw BusinessException.of(ErrorCode.INVENTORY_ALREADY_EXISTS, request.productId());
        }
        Inventory inventory = mapper.toEntity(request);
        inventory.setLastUpdated(Instant.now());
        Inventory saved = inventoryRepository.save(inventory);
        publisher.publishAdjusted(saved);
        cacheService.evictAfterCommit(saved.getProductId());
        return mapper.toResponse(saved);
    }

    @Override
    @Transactional
    public InventoryResponse update(UUID productId, InventoryUpsertRequest request) {
        if (request.productId() != null && !request.productId().equals(productId)) {
            // Body productId (if present) must agree with the path — never silently
            // update a different row than the caller addressed.
            throw BusinessException.badRequest("inventory.product.id.mismatch", request.productId(), productId);
        }
        Inventory existing = inventoryRepository.findByProductId(productId)
            .orElseThrow(() -> BusinessException.of(ErrorCode.INVENTORY_NOT_FOUND, productId));
        mapper.partialUpdate(existing, request);
        existing.setLastUpdated(Instant.now());
        Inventory saved = inventoryRepository.save(existing);
        publisher.publishAdjusted(saved);
        if (saved.getAvailableQuantity() <= saved.getSafetyStockThreshold()) {
            publisher.publishLowStock(saved);
        }
        cacheService.evictAfterCommit(productId);
        return mapper.toResponse(saved);
    }

    @Override
    @Transactional
    public void delete(UUID productId) {
        Inventory existing = inventoryRepository.findByProductId(productId)
            .orElseThrow(() -> BusinessException.of(ErrorCode.INVENTORY_NOT_FOUND, productId));
        long active = reservationRepository.countByProductIdAndStatusIn(
            productId, List.of(ReservationStatus.PENDING, ReservationStatus.COMMITTED));
        if (active > 0) {
            // INV-3009 — the conflict is about the INVENTORY having active
            // reservations, not about a reservation being in an invalid state.
            throw BusinessException.of(ErrorCode.INVENTORY_IN_USE, productId);
        }
        inventoryRepository.delete(existing);
        publisher.publishDeleted(existing);
        cacheService.evictAfterCommit(productId);
    }

    @Override
    @Transactional
    public ReservationResponse reserve(UUID productId, ReserveRequest request) {
        // H10 — atomic capacity check + increment. The pre-fix read-then-write
        // path had a TOCTOU window: two concurrent reserves could both observe
        // the same available capacity and both succeed. The atomic UPDATE on
        // the repository collapses the check + write into one statement that
        // Postgres serializes via the row lock — concurrent requests can never
        // both succeed past the available-reserved guard.
        int updated = inventoryRepository.atomicReserve(productId, request.quantity());
        if (updated == 0) {
            // 0 rows updated — capacity was insufficient OR product missing.
            // We disambiguate by looking the row up (the atomic UPDATE doesn't
            // tell us which side failed). The find is rare-path only; the hot
            // success path takes the early return below.
            inventoryRepository.findByProductId(productId)
                .orElseThrow(() -> BusinessException.of(ErrorCode.INVENTORY_NOT_FOUND, productId));
            throw BusinessException.of(ErrorCode.STOCK_INSUFFICIENT, productId);
        }
        Inventory inventory = inventoryRepository.findByProductId(productId)
            .orElseThrow(() -> BusinessException.of(ErrorCode.INVENTORY_NOT_FOUND, productId));

        Reservation reservation = Reservation.builder()
            .productId(productId)
            .quantity(request.quantity())
            .status(ReservationStatus.PENDING)
            .createdAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(reservationTtlSeconds))
            .orderId(request.orderId())
            .build();
        reservationRepository.save(reservation);

        publisher.publishReserved(inventory, reservation);
        cacheService.evictAfterCommit(productId);
        return mapper.toReservationResponse(reservation);
    }

    @Override
    @Transactional
    public void commit(UUID reservationId) {
        Reservation r = reservationRepository.findById(reservationId)
            .orElseThrow(() -> BusinessException.of(ErrorCode.RESERVATION_NOT_FOUND, reservationId));
        // Idempotent retry: a retried commit after a timeout must succeed (hardening spec §7.1)
        if (r.getStatus() == ReservationStatus.COMMITTED) {
            log.info("Reservation {} already committed (idempotent retry)", reservationId);
            return;
        }
        if (r.getStatus() != ReservationStatus.PENDING) {
            throw BusinessException.of(ErrorCode.RESERVATION_INVALID_STATE, reservationId);
        }
        if (r.getExpiresAt().isBefore(Instant.now())) {
            throw BusinessException.of(ErrorCode.RESERVATION_EXPIRED, reservationId);
        }
        Inventory inventory = inventoryRepository.findByProductId(r.getProductId())
            .orElseThrow(() -> BusinessException.of(ErrorCode.INVENTORY_NOT_FOUND, r.getProductId()));
        inventory.setAvailableQuantity(inventory.getAvailableQuantity() - r.getQuantity());
        inventory.setReservedQuantity(inventory.getReservedQuantity() - r.getQuantity());
        inventory.setLastUpdated(Instant.now());
        r.setStatus(ReservationStatus.COMMITTED);
        r.setCommittedAt(Instant.now());
        inventoryRepository.save(inventory);
        reservationRepository.save(r);
        publisher.publishCommitted(inventory, r);
        if (inventory.getAvailableQuantity() <= inventory.getSafetyStockThreshold()) {
            publisher.publishLowStock(inventory);
        }
        cacheService.evictAfterCommit(r.getProductId());
    }

    @Override
    @Transactional
    public void release(UUID reservationId) {
        Reservation r = reservationRepository.findById(reservationId)
            .orElseThrow(() -> BusinessException.of(ErrorCode.RESERVATION_NOT_FOUND, reservationId));
        // Idempotent: quota already returned — safe no-op (hardening spec §7.1)
        if (r.getStatus() == ReservationStatus.RELEASED
            || r.getStatus() == ReservationStatus.EXPIRED) {
            log.info("Reservation {} already terminalized (idempotent retry)", reservationId);
            return;
        }
        if (r.getStatus() != ReservationStatus.PENDING) {
            throw BusinessException.of(ErrorCode.RESERVATION_INVALID_STATE, reservationId);
        }
        if (r.getExpiresAt().isBefore(Instant.now())) {
            throw BusinessException.of(ErrorCode.RESERVATION_EXPIRED, reservationId);
        }
        Inventory inventory = inventoryRepository.findByProductId(r.getProductId())
            .orElseThrow(() -> BusinessException.of(ErrorCode.INVENTORY_NOT_FOUND, r.getProductId()));
        inventory.setReservedQuantity(inventory.getReservedQuantity() - r.getQuantity());
        inventory.setLastUpdated(Instant.now());
        r.setStatus(ReservationStatus.RELEASED);
        r.setReleasedAt(Instant.now());
        inventoryRepository.save(inventory);
        reservationRepository.save(r);
        publisher.publishReleased(inventory, r, "PENDING");
        cacheService.evictAfterCommit(r.getProductId());
    }

    /**
     * ⚠️ Hardening spec §7.2 — half-commit rollback. COMMITTED → RELEASED
     * with restock: commit() already moved the quantity out of
     * {@code reservedQuantity} and deducted {@code availableQuantity}, so
     * releasing a committed reservation credits the stock back instead of
     * freeing the hold.
     */
    @Override
    @Transactional
    public void releaseCommitted(UUID reservationId) {
        Reservation r = reservationRepository.findById(reservationId)
            .orElseThrow(() -> BusinessException.of(ErrorCode.RESERVATION_NOT_FOUND, reservationId));
        // Idempotent retry: quota already restored — safe no-op (hardening spec §7.2)
        if (r.getStatus() == ReservationStatus.RELEASED
            || r.getStatus() == ReservationStatus.EXPIRED) {
            log.info("Reservation {} already terminalized (idempotent retry)", reservationId);
            return;
        }
        if (r.getStatus() != ReservationStatus.COMMITTED) {
            // PENDING rows belong to the plain release() path.
            throw BusinessException.of(ErrorCode.RESERVATION_INVALID_STATE, reservationId);
        }
        Inventory inv = inventoryRepository.findByProductId(r.getProductId())
            .orElseThrow(() -> BusinessException.of(ErrorCode.INVENTORY_NOT_FOUND, r.getProductId()));
        inv.setAvailableQuantity(inv.getAvailableQuantity() + r.getQuantity());
        inv.setLastUpdated(Instant.now());
        r.setStatus(ReservationStatus.RELEASED);
        r.setReleasedAt(Instant.now());
        inventoryRepository.save(inv);
        reservationRepository.save(r);
        publisher.publishReleased(inv, r, "COMMITTED");
        cacheService.evictAfterCommit(r.getProductId());
    }

    /**
     * Read-only reservation state projection for reconciliation polling
     * (hardening spec §7.3). No retries needed — a single consistent read.
     */
    @Override
    @Transactional(readOnly = true)
    public ReservationResponse getState(UUID reservationId) {
        Reservation r = reservationRepository.findById(reservationId)
            .orElseThrow(() -> BusinessException.of(ErrorCode.RESERVATION_NOT_FOUND, reservationId));
        return mapper.toReservationResponse(r);
    }

}
