package com.shop.productservice.service.impls;

import com.shop.common.core.exception.BusinessException;
import com.shop.productservice.dto.request.BrandCreateRequest;
import com.shop.productservice.dto.request.BrandUpdateRequest;
import com.shop.productservice.dto.response.BrandResponse;
import com.shop.productservice.entity.Brand;
import com.shop.productservice.mapper.BrandMapper;
import com.shop.productservice.repository.BrandRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BrandServiceImplTest {

    @Mock BrandRepository repo;
    @Mock BrandMapper mapper;
    @Mock AuditorAware<String> auditorAware;
    @InjectMocks BrandServiceImpl service;

    @Test
    void findById_returnsBrand() {
        Brand brand = Brand.builder().id(1L).name("Acme").slug("acme").build();
        BrandResponse resp = new BrandResponse(1L, "Acme", "acme", null, null);
        when(repo.findById(1L)).thenReturn(Optional.of(brand));
        when(mapper.toResponse(brand)).thenReturn(resp);

        assertThat(service.findById(1L)).isEqualTo(resp);
    }

    @Test
    void findById_throwsWhenNotFound() {
        when(repo.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById(1L))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void create_persistsAndReturns() {
        BrandCreateRequest req = new BrandCreateRequest("Acme", "acme", null, null);
        Brand brand = Brand.builder().id(1L).name("Acme").slug("acme").build();
        BrandResponse resp = new BrandResponse(1L, "Acme", "acme", null, null);
        when(repo.existsBySlug("acme")).thenReturn(false);
        when(mapper.toEntity(req)).thenReturn(brand);
        when(repo.save(brand)).thenReturn(brand);
        when(mapper.toResponse(brand)).thenReturn(resp);

        assertThat(service.create(req)).isEqualTo(resp);
    }

    @Test
    void create_throwsConflictOnDuplicateSlug() {
        BrandCreateRequest req = new BrandCreateRequest("Acme", "acme", null, null);
        when(repo.existsBySlug("acme")).thenReturn(true);

        assertThatThrownBy(() -> service.create(req))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void update_throwsConflictOnDuplicateSlug() {
        Brand existing = Brand.builder().id(1L).name("Acme").slug("acme").build();
        BrandUpdateRequest req = new BrandUpdateRequest(null, "taken", null, null);
        when(repo.findById(1L)).thenReturn(Optional.of(existing));
        when(repo.existsBySlugAndIdNot("taken", 1L)).thenReturn(true);

        assertThatThrownBy(() -> service.update(1L, req))
            .isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo("PRD-2007"));
    }

    @Test
    void update_appliesPartialUpdate() {
        Brand existing = Brand.builder().id(1L).name("Acme").slug("acme").description("old").build();
        BrandUpdateRequest req = new BrandUpdateRequest(null, null, null, "new");
        when(repo.findById(1L)).thenReturn(Optional.of(existing));
        when(repo.save(existing)).thenReturn(existing);
        when(mapper.toResponse(existing)).thenReturn(
            new BrandResponse(1L, "Acme", "acme", null, "new"));

        BrandResponse result = service.update(1L, req);
        assertThat(result.description()).isEqualTo("new");
        verify(mapper).partialUpdate(existing, req);
    }

    @Test
    void delete_softDeletesWithActor() {
        Brand existing = Brand.builder().id(1L).name("Acme").slug("acme").build();
        when(repo.findById(1L)).thenReturn(Optional.of(existing));
        when(auditorAware.getCurrentAuditor()).thenReturn(Optional.of("alice"));

        service.delete(1L);

        assertThat(existing.isDeleted()).isTrue();
        assertThat(existing.getDeletedBy()).isEqualTo("alice");
        assertThat(existing.getDeletedAt()).isNotNull();
        verify(repo).save(existing);
    }

    @Test
    void findAll_returnsPage() {
        Page<Brand> page = new PageImpl<>(List.of());
        when(repo.findAll(any(PageRequest.class))).thenReturn(page);

        var result = service.findAll(PageRequest.of(0, 10));
        assertThat(result.content()).isEmpty();
    }
}