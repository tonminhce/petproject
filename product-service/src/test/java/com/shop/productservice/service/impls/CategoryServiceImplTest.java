package com.shop.productservice.service.impls;

import com.shop.common.core.exception.BusinessException;
import com.shop.productservice.dto.response.CategoryTreeResponse;
import com.shop.productservice.entity.Category;
import com.shop.productservice.mapper.CategoryMapper;
import com.shop.productservice.repository.CategoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CategoryServiceImplTest {

    @Mock CategoryRepository repo;
    @Mock CategoryMapper mapper;
    @InjectMocks CategoryServiceImpl service;

    @Test
    void findTree_buildsNestedStructure() {
        Category root = Category.builder().id(1L).title("Electronics").slug("electronics").build();
        Category child1 = Category.builder().id(2L).title("Phones").slug("phones").parent(root).build();
        Category child2 = Category.builder().id(3L).title("Laptops").slug("laptops").parent(root).build();
        Category grandchild = Category.builder().id(4L).title("iPhone").slug("iphone").parent(child1).build();
        when(repo.findAllByOrderByTitleAsc())
            .thenReturn(List.of(root, child1, child2, grandchild));
        when(mapper.toTreeResponse(eq(root),     any())).thenAnswer(inv -> new CategoryTreeResponse(1L, "Electronics", "electronics", null, null, inv.getArgument(1)));
        when(mapper.toTreeResponse(eq(child1),   any())).thenAnswer(inv -> new CategoryTreeResponse(2L, "Phones", "phones", null, 1L, inv.getArgument(1)));
        when(mapper.toTreeResponse(eq(child2),   any())).thenAnswer(inv -> new CategoryTreeResponse(3L, "Laptops", "laptops", null, 1L, inv.getArgument(1)));
        when(mapper.toTreeResponse(eq(grandchild), any())).thenAnswer(inv -> new CategoryTreeResponse(4L, "iPhone", "iphone", null, 2L, inv.getArgument(1)));

        List<CategoryTreeResponse> tree = service.findTree();

        assertThat(tree).hasSize(1);
        CategoryTreeResponse rootResp = tree.get(0);
        assertThat(rootResp.title()).isEqualTo("Electronics");
        assertThat(rootResp.children()).hasSize(2);
        assertThat(rootResp.children().stream().filter(c -> c.title().equals("Phones")).findFirst().orElseThrow().children())
            .extracting(CategoryTreeResponse::title).containsExactly("iPhone");
    }

    @Test
    void findById_throwsWhenNotFound() {
        when(repo.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById(1L)).isInstanceOf(BusinessException.class);
    }
}