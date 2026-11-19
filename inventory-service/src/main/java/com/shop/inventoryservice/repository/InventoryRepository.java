package com.shop.inventoryservice.repository;

import com.shop.inventoryservice.entity.Inventory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface InventoryRepository extends JpaRepository<Inventory, UUID> {

    Optional<Inventory> findByProductId(UUID productId);

    boolean existsByProductId(UUID productId);

    /**
     * Atomic reserve. The pre-fix design read the inventory row,
     * computed available capacity, then wrote the increment — a TOCTOU
     * window where two concurrent requests could both observe the same
     * available capacity and both succeed even though only one truly fit.
     * <p>The atomic UPDATE collapses the check + write into a single
     * statement that Postgres serializes via row-level locking. The
     * {@code WHERE (available - reserved) >= :qty} guard makes the capacity
     * check part of the update itself; if the predicate doesn't hold the
     * UPDATE affects zero rows and we surface the
     * {@link com.shop.common.core.exception.ErrorCode#STOCK_INSUFFICIENT}
     * domain error. Concurrent requests can never observe a stale capacity
     * because the row lock is held for the duration of the UPDATE.</p>
     * <p>{@code @Version} stays on the entity for the read-then-write paths
     * (commit/release); this UPDATE doesn't go through the entity at all,
     * so the version bump is folded into the {@code version = version + 1}
     * set clause to keep optimistic-lock consumers (e.g. concurrent
     * {@code commit()}) consistent.</p>
     *
     * @return the number of rows affected — 1 on success, 0 if capacity was insufficient
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Inventory i SET i.reservedQuantity = i.reservedQuantity + :qty, "
         + "i.lastUpdated = CURRENT_TIMESTAMP, i.version = i.version + 1 "
         + "WHERE i.productId = :productId "
         + "AND (i.availableQuantity - i.reservedQuantity) >= :qty")
    int atomicReserve(@Param("productId") UUID productId, @Param("qty") int qty);

    /**
     * Atomic release of a reservation hold.
     * <p>Decrements {@code reservedQuantity} only when the row currently holds at
     * least the requested quantity, so the counter can never go negative and a
     * double release can never over-credit the stock. The version is bumped in
     * the same statement, mirroring {@link #atomicReserve}, so a concurrent
     * read-modify-write path ({@code commit()}, {@code release()}) sees a
     * consistent version.</p>
     *
     * @return 1 when the hold was released, 0 when the row holds less than
     *         {@code qty} (the caller must NOT mask this with a clamp - it is an
     *         accounting inconsistency that needs an operator)
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Inventory i SET i.reservedQuantity = i.reservedQuantity - :qty, "
         + "i.lastUpdated = CURRENT_TIMESTAMP, i.version = i.version + 1 "
         + "WHERE i.productId = :productId AND i.reservedQuantity >= :qty")
    int releaseReserved(@Param("productId") UUID productId, @Param("qty") int qty);
}
