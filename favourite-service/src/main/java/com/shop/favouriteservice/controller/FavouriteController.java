package com.shop.favouriteservice.controller;

import com.shop.common.core.constants.ApiPaths;
import com.shop.common.core.constants.PageableConstant;
import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.viewmodel.ApiResponse;
import com.shop.common.core.viewmodel.PageResponse;
import com.shop.common.security.jwt.AuthenticatedUser;
import com.shop.favouriteservice.dto.request.FavouriteCreateRequest;
import com.shop.favouriteservice.dto.response.FavouriteResponse;
import com.shop.favouriteservice.service.FavouriteService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

// NO @PreAuthorize (rev 2): auth is enforced by BaseSecurityConfig filter chain
// (anyRequest().authenticated() — public-paths is empty). Class-level annotation
// is redundant and creates ambiguous method-security semantics inside the
// @WebMvcTest slice. Matches the ProductController pattern.
@RestController
@RequestMapping(ApiPaths.FAVOURITES)
@RequiredArgsConstructor
public class FavouriteController {

    private final FavouriteService service;

    @GetMapping
    public ApiResponse<PageResponse<FavouriteResponse>> findAll(
            @RequestParam(defaultValue = "" + PageableConstant.DEFAULT_PAGE_NUMBER) int page,
            @RequestParam(defaultValue = "" + PageableConstant.DEFAULT_PAGE_SIZE) int size) {
        // Cap page size — PageableConstant.MAX_PAGE_SIZE guards against ?size=100000 dumps.
        Pageable pageable = PageRequest.of(page, Math.min(size, PageableConstant.MAX_PAGE_SIZE));
        return ApiResponse.ok(service.findAllByCurrentUser(currentUserId(), pageable));
    }

    @GetMapping("/{favouriteId}")
    public ApiResponse<FavouriteResponse> findById(@PathVariable UUID favouriteId) {
        return ApiResponse.ok(service.findById(favouriteId, currentUserId()));
    }

    @PostMapping
    public ApiResponse<FavouriteResponse> create(@Valid @RequestBody FavouriteCreateRequest request) {
        return ApiResponse.ok(service.create(currentUserId(), request),
                "Favourite added successfully");
    }

    @DeleteMapping("/{favouriteId}")
    public ApiResponse<Void> deleteById(@PathVariable UUID favouriteId) {
        service.deleteById(favouriteId, currentUserId());
        return ApiResponse.message("Favourite removed successfully");
    }

    @DeleteMapping("/by-product/{productId}")
    public ApiResponse<Void> deleteByProduct(@PathVariable UUID productId) {
        service.deleteByProductId(currentUserId(), productId);
        return ApiResponse.message("Favourite removed successfully");
    }

    private static UUID currentUserId() {
        String sub = AuthenticatedUser.requireCurrent().id();
        try {
            return UUID.fromString(sub);
        } catch (IllegalArgumentException ex) {
            // Defensive — valid Keycloak JWT subjects are always UUIDs. Prevents a
            // 500 if a non-UUID subject is ever introduced.
            throw BusinessException.unauthorized("favourite.user.subject.malformed");
        }
    }
}
