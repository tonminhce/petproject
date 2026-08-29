package com.shop.productservice.service.impls;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.common.core.viewmodel.PageResponse;
import com.shop.productservice.dto.request.BrandCreateRequest;
import com.shop.productservice.dto.request.BrandUpdateRequest;
import com.shop.productservice.dto.response.BrandResponse;
import com.shop.productservice.entity.Brand;
import com.shop.productservice.mapper.BrandMapper;
import com.shop.productservice.repository.BrandRepository;
import com.shop.productservice.service.BrandEventPublisher;
import com.shop.productservice.service.BrandService;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BrandServiceImpl implements BrandService {

    private final BrandRepository repo;
    private final BrandMapper mapper;
    private final BrandEventPublisher publisher;
    private final AuditorAware<String> auditorAware;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<BrandResponse> findAll(Pageable pageable) {
        Page<Brand> page = repo.findAll(pageable);
        return PageResponse.of(
            page.map(mapper::toResponse).getContent(),
            page.getNumber(),
            page.getSize(),
            page.getTotalElements());
    }

    @Override
    @Transactional(readOnly = true)
    @Cacheable(value = "brand", key = "#id")
    public BrandResponse findById(UUID id) {
        return repo.findById(id)
            .map(mapper::toResponse)
            .orElseThrow(() -> BusinessException.of(ErrorCode.BRAND_NOT_FOUND, id));
    }

    @Override
    @Transactional
    public BrandResponse create(BrandCreateRequest request) {
        if (repo.existsBySlug(request.slug())) {
            throw BusinessException.of(ErrorCode.BRAND_SLUG_EXISTS);
        }
        Brand brand = mapper.toEntity(request);
        Brand saved = repo.save(brand);
        publisher.publishCreated(saved);
        return mapper.toResponse(saved);
    }

    @Override
    @Transactional
    @CachePut(value = "brand", key = "#id")
    public BrandResponse update(UUID id, BrandUpdateRequest request) {
        Brand existing = repo.findById(id)
            .orElseThrow(() -> BusinessException.of(ErrorCode.BRAND_NOT_FOUND, id));
        if (request.slug() != null && repo.existsBySlugAndIdNot(request.slug(), id)) {
            throw BusinessException.of(ErrorCode.BRAND_SLUG_EXISTS);
        }
        mapper.partialUpdate(existing, request);
        Brand saved = repo.save(existing);
        publisher.publishUpdated(saved);
        return mapper.toResponse(saved);
    }

    @Override
    @Transactional
    @CacheEvict(value = "brand", allEntries = true)
    public void delete(UUID id) {
        Brand existing = repo.findById(id)
            .orElseThrow(() -> BusinessException.of(ErrorCode.BRAND_NOT_FOUND, id));
        existing.markDeleted(auditorAware.getCurrentAuditor().orElseThrow());
        repo.save(existing);
        publisher.publishDeleted(existing);
    }
}