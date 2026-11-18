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

    @Modifying(clearAutomatically = true)
    @Query("UPDATE Inventory i SET i.reservedQuantity = i.reservedQuantity + :qty, "
         + "i.lastUpdated = CURRENT_TIMESTAMP, i.version = i.version + 1 "
         + "WHERE i.productId = :productId "
         + "AND (i.availableQuantity - i.reservedQuantity) >= :qty")
    int atomicReserve(@Param("productId") UUID productId, @Param("qty") int qty);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Inventory i SET i.reservedQuantity = i.reservedQuantity - :qty, "
         + "i.lastUpdated = CURRENT_TIMESTAMP, i.version = i.version + 1 "
         + "WHERE i.productId = :productId AND i.reservedQuantity >= :qty")
    int releaseReserved(@Param("productId") UUID productId, @Param("qty") int qty);
}
