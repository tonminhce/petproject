package com.shop.mediaservice.entity;

import com.shop.common.core.data.AbstractMappedEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.SQLRestriction;
import org.springframework.data.domain.Persistable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * D1 — one uploaded image: content-addressed by SHA-256 (unique — duplicate
 * uploads resolve to the existing row, never a second object), the declared
 * content type of the ORIGINAL, and the original byte size. Variants are the
 * six D2 renders (original/display/thumb × original-format/WebP) owned
 * cascade-all so media + variants commit in one transaction LAST, after all
 * S3 objects are already durable.
 *
 * <p>The id is application-assigned: object keys are {@code {mediaId}/…} (D1),
 * so the UUID must exist BEFORE the S3 writes. {@link Persistable} tells
 * Spring Data a pre-set id is still a NEW entity (persist, not merge).</p>
 */
@Entity
@Table(name = "medias")
@SQLRestriction("deleted = false")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Media extends AbstractMappedEntity implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "sha256", nullable = false, length = 64)
    private String sha256;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @OneToMany(mappedBy = "media", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @Builder.Default
    private List<MediaVariant> variants = new ArrayList<>();

    /** New-ness is tracked outside the DB — every loaded/persisted row is not new. */
    @Transient
    @Builder.Default
    private transient boolean isNew = true;

    @Override
    public boolean isNew() {
        return isNew;
    }

    @jakarta.persistence.PostLoad
    @jakarta.persistence.PrePersist
    void markNotNew() {
        this.isNew = false;
    }
}
