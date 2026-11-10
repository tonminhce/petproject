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
