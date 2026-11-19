package com.shop.favouriteservice.repository;

import com.shop.favouriteservice.entity.Favourite;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface FavouriteRepository extends JpaRepository<Favourite, UUID> {

    Page<Favourite> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    Optional<Favourite> findByIdAndUserId(UUID id, UUID userId);

    boolean existsByUserIdAndProductId(UUID userId, UUID productId);

    @Modifying
    @Query("""
            UPDATE Favourite f
               SET f.deleted = true,
                   f.deletedAt = CURRENT_TIMESTAMP,
                   f.deletedBy = :deletedBy
             WHERE f.id = :id
               AND f.userId = :userId
               AND f.deleted = false
            """)
    int softDeleteByIdAndUserId(@Param("id") UUID id,
                                 @Param("userId") UUID userId,
                                 @Param("deletedBy") String deletedBy);

    @Modifying
    @Query("""
            UPDATE Favourite f
               SET f.deleted = true,
                   f.deletedAt = CURRENT_TIMESTAMP,
                   f.deletedBy = :deletedBy
             WHERE f.userId = :userId
               AND f.productId = :productId
               AND f.deleted = false
            """)
    int softDeleteByUserIdAndProductId(@Param("userId") UUID userId,
                                        @Param("productId") UUID productId,
                                        @Param("deletedBy") String deletedBy);
}
