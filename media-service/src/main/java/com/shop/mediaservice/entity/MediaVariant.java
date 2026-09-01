package com.shop.mediaservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * D2 — one rendered variant of a {@link Media}: canonical names
 * {@code original|display|thumb}, format {@code jpeg|png|webp}, rendered
 * pixel width, encoded byte size, and the private-bucket object key
 * ({@code {mediaId}/{variant}.{ext}}).
 */
@Entity
@Table(name = "media_variants",
       uniqueConstraints = @UniqueConstraint(name = "uk_media_variant_format",
               columnNames = {"media_id", "variant", "format"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class MediaVariant {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "media_id", nullable = false)
    private Media media;

    @Column(nullable = false, length = 20)
    private String variant;

    @Column(nullable = false, length = 10)
    private String format;

    @Column(nullable = false)
    private int width;

    @Column(nullable = false)
    private long bytes;

    @Column(name = "object_key", nullable = false, length = 500)
    private String objectKey;
}
