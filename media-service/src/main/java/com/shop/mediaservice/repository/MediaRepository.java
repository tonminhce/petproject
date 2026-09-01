package com.shop.mediaservice.repository;

import com.shop.mediaservice.entity.Media;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MediaRepository extends JpaRepository<Media, UUID> {

    /** Dedup lookup (D1) — SHA-256 is unique across LIVE media. */
    Optional<Media> findBySha256(String sha256);

    /**
     * Existence check that SEES soft-deleted rows (the {@code @SQLRestriction}
     * on {@link Media} hides them from every derived query). The lifecycle
     * service needs the distinction: unknown id → 404 MED-12004, deleted row
     * → 409 MED-12005.
     */
    @Query(value = "SELECT EXISTS(SELECT 1 FROM medias WHERE id = :id)", nativeQuery = true)
    boolean existsIncludingDeleted(@Param("id") UUID id);

    /**
     * Atomic soft delete — flips {@code deleted} only when the row is still
     * live, so a repeat delete loses the race and returns 0 affected rows
     * (→ 409 MED-12005). {@code deleted_at} set here is the purge-eligibility
     * timestamp the MediaPurgeJob measures the grace window against.
     *
     * <p>The conditional UPDATE (not load-then-mark) also means no entity is
     * loaded, so the EAGER variants join never fires on the delete path.</p>
     *
     * @return 1 when this call performed the delete, 0 when the row was
     *         already deleted (or concurrently deleted by another caller)
     */
    @Modifying
    @Transactional
    @Query(value = "UPDATE medias SET deleted = true, deleted_at = now(), deleted_by = :actor "
            + "WHERE id = :id AND deleted = false", nativeQuery = true)
    int softDelete(@Param("id") UUID id, @Param("actor") String actor);

    /**
     * Purge candidates (MediaPurgeJob): soft-deleted long enough that the
     * grace window has fully elapsed — {@code deleted_at <= now - grace}.
     * The {@code <=} is the binding boundary: a row deleted EXACTLY at the
     * grace horizon is purgeable, anything inside the grace is not. Native
     * SQL because the entity's {@code @SQLRestriction} hides deleted rows
     * from JPQL too.
     */
    @Query(value = "SELECT id FROM medias WHERE deleted = true AND deleted_at <= :cutoff", nativeQuery = true)
    List<UUID> findPurgeableIds(@Param("cutoff") Instant cutoff);

    /** Object keys of every stored variant of a media (purge deletes each). */
    @Query(value = "SELECT object_key FROM media_variants WHERE media_id = :id", nativeQuery = true)
    List<String> findObjectKeysByMediaId(@Param("id") UUID id);

    /**
     * Hard row removal after the S3 objects are gone — the FK
     * {@code fk_media_variants_media ON DELETE CASCADE} takes the variant
     * rows with it, so one DELETE finishes the purge.
     */
    @Modifying
    @Transactional
    @Query(value = "DELETE FROM medias WHERE id = :id", nativeQuery = true)
    int deleteIncludingDeleted(@Param("id") UUID id);
}
