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
import com.shop.productservice.service.CategoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.AuditorAware;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CategoryServiceImpl implements CategoryService {

    private final CategoryRepository repo;
    private final CategoryMapper mapper;
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
        Map<Long, CategoryTreeResponse> nodeMap = new LinkedHashMap<>();
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
    public CategoryResponse findById(Long id) {
        return repo.findById(id)
            .map(mapper::toResponse)
            .orElseThrow(() -> BusinessException.of(ErrorCode.CATEGORY_NOT_FOUND, id));
    }

    @Override
    @Transactional
    public CategoryResponse create(CategoryCreateRequest request) {
        if (repo.existsBySlug(request.slug())) {
            throw BusinessException.conflict("category.slug.exists");
        }
        Category category = mapper.toEntity(request);
        if (request.parentId() != null) {
            Category parent = repo.findById(request.parentId())
                .orElseThrow(() -> BusinessException.of(ErrorCode.CATEGORY_NOT_FOUND, request.parentId()));
            category.setParent(parent);
        }
        return mapper.toResponse(repo.save(category));
    }

    @Override
    @Transactional
    public CategoryResponse update(Long id, CategoryUpdateRequest request) {
        Category existing = repo.findById(id)
            .orElseThrow(() -> BusinessException.of(ErrorCode.CATEGORY_NOT_FOUND, id));
        mapper.partialUpdate(existing, request);
        if (request.parentId() != null) {
            Category parent = repo.findById(request.parentId())
                .orElseThrow(() -> BusinessException.of(ErrorCode.CATEGORY_NOT_FOUND, request.parentId()));
            existing.setParent(parent);
        }
        return mapper.toResponse(repo.save(existing));
    }

    @Override
    @Transactional
    public void delete(Long id) {
        Category existing = repo.findById(id)
            .orElseThrow(() -> BusinessException.of(ErrorCode.CATEGORY_NOT_FOUND, id));
        existing.markDeleted(auditorAware.getCurrentAuditor().orElse("system"));
        repo.save(existing);
    }
}