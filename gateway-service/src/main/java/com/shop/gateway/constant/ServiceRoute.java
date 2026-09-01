package com.shop.gateway.constant;

import com.shop.gateway.routing.ApiPaths;

import java.util.Arrays;
import java.util.stream.Stream;

public enum ServiceRoute {

    AUTH("auth-service", "auth", "auth-service", 8088),
    USERS("auth-users", "users", "auth-service", 8088),
    ROLES("auth-roles", "roles", "auth-service", 8088),
    FAVOURITE("favourite-service", "favourites", "favourite-service", 8081),
    INVENTORY("inventory-service", "inventory", "inventory-service", 8082),
    MEDIA("media-service", "medias", "media-service", 8083),
    ORDER("order-service", "orders", "order-service", 8084),
    PAYMENT("payment-service", "payments", "payment-service", 8085),
    PRODUCT("product-service", "products", "product-service", 8086),
    SHIPPING("shipping-service", "shipping", "shipping-service", 8087),
    PAYMENT_WEBHOOK("payment-webhooks", "webhooks/payments", "payment-service", 8085),
    SHIPPING_WEBHOOK("shipping-webhooks", "webhooks/shipping", "shipping-service", 8087),
    RATING("rating-service", "ratings", "rating-service", 8089),
    NOTIFICATION("notification-service", "notifications", "notification-service", 8090),
    TAX("tax-service", "tax", "tax-service", 8091),
    PROMOTION("promotion-service", "promotions", "promotion-service", 8093),
    SEARCH("search-service", "search", "search-service", 8094),

    // ---- Backoffice edge routes (D1) — ADMIN realm-role gated at the gateway ----
    BACKOFFICE_RATINGS("backoffice-ratings", "backoffice/ratings", "rating-service", 8089),
    BACKOFFICE_PRODUCTS("backoffice-products", "backoffice/products", "product-service", 8086),
    BACKOFFICE_SEARCH("backoffice-search", "backoffice/search", "search-service", 8094),
    BACKOFFICE_PROMOTIONS("backoffice-promotions", "backoffice/promotions", "promotion-service", 8093),
    BACKOFFICE_TAX_CLASSES("backoffice-tax-classes", "backoffice/tax-classes", "tax-service", 8091),
    BACKOFFICE_TAX_RATES("backoffice-tax-rates", "backoffice/tax-rates", "tax-service", 8091),
    BACKOFFICE_NOTIFICATIONS("backoffice-notifications", "backoffice/notifications", "notification-service", 8090),
    BACKOFFICE_PAYMENTS("backoffice-payments", "backoffice/payments", "payment-service", 8085),
    BACKOFFICE_SHIPMENTS("backoffice-shipments", "backoffice/shipments", "shipping-service", 8087),
    BACKOFFICE_MEDIAS("backoffice-medias", "backoffice/medias", "media-service", 8083);

    private static final String BACKOFFICE_RESOURCE_PREFIX = "backoffice/";

    private final String id;
    private final String resource;
    private final String serviceName;
    private final int port;

    ServiceRoute(final String id, final String resource, final String serviceName, final int port) {
        this.id = id;
        this.resource = resource;
        this.serviceName = serviceName;
        this.port = port;
    }

    public String id() {
        return id;
    }

    public String resource() {
        return resource;
    }

    public String serviceName() {
        return serviceName;
    }

    public int port() {
        return port;
    }

    public String path() {
        return ApiPaths.resourcePath(resource);
    }

    /**
     * Route prefix without the trailing {@code /**} wildcard, e.g.
     * {@code /api/v1/backoffice/ratings} — used by the edge filters for
     * scope and role-gate matching.
     */
    public String prefix() {
        return ApiPaths.API_V1_PREFIX + "/" + resource;
    }

    /**
     * True for the D1 backoffice edge routes that require the ADMIN realm role.
     */
    public boolean backoffice() {
        return resource.startsWith(BACKOFFICE_RESOURCE_PREFIX);
    }

    public static Stream<ServiceRoute> backofficeRoutes() {
        return Arrays.stream(values()).filter(ServiceRoute::backoffice);
    }

    public String uri() {
        return ApiPaths.serviceUri(serviceName, port);
    }
}
