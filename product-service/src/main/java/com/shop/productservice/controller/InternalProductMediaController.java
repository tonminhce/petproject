package com.shop.productservice.controller;

import com.shop.common.core.constants.ApiPaths;
import com.shop.common.core.viewmodel.ApiResponse;
import com.shop.productservice.dto.response.MediaReferenceCountResponse;
import com.shop.productservice.service.ProductMediaService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * H-4 internal surface for media-service's purge gate: how many live products
 * still reference a given media. NOT part of the public API — never routed to
 * browsers; the only caller is media-service's
 * {@code MediaReferenceClient} with a client-credentials SERVICE token.
 *
 * <p>Security: {@code hasRole('SERVICE')} — deliberately NARROWER than the
 * fleet's human-facing {@code hasAnyRole('SERVICE','ADMIN')} convention
 * (order verify-purchase): an internal machine endpoint exists for service
 * accounts, and even an ADMIN user token must be denied (tested). The
 * resource-server chain still rejects anonymous callers with 401 before
 * method security runs.</p>
 */
@RestController
@RequestMapping(ApiPaths.INTERNAL_PRODUCT_MEDIA_REFERENCES)
@RequiredArgsConstructor
public class InternalProductMediaController {

    private final ProductMediaService productMediaService;

    /**
     * @return {@code {mediaId, referenceCount}} — count of LIVE products whose
     *         {@code media_id} points at the given media (0 = safe to purge)
     */
    @GetMapping("/{mediaId}")
    @PreAuthorize("hasRole('SERVICE')")
    public ApiResponse<MediaReferenceCountResponse> referenceCount(@PathVariable UUID mediaId) {
        return ApiResponse.ok(new MediaReferenceCountResponse(
                mediaId, productMediaService.referenceCount(mediaId)));
    }
}
