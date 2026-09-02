package com.shop.orderservice.controller;

import com.shop.common.core.constants.ApiPaths;
import com.shop.common.core.viewmodel.ApiResponse;
import com.shop.common.logging.audit.AuditActorResolver;
import com.shop.common.logging.audit.AuditEvent;
import com.shop.common.logging.audit.Audited;
import com.shop.common.security.jwt.AuthenticatedUser;
import com.shop.orderservice.dto.response.OrderResponse;
import com.shop.orderservice.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Admin / service-to-service status transitions. Authorization is endpoint-level
 * ({@code SERVICE or ADMIN}); the service layer only enforces the state machine,
 * so no per-call role inspection is needed here.
 */
@RestController
@RequestMapping(ApiPaths.ORDERS)
@RequiredArgsConstructor
@PreAuthorize("hasRole('SERVICE') or hasRole('ADMIN')")
public class OrderStatusController {

    private final OrderService orderService;

    @PostMapping("/{orderId}/confirm")
    @Audited(action = "order.confirm", resourceType = "order")
    public ApiResponse<OrderResponse> confirm(@PathVariable UUID orderId,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return ApiResponse.ok(orderService.confirmOrder(orderId, resolveActor(), idempotencyKey));
    }

    @PostMapping("/{orderId}/ship")
    @Audited(action = "order.ship", resourceType = "order")
    public ApiResponse<OrderResponse> ship(@PathVariable UUID orderId) {
        return ApiResponse.ok(orderService.shipOrder(orderId));
    }

    @PostMapping("/{orderId}/deliver")
    @Audited(action = "order.deliver", resourceType = "order")
    public ApiResponse<OrderResponse> deliver(@PathVariable UUID orderId) {
        return ApiResponse.ok(orderService.deliverOrder(orderId));
    }

    /**
     * H-6 actor label by token shape: ADMIN → {@code sub} (the human id),
     * SERVICE → {@code service:<azp>} (the machine identity). Reuses the audit
     * fleet's {@link AuditActorResolver} — the same KC26-probed claim rules the
     * {@code @Audited} aspect applies — so idempotency rows and audit lines can
     * never disagree about who confirmed an order.
     */
    private static String resolveActor() {
        AuthenticatedUser.requireCurrent();  // fail fast when unauthenticated (401 semantics preserved)
        AuditActorResolver.Actor actor = AuditActorResolver.resolve();
        return AuditEvent.ACTOR_TYPE_SERVICE.equals(actor.type())
            ? "service:" + actor.id()
            : actor.id();
    }
}