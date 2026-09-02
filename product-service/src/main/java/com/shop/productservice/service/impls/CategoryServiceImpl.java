package com.shop.productservice.service.impls;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.productservice.dto.request.CategoryCreateRequest;
import com.shop.productservice.dto.request.CategoryUpdateRequest;
import com.shop.productservice.dto.response.CategoryResponse;
import com.shop.productservice.dto.response.CategoryTreeResponse;
import com.shop.productservice.entity.Category;
import com.shop.productservice.mapper.CategoryMapper;
import com.shop.productservice.repository.CategoryRepository;
import com.shop.productservice.service.CategoryEventPublisher;
import com.shop.productservice.service.CategoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.AuditorAware;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CategoryServiceImpl implements CategoryService {

    private final CategoryRepository repo;
    private final CategoryMapper mapper;
    private final CategoryEventPublisher publisher;
    private final AuditorAware<String> auditorAware;

    @Override
    @Transactional(readOnly = true)
    public List<CategoryResponse> findAll() {
        return repo.findAllByOrderByTitleAsc().stream()
            .map(mapper::toResponse)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<CategoryTreeResponse> findTree() {
        List<Category> all = repo.findAllByOrderByTitleAsc();
        Map<UUID, CategoryTreeResponse> nodeMap = new LinkedHashMap<>();
        List<CategoryTreeResponse> roots = new ArrayList<>();
        for (Category c : all) {
            nodeMap.put(c.getId(), mapper.toTreeResponse(c, new ArrayList<>()));
        }
        for (Category c : all) {
            CategoryTreeResponse node = nodeMap.get(c.getId());
            if (c.getParent() == null) {
                roots.add(node);
            } else {
                CategoryTreeResponse parent = nodeMap.get(c.getParent().getId());
                if (parent != null) {
                    parent.children().add(node);
                }
            }
        }
        return roots;
    }

    @Override
    @Transactional(readOnly = true)
    @Cacheable(value = "category", key = "#id")
    public CategoryResponse findById(UUID id) {
        return repo.findById(id)
            .map(mapper::toResponse)
            .orElseThrow(() -> BusinessException.of(ErrorCode.CATEGORY_NOT_FOUND, id));
    }

    @Override
    @Transactional
    public CategoryResponse create(CategoryCreateRequest request) {
        if (repo.existsBySlug(request.slug())) {
            throw BusinessException.of(ErrorCode.CATEGORY_SLUG_EXISTS);
        }
        Category category = mapper.toEntity(request);
        if (request.parentId() != null) {
            // C11 fix — refuse to link under a parent that is, or descends from,
            // a row already in this category's future chain. Without this check
            // a malicious or buggy client could build a cycle that breaks the
            // /api/v1/categories/tree response (infinite loop while building the
            // child list).
            assertNoCycle(null, request.parentId());
            Category parent = repo.findById(request.parentId())
                .orElseThrow(() -> BusinessException.of(ErrorCode.CATEGORY_NOT_FOUND, request.parentId()));
            category.setParent(parent);
        }
        Category saved = repo.save(category);
        publisher.publishCreated(saved);
        return mapper.toResponse(saved);
    }

    @Override
    @Transactional
    @CachePut(value = "category", key = "#id")
    public CategoryResponse update(UUID id, CategoryUpdateRequest request) {
        Category existing = repo.findById(id)
            .orElseThrow(() -> BusinessException.of(ErrorCode.CATEGORY_NOT_FOUND, id));
        if (request.slug() != null && repo.existsBySlugAndIdNot(request.slug(), id)) {
            throw BusinessException.of(ErrorCode.CATEGORY_SLUG_EXISTS);
        }
        mapper.partialUpdate(existing, request);
        if (request.parentId() != null) {
            // C11 fix — see create(); the cycle window here is wider because
            // the existing node may already be part of a chain that loops back
            // through the new parent.
            assertNoCycle(id, request.parentId());
            Category parent = repo.findById(request.parentId())
                .orElseThrow(() -> BusinessException.of(ErrorCode.CATEGORY_NOT_FOUND, request.parentId()));
            existing.setParent(parent);
        }
        Category saved = repo.save(existing);
        publisher.publishUpdated(saved);
        return mapper.toResponse(saved);
    }

    /**
     * C11 fix — verify that linking {@code categoryId} under {@code newParentId}
     * would not form a cycle. {@code categoryId} is null for {@code create}
     * (the new node has no descendants yet) and non-null for {@code update}.
     *
     * <p>Walks up the parent chain from {@code newParentId} bounded by
     * {@link #MAX_PARENT_DEPTH} so a corrupt chain can't trigger an unbounded
     * load. Returns silently on a healthy tree; throws {@code CYCLE_DETECTED}
     * otherwise.</p>
     */
    private void assertNoCycle(UUID categoryId, UUID newParentId) {
        if (newParentId == null) {
            return;
        }
        UUID cursor = newParentId;
        for (int depth = 0; depth < MAX_PARENT_DEPTH; depth++) {
            if (categoryId != null && cursor.equals(categoryId)) {
                throw BusinessException.of(ErrorCode.CATEGORY_CYCLE_DETECTED);
            }
            Category node = repo.findById(cursor).orElse(null);
            if (node == null || node.getParent() == null) {
                return;
            }
            cursor = node.getParent().getId();
        }
        throw BusinessException.of(ErrorCode.CATEGORY_CYCLE_DETECTED);
    }

    private static final int MAX_PARENT_DEPTH = 64;

    @Override
    @Transactional
    @CacheEvict(value = "category", allEntries = true)
    public void delete(UUID id) {
        Category existing = repo.findById(id)
            .orElseThrow(() -> BusinessException.of(ErrorCode.CATEGORY_NOT_FOUND, id));
        existing.markDeleted(auditorAware.getCurrentAuditor().orElseThrow());
        repo.save(existing);
        publisher.publishDeleted(existing);
    }
}