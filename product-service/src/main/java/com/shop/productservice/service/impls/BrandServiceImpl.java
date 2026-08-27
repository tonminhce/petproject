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
import com.shop.productservice.service.BrandService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class BrandServiceImpl implements BrandService {

    private final BrandRepository repo;
    private final BrandMapper mapper;
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
    public BrandResponse findById(Long id) {
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
        return mapper.toResponse(repo.save(brand));
    }

    @Override
    @Transactional
    public BrandResponse update(Long id, BrandUpdateRequest request) {
        Brand existing = repo.findById(id)
            .orElseThrow(() -> BusinessException.of(ErrorCode.BRAND_NOT_FOUND, id));
        if (request.slug() != null && repo.existsBySlugAndIdNot(request.slug(), id)) {
            throw BusinessException.of(ErrorCode.BRAND_SLUG_EXISTS);
        }
        mapper.partialUpdate(existing, request);
        return mapper.toResponse(repo.save(existing));
    }

    @Override
    @Transactional
    public void delete(Long id) {
        Brand existing = repo.findById(id)
            .orElseThrow(() -> BusinessException.of(ErrorCode.BRAND_NOT_FOUND, id));
        existing.markDeleted(auditorAware.getCurrentAuditor().orElse("system"));
        repo.save(existing);
    }
}