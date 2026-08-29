package com.shop.productservice.service.impls;

import com.shop.common.core.exception.BusinessException;
import com.shop.productservice.dto.request.CategoryCreateRequest;
import com.shop.productservice.dto.request.CategoryUpdateRequest;
import com.shop.productservice.dto.response.CategoryResponse;
import com.shop.productservice.dto.response.CategoryTreeResponse;
import com.shop.productservice.entity.Category;
import com.shop.productservice.mapper.CategoryMapper;
import com.shop.productservice.repository.CategoryRepository;
import com.shop.productservice.service.CategoryEventPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.AuditorAware;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CategoryServiceImplTest {

    @Mock CategoryRepository repo;
    @Mock CategoryMapper mapper;
    @Mock CategoryEventPublisher publisher;
    @Mock AuditorAware<String> auditorAware;
    @InjectMocks CategoryServiceImpl service;

    private static final UUID ROOT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID CHILD1 = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID CHILD2 = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID GRANDCHILD = UUID.fromString("00000000-0000-0000-0000-000000000004");

    @Test
    void findTree_buildsNestedStructure() {
        Category root = Category.builder().id(ROOT).title("Electronics").slug("electronics").build();
        Category child1 = Category.builder().id(CHILD1).title("Phones").slug("phones").parent(root).build();
        Category child2 = Category.builder().id(CHILD2).title("Laptops").slug("laptops").parent(root).build();
        Category grandchild = Category.builder().id(GRANDCHILD).title("iPhone").slug("iphone").parent(child1).build();
        when(repo.findAllByOrderByTitleAsc())
            .thenReturn(List.of(root, child1, child2, grandchild));
        when(mapper.toTreeResponse(eq(root),     any())).thenAnswer(inv -> new CategoryTreeResponse(ROOT, "Electronics", "electronics", null, null, inv.getArgument(1)));
        when(mapper.toTreeResponse(eq(child1),   any())).thenAnswer(inv -> new CategoryTreeResponse(CHILD1, "Phones", "phones", null, ROOT, inv.getArgument(1)));
        when(mapper.toTreeResponse(eq(child2),   any())).thenAnswer(inv -> new CategoryTreeResponse(CHILD2, "Laptops", "laptops", null, ROOT, inv.getArgument(1)));
        when(mapper.toTreeResponse(eq(grandchild), any())).thenAnswer(inv -> new CategoryTreeResponse(GRANDCHILD, "iPhone", "iphone", null, CHILD1, inv.getArgument(1)));

        List<CategoryTreeResponse> tree = service.findTree();

        assertThat(tree).hasSize(1);
        CategoryTreeResponse rootResp = tree.get(0);
        assertThat(rootResp.title()).isEqualTo("Electronics");
        assertThat(rootResp.children()).hasSize(2);
        assertThat(rootResp.children().stream().filter(c -> c.title().equals("Phones")).findFirst().orElseThrow().children())
            .extracting(CategoryTreeResponse::title).containsExactly("iPhone");
    }

    @Test
    void update_throwsConflictOnDuplicateSlug() {
        Category existing = Category.builder().id(ROOT).title("Electronics").slug("electronics").build();
        CategoryUpdateRequest req = new CategoryUpdateRequest(null, "taken", null, null);
        when(repo.findById(ROOT)).thenReturn(Optional.of(existing));
        when(repo.existsBySlugAndIdNot("taken", ROOT)).thenReturn(true);

        assertThatThrownBy(() -> service.update(ROOT, req))
            .isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo("PRD-2008"));
    }

    @Test
    void findById_throwsWhenNotFound() {
        when(repo.findById(ROOT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById(ROOT)).isInstanceOf(BusinessException.class);
    }

    @Test
    void create_persistsWithParentAndPublishes() {
        CategoryCreateRequest req = new CategoryCreateRequest("iPhone", "iphone", null, ROOT);
        Category parent = Category.builder().id(ROOT).title("Electronics").slug("electronics").build();
        Category child = Category.builder().id(CHILD1).title("iPhone").slug("iphone").build();
        CategoryResponse resp = new CategoryResponse(CHILD1, "iPhone", "iphone", null, ROOT);
        when(repo.existsBySlug("iphone")).thenReturn(false);
        when(mapper.toEntity(req)).thenReturn(child);
        when(repo.findById(ROOT)).thenReturn(Optional.of(parent));
        when(repo.save(child)).thenReturn(child);
        when(mapper.toResponse(child)).thenReturn(resp);

        assertThat(service.create(req)).isEqualTo(resp);
        assertThat(child.getParent()).isEqualTo(parent);
        verify(repo).save(child);
        verify(publisher).publishCreated(child);
    }

    @Test
    void create_throwsWhenParentNotFound() {
        CategoryCreateRequest req = new CategoryCreateRequest("iPhone", "iphone", null, ROOT);
        when(repo.existsBySlug("iphone")).thenReturn(false);
        when(repo.findById(ROOT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(req))
            .isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo("PRD-2003"));
        verifyNoInteractions(publisher);
        verify(repo, never()).save(any());
    }

    @Test
    void update_throwsWhenParentNotFound() {
        Category existing = Category.builder().id(ROOT).title("Electronics").slug("electronics").build();
        CategoryUpdateRequest req = new CategoryUpdateRequest(null, null, null, CHILD1);
        when(repo.findById(ROOT)).thenReturn(Optional.of(existing));
        when(repo.findById(CHILD1)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(ROOT, req))
            .isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo("PRD-2003"));
        verifyNoInteractions(publisher);
    }

    @Test
    void update_persistsAndPublishes() {
        Category existing = Category.builder().id(ROOT).title("Electronics").slug("electronics").build();
        CategoryUpdateRequest req = new CategoryUpdateRequest("Consumer Tech", null, null, null);
        CategoryResponse resp = new CategoryResponse(ROOT, "Consumer Tech", "electronics", null, null);
        when(repo.findById(ROOT)).thenReturn(Optional.of(existing));
        when(repo.save(existing)).thenReturn(existing);
        when(mapper.toResponse(existing)).thenReturn(resp);

        assertThat(service.update(ROOT, req).title()).isEqualTo("Consumer Tech");
        verify(mapper).partialUpdate(existing, req);
        verify(publisher).publishUpdated(existing);
    }

    @Test
    void delete_softDeletesWithActorAndPublishes() {
        Category existing = Category.builder().id(ROOT).title("Electronics").slug("electronics").build();
        when(repo.findById(ROOT)).thenReturn(Optional.of(existing));
        when(auditorAware.getCurrentAuditor()).thenReturn(Optional.of("alice"));

        service.delete(ROOT);

        assertThat(existing.isDeleted()).isTrue();
        assertThat(existing.getDeletedBy()).isEqualTo("alice");
        verify(repo).save(existing);
        verify(publisher).publishDeleted(existing);
    }
}