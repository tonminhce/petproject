package com.shop.searchservice.controller;

import com.shop.common.core.constants.ApiPaths;
import com.shop.common.core.viewmodel.ApiResponse;
import com.shop.common.logging.audit.Audited;
import com.shop.searchservice.dto.request.ReindexRequest;
import com.shop.searchservice.dto.response.ReindexResponse;
import com.shop.searchservice.service.ReindexService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(ApiPaths.BACKOFFICE_SEARCH)
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class BackofficeSearchController {

    private final ReindexService reindexService;

    @PostMapping("/reindex")
    @Audited(action = "search.reindex", resourceType = "search-index")
    public ApiResponse<ReindexResponse> reindex(
            @RequestBody(required = false) ReindexRequest request) {
        boolean dryRun = request != null && Boolean.TRUE.equals(request.dryRun());
        return ApiResponse.ok(reindexService.reindex(dryRun));
    }
}
