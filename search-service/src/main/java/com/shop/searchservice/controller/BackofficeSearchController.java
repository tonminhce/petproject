package com.shop.searchservice.controller;

import com.shop.common.core.constants.ApiPaths;
import com.shop.common.core.viewmodel.ApiResponse;
import com.shop.searchservice.dto.request.ReindexRequest;
import com.shop.searchservice.dto.response.ReindexResponse;
import com.shop.searchservice.service.ReindexService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Backoffice search administration (spec D5). ADMIN only — the reindex is an
 * expensive streaming operation (product-service scan + full index rebuild)
 * and deliberately NOT auto-started (ops §4(1)).
 */
@RestController
@RequestMapping(ApiPaths.BACKOFFICE_SEARCH)
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class BackofficeSearchController {

    private final ReindexService reindexService;

    /**
     * Body optional: {@code {"dryRun":true}} counts the ACTIVE source rows
     * without creating an index; no body (or {@code false}) runs the full
     * stream + atomic alias swap.
     */
    @PostMapping("/reindex")
    public ApiResponse<ReindexResponse> reindex(
            @RequestBody(required = false) ReindexRequest request) {
        boolean dryRun = request != null && Boolean.TRUE.equals(request.dryRun());
        return ApiResponse.ok(reindexService.reindex(dryRun));
    }
}
