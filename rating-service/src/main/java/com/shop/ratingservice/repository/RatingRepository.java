package com.shop.ratingservice.repository;

import com.shop.ratingservice.entity.Rating;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RatingRepository extends JpaRepository<Rating, UUID> {

    Page<Rating> findByProductIdAndHiddenFalseAndDeletedFalse(UUID productId, Pageable pageable);

    Optional<Rating> findByUserIdAndProductIdAndDeletedFalse(UUID userId, UUID productId);

    Optional<Rating> findByIdAndDeletedFalse(UUID id);

    @Query("select coalesce(avg(r.rating), 0), count(r) from Rating r where r.productId = :productId and r.hidden = false and r.deleted = false")
    List<Object[]> findAggregateByProductId(@Param("productId") UUID productId);
}
