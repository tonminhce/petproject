package com.shop.mediaservice.controller;

import com.shop.common.core.constants.ApiPaths;
import com.shop.mediaservice.service.MediaQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.URL;
import java.util.UUID;

/**
 * D3 public read surface (P2-6, fleet precedent — deliberately NO
 * {@code @PreAuthorize}: Keycloak users may lack an explicit USER realm role
 * and an unhelpful 403 is worse than the filter chain's own 401 for
 * anonymous callers; authentication is still enforced by the resource-server
 * chain on this edge-routed path).
 *
 * <p>GET answers a 302 whose Location is the presigned GET URL — bytes never
 * stream through media-service. HEAD is the existence check WITHOUT presign
 * (200/404), product's Option C write-time validation endpoint. The two are
 * SEPARATE handlers: an implicit GET→HEAD fallback would run the presign,
 * which the D3 contract forbids for HEAD.</p>
 */
@RestController
@RequestMapping(ApiPaths.MEDIAS)
@RequiredArgsConstructor
public class MediaPublicController {

    private final MediaQueryService mediaQueryService;

    @GetMapping("/{id}")
    public ResponseEntity<Void> get(
            @PathVariable UUID id,
            @RequestParam(required = false, defaultValue = MediaQueryService.DEFAULT_VARIANT) String variant,
            @RequestParam(required = false, defaultValue = MediaQueryService.DEFAULT_FORMAT) MediaFormatQuery format) {
        URL url = mediaQueryService.resolve(id, variant, format.name());
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(url.toString()))
                .build();
    }

    @RequestMapping(value = "/{id}", method = RequestMethod.HEAD)
    public ResponseEntity<Void> head(@PathVariable UUID id) {
        HttpStatus status = mediaQueryService.exists(id) ? HttpStatus.OK : HttpStatus.NOT_FOUND;
        return ResponseEntity.status(status).build();
    }

    /** Wire format of the {@code format} query param — invalid values are a 400 via type mismatch. */
    public enum MediaFormatQuery {
        auto, webp
    }
}
