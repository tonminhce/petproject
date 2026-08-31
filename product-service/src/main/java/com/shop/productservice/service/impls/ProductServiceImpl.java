package com.shop.productservice.service.impls;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.common.core.viewmodel.PageResponse;
import com.shop.productservice.dto.ProductFilter;
import com.shop.productservice.dto.request.ProductCreateRequest;
import com.shop.productservice.dto.request.ProductUpdateRequest;
import com.shop.productservice.dto.response.ProductDetailResponse;
import com.shop.productservice.dto.response.ProductSummaryResponse;
import com.shop.productservice.entity.Brand;
import com.shop.productservice.entity.Category;
import com.shop.productservice.entity.Product;
import com.shop.productservice.mapper.ProductMapper;
import com.shop.productservice.repository.BrandRepository;
import com.shop.productservice.repository.CategoryRepository;
import com.shop.productservice.repository.ProductRepository;
import com.shop.productservice.service.ProductEventPublisher;
import com.shop.productservice.service.ProductService;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ProductServiceImpl implements ProductService {

    private final ProductRepository repo;
    private final CategoryRepository categoryRepo;
    private final BrandRepository brandRepo;
    private final ProductMapper mapper;
    private final ProductEventPublisher publisher;
    private final AuditorAware<String> auditorAware;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<ProductSummaryResponse> findAll(ProductFilter filter, Pageable pageable) {
        Page<Product> page = repo.findAll(filterSpec(filter), pageable);
        return PageResponse.of(
            page.map(mapper::toSummaryResponse).getContent(),
            page.getNumber(),
            page.getSize(),
            page.getTotalElements());
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<ProductDetailResponse> findAllDetail(ProductFilter filter, Pageable pageable) {
        Page<Product> page = repo.findAll(filterSpec(filter), pageable);
        return PageResponse.of(
            page.map(mapper::toDetailResponse).getContent(),
            page.getNumber(),
            page.getSize(),
            page.getTotalElements());
    }

    private static Specification<Product> filterSpec(ProductFilter filter) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (filter.categoryId() != null) {
                predicates.add(cb.equal(root.get("category").get("id"), filter.categoryId()));
            }
            if (filter.brandId() != null) {
                predicates.add(cb.equal(root.get("brand").get("id"), filter.brandId()));
            }
            if (filter.status() != null) {
                predicates.add(cb.equal(root.get("status"), filter.status()));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    @Override
    @Transactional(readOnly = true)
    @Cacheable(value = "product", key = "#id")
    public ProductDetailResponse findById(UUID id) {
        return repo.findWithRelationsById(id)
            .map(mapper::toDetailResponse)
            .orElseThrow(() -> BusinessException.of(ErrorCode.PRODUCT_NOT_FOUND, id));
    }

    @Override
    @Transactional(readOnly = true)
    @Cacheable(value = "productBySlug", key = "#slug")
    public ProductDetailResponse findBySlug(String slug) {
        return repo.findWithRelationsBySlug(slug)
            .map(mapper::toDetailResponse)
            .orElseThrow(() -> BusinessException.of(ErrorCode.PRODUCT_NOT_FOUND, "slug=" + slug));
    }

    @Override
    @Transactional
    public ProductDetailResponse create(ProductCreateRequest request) {
        if (repo.existsBySlug(request.slug())) {
            throw BusinessException.of(ErrorCode.PRODUCT_SLUG_EXISTS);
        }
        if (repo.existsBySku(request.sku())) {
            throw BusinessException.of(ErrorCode.PRODUCT_SKU_EXISTS);
        }
        Product product = mapper.toEntity(request);
        if (request.categoryId() != null) {
            Category category = categoryRepo.findById(request.categoryId())
                .orElseThrow(() -> BusinessException.of(ErrorCode.CATEGORY_NOT_FOUND, request.categoryId()));
            product.setCategory(category);
        }
        if (request.brandId() != null) {
            Brand brand = brandRepo.findById(request.brandId())
                .orElseThrow(() -> BusinessException.of(ErrorCode.BRAND_NOT_FOUND, request.brandId()));
            product.setBrand(brand);
        }
        Product saved = repo.save(product);
        publisher.publishCreated(saved);
        return mapper.toDetailResponse(saved);
    }

    @Override
    @Transactional
    @Caching(put = @CachePut(value = "product", key = "#id"),
             evict = @CacheEvict(value = "productBySlug", allEntries = true))
    public ProductDetailResponse update(UUID id, ProductUpdateRequest request) {
        Product existing = repo.findById(id)
            .orElseThrow(() -> BusinessException.of(ErrorCode.PRODUCT_NOT_FOUND, id));
        if (request.slug() != null && repo.existsBySlugAndIdNot(request.slug(), id)) {
            throw BusinessException.of(ErrorCode.PRODUCT_SLUG_EXISTS);
        }
        if (request.sku() != null && repo.existsBySkuAndIdNot(request.sku(), id)) {
            throw BusinessException.of(ErrorCode.PRODUCT_SKU_EXISTS);
        }
        mapper.partialUpdate(existing, request);
        if (request.categoryId() != null) {
            Category category = categoryRepo.findById(request.categoryId())
                .orElseThrow(() -> BusinessException.of(ErrorCode.CATEGORY_NOT_FOUND, request.categoryId()));
            existing.setCategory(category);
        }
        if (request.brandId() != null) {
            Brand brand = brandRepo.findById(request.brandId())
                .orElseThrow(() -> BusinessException.of(ErrorCode.BRAND_NOT_FOUND, request.brandId()));
            existing.setBrand(brand);
        }
        Product saved = repo.save(existing);
        publisher.publishUpdated(saved);
        return mapper.toDetailResponse(saved);
    }

    @Override
    @Transactional
    @CacheEvict(value = {"product", "productBySlug"}, allEntries = true)
    public void delete(UUID id) {
        Product existing = repo.findById(id)
            .orElseThrow(() -> BusinessException.of(ErrorCode.PRODUCT_NOT_FOUND, id));
        existing.markDeleted(auditorAware.getCurrentAuditor().orElseThrow());
        repo.save(existing);
        publisher.publishDeleted(existing);
    }
}