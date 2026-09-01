package com.shop.mediaservice.repository;

import com.shop.mediaservice.entity.Media;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface MediaRepository extends JpaRepository<Media, UUID> {

    /** Dedup lookup (D1) — SHA-256 is unique across LIVE media. */
    Optional<Media> findBySha256(String sha256);
}
