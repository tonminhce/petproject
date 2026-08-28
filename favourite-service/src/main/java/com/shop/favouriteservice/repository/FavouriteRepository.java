package com.shop.favouriteservice.repository;

import com.shop.favouriteservice.entity.Favourite;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FavouriteRepository extends JpaRepository<Favourite, UUID> {

    /**
     * Returns the current user's favourites, newest first. {@code @SQLRestriction}
     * on the entity auto-filters soft-deleted rows — no {@code AndDeletedFalse} suffix needed.
     */
    List<Favourite> findByUserIdOrderByCreatedAtDesc(UUID userId);

    /**
     * Finds a favourite by id scoped to its owning user. Returns empty when the row
     * exists but belongs to a different user — lets the service throw NOT_FOUND
     * without leaking cross-user existence.
     */
    Optional<Favourite> findByIdAndUserId(UUID id, UUID userId);

    Optional<Favourite> findByUserIdAndProductId(UUID userId, UUID productId);

    boolean existsByUserIdAndProductId(UUID userId, UUID productId);

    /**
     * Soft-deletes a single favourite when it belongs to the given user.
     *
     * @return number of rows updated (0 if id missing or owner mismatch — both
     *         intentionally treated as "not found" by the service layer)
     */
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

    /**
     * Soft-deletes by (userId, productId) pair. Used by the
     * {@code DELETE /api/v1/favourites/by-product/{productId}} endpoint.
     */
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
