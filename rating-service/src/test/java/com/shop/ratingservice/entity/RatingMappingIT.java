package com.shop.ratingservice.entity;

import com.shop.ratingservice.repository.RatingRepository;
import com.shop.ratingservice.support.AbstractIntegrationTest;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class RatingMappingIT extends AbstractIntegrationTest {

    @Autowired
    private RatingRepository ratingRepository;

    private static final String LONG_COMMENT = "Great product, would buy again!";

    @Test
    void validRatingPersists() {
        UUID productId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        Rating saved = ratingRepository.saveAndFlush(Rating.builder()
            .productId(productId)
            .userId(userId)
            .rating(5)
            .comment(LONG_COMMENT)
            .verified(true)
            .build());

        assertThat(saved.getId()).isNotNull();

        Rating loaded = ratingRepository.findById(saved.getId()).orElseThrow();
        assertThat(loaded.getProductId()).isEqualTo(productId);
        assertThat(loaded.getUserId()).isEqualTo(userId);
        assertThat(loaded.getRating()).isEqualTo(5);
        assertThat(loaded.getComment()).isEqualTo(LONG_COMMENT);
        assertThat(loaded.isVerified()).isTrue();
        assertThat(loaded.isHidden()).isFalse();
        assertThat(loaded.getHiddenAt()).isNull();
        assertThat(loaded.getHiddenBy()).isNull();
        assertThat(loaded.getCreatedAt()).isNotNull();
        assertThat(loaded.getUpdatedAt()).isNotNull();
        assertThat(loaded.isDeleted()).isFalse();
    }

    @Test
    void commentShorterThanFiveCharsViolatesCheckConstraint() {
        Rating rating = Rating.builder()
            .productId(UUID.randomUUID())
            .userId(UUID.randomUUID())
            .rating(4)
            .comment("bad")
            .build();

        assertThat(flushedConstraintName(rating)).isEqualTo("ck_ratings_comment_length");
    }

    @Test
    void ratingAboveFiveViolatesRangeConstraint() {
        Rating rating = Rating.builder()
            .productId(UUID.randomUUID())
            .userId(UUID.randomUUID())
            .rating(6)
            .comment(LONG_COMMENT)
            .build();

        assertThat(flushedConstraintName(rating)).isEqualTo("ck_ratings_rating_range");
    }

    @Test
    void duplicateUserProductPairViolatesUniqueIndex() {
        UUID productId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        ratingRepository.saveAndFlush(Rating.builder()
            .productId(productId)
            .userId(userId)
            .rating(3)
            .comment(LONG_COMMENT)
            .build());

        Rating duplicate = Rating.builder()
            .productId(productId)
            .userId(userId)
            .rating(4)
            .comment("Second attempt here")
            .build();

        assertThat(flushedConstraintName(duplicate)).isEqualTo("uk_rating_user_product_live");
    }

    @Test
    void hiddenWithoutAuditFieldsViolatesAuditConstraint() {
        Rating rating = Rating.builder()
            .productId(UUID.randomUUID())
            .userId(UUID.randomUUID())
            .rating(5)
            .comment(LONG_COMMENT)
            .hidden(true)
            .build();

        assertThat(flushedConstraintName(rating)).isEqualTo("ck_ratings_audit");
    }

    @Test
    void aggregateQueryReturnsCountAndAverageForSeededRow() {
        UUID productId = UUID.randomUUID();

        ratingRepository.saveAndFlush(Rating.builder()
            .productId(productId)
            .userId(UUID.randomUUID())
            .rating(4)
            .comment(LONG_COMMENT)
            .build());

        List<Object[]> rows = ratingRepository.findAggregateByProductId(productId);

        assertThat(rows).hasSize(1);
        assertThat(((Number) rows.get(0)[0]).doubleValue()).isEqualTo(4.0);
        assertThat(((Number) rows.get(0)[1]).longValue()).isEqualTo(1L);
    }

    private String flushedConstraintName(Rating rating) {
        DataIntegrityViolationException ex = catchThrowableOfType(
            () -> ratingRepository.saveAndFlush(rating), DataIntegrityViolationException.class);
        assertThat(ex).isNotNull();
        assertThat(ex.getCause()).isInstanceOf(ConstraintViolationException.class);
        return ((ConstraintViolationException) ex.getCause()).getConstraintName();
    }
}
