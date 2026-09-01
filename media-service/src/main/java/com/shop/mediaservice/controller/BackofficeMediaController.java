package com.shop.mediaservice.controller;

import com.shop.common.core.constants.ApiPaths;
import com.shop.common.core.viewmodel.ApiResponse;
import com.shop.mediaservice.dto.response.MediaResponse;
import com.shop.mediaservice.service.MediaLifecycleService;
import com.shop.mediaservice.service.MediaUploadService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

/**
 * D3 backoffice surface — ADMIN-gated upload (multipart → 201 created, or
 * 200 + {@code duplicate:true} when the SHA-256 dedup resolves to the
 * existing media) and soft delete (repeat delete → 409 MED-12005).
 * Class-level {@code hasRole('ADMIN')} mirrors the fleet backoffice pattern
 * (BackofficeSearchController / BackofficeRatingController).
 */
@RestController
@RequestMapping(ApiPaths.BACKOFFICE_MEDIAS)
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class BackofficeMediaController {

    private final MediaUploadService uploadService;
    private final MediaLifecycleService lifecycleService;

    @PostMapping
    public ResponseEntity<ApiResponse<MediaResponse>> upload(
            @RequestParam("file") MultipartFile file) {
        MediaResponse response = uploadService.upload(file);
        HttpStatus status = response.duplicate() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(ApiResponse.ok(response));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        lifecycleService.softDelete(id);
        return ApiResponse.message("Media deleted successfully");
    }
}
