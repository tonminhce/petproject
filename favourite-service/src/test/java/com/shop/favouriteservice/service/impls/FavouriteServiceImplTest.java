package com.shop.favouriteservice.service.impls;

import com.shop.common.core.exception.BusinessException;
import com.shop.favouriteservice.dto.request.FavouriteCreateRequest;
import com.shop.favouriteservice.dto.response.FavouriteResponse;
import com.shop.favouriteservice.entity.Favourite;
import com.shop.favouriteservice.mapper.FavouriteMapper;
import com.shop.favouriteservice.repository.FavouriteRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.AuditorAware;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FavouriteServiceImplTest {

    @Mock private FavouriteRepository repo;
    @Mock private FavouriteMapper mapper;
    @Mock private AuditorAware<String> auditorAware;

    @InjectMocks private FavouriteServiceImpl service;

    private final UUID userId = UUID.randomUUID();
    private final UUID favouriteId = UUID.randomUUID();
    private final UUID productId = UUID.randomUUID();

    private Favourite sampleFavourite() {
        // Note: no `.createdAt(...)` here — createdAt lives on AbstractMappedEntity
        // (superclass) which is NOT @SuperBuilder. Subclass @Builder only exposes
        // subclass fields. Auditing auto-fills createdAt via JpaAuditingEntityListener.
        return Favourite.builder()
                .id(favouriteId)
                .userId(userId)
                .productId(productId)
                .build();
    }

    private FavouriteResponse sampleResponse() {
        return new FavouriteResponse(favouriteId, userId, productId, Instant.now());
    }

    @Test
    void findAllByCurrentUser_returnsMappedList() {
        Favourite fav = sampleFavourite();
        when(repo.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(fav));
        when(mapper.toResponse(fav)).thenReturn(sampleResponse());

        List<FavouriteResponse> result = service.findAllByCurrentUser(userId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo(favouriteId);
    }

    @Test
    void findById_returnsFavourite_whenOwnedByCurrentUser() {
        Favourite fav = sampleFavourite();
        when(repo.findByIdAndUserId(favouriteId, userId)).thenReturn(Optional.of(fav));
        when(mapper.toResponse(fav)).thenReturn(sampleResponse());

        FavouriteResponse result = service.findById(favouriteId, userId);

        assertThat(result.id()).isEqualTo(favouriteId);
    }

    @Test
    void findById_throwsNotFound_whenMissing() {
        when(repo.findByIdAndUserId(favouriteId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById(favouriteId, userId))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo("FAV-6001"));
    }

    @Test
    void findById_throwsNotFound_whenOwnedByOtherUser() {
        when(repo.findByIdAndUserId(favouriteId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById(favouriteId, userId))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void create_persistsAndReturnsResponse() {
        when(repo.existsByUserIdAndProductId(userId, productId)).thenReturn(false);
        ArgumentCaptor<Favourite> captor = ArgumentCaptor.forClass(Favourite.class);
        Favourite saved = sampleFavourite();
        when(repo.save(any(Favourite.class))).thenReturn(saved);
        when(mapper.toResponse(saved)).thenReturn(sampleResponse());

        FavouriteResponse result = service.create(userId,
                new FavouriteCreateRequest(productId));

        verify(repo).save(captor.capture());
        Favourite persisted = captor.getValue();
        assertThat(persisted.getUserId()).isEqualTo(userId);
        assertThat(persisted.getProductId()).isEqualTo(productId);
        assertThat(result.id()).isEqualTo(favouriteId);
    }

    @Test
    void create_throwsConflict_whenDuplicate() {
        when(repo.existsByUserIdAndProductId(userId, productId)).thenReturn(true);

        assertThatThrownBy(() -> service.create(userId,
                new FavouriteCreateRequest(productId)))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo("FAV-6002"));
    }

    @Test
    void deleteById_softDeletes_whenFound() {
        when(auditorAware.getCurrentAuditor()).thenReturn(Optional.of("alice"));
        when(repo.softDeleteByIdAndUserId(eq(favouriteId), eq(userId), eq("alice"))).thenReturn(1);

        service.deleteById(favouriteId, userId);

        verify(repo).softDeleteByIdAndUserId(favouriteId, userId, "alice");
    }

    @Test
    void deleteById_throwsNotFound_whenNotOwned() {
        when(auditorAware.getCurrentAuditor()).thenReturn(Optional.of("alice"));
        when(repo.softDeleteByIdAndUserId(eq(favouriteId), eq(userId), eq("alice"))).thenReturn(0);

        assertThatThrownBy(() -> service.deleteById(favouriteId, userId))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo("FAV-6001"));
    }

    @Test
    void deleteByProductId_softDeletes_whenFound() {
        when(auditorAware.getCurrentAuditor()).thenReturn(Optional.of("alice"));
        when(repo.softDeleteByUserIdAndProductId(userId, productId, "alice")).thenReturn(1);

        service.deleteByProductId(userId, productId);

        verify(repo).softDeleteByUserIdAndProductId(userId, productId, "alice");
    }

    @Test
    void deleteByProductId_throwsNotFound_whenMissing() {
        when(auditorAware.getCurrentAuditor()).thenReturn(Optional.of("alice"));
        when(repo.softDeleteByUserIdAndProductId(userId, productId, "alice")).thenReturn(0);

        assertThatThrownBy(() -> service.deleteByProductId(userId, productId))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo("FAV-6003"));
    }
}
