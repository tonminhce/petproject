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

    Optional<Media> findBySha256(String sha256);

    @Query(value = "SELECT EXISTS(SELECT 1 FROM medias WHERE id = :id)", nativeQuery = true)
    boolean existsIncludingDeleted(@Param("id") UUID id);

    @Modifying
    @Transactional
    @Query(value = "UPDATE medias SET deleted = true, deleted_at = now(), deleted_by = :actor "
            + "WHERE id = :id AND deleted = false", nativeQuery = true)
    int softDelete(@Param("id") UUID id, @Param("actor") String actor);

    @Query(value = "SELECT id FROM medias WHERE deleted = true AND deleted_at <= :cutoff", nativeQuery = true)
    List<UUID> findPurgeableIds(@Param("cutoff") Instant cutoff);

    @Query(value = "SELECT object_key FROM media_variants WHERE media_id = :id", nativeQuery = true)
    List<String> findObjectKeysByMediaId(@Param("id") UUID id);

    @Modifying
    @Transactional
    @Query(value = "DELETE FROM medias WHERE id = :id", nativeQuery = true)
    int deleteIncludingDeleted(@Param("id") UUID id);
}
