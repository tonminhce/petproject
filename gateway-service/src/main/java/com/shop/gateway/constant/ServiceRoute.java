package com.shop.gateway.constant;

import com.shop.gateway.routing.ApiPaths;

public enum ServiceRoute {

    AUTH("auth-service", "auth", "auth-service", 8088),
    USERS("auth-users", "users", "auth-service", 8088),
    ROLES("auth-roles", "roles", "auth-service", 8088),
    FAVOURITE("favourite-service", "favourites", "favourite-service", 8081),
    INVENTORY("inventory-service", "inventory", "inventory-service", 8082),
    MEDIA("media-service", "media", "media-service", 8083),
    ORDER("order-service", "orders", "order-service", 8084),
    PAYMENT("payment-service", "payments", "payment-service", 8085),
    PRODUCT("product-service", "products", "product-service", 8086),
    SHIPPING("shipping-service", "shipping", "shipping-service", 8087),
    RATING("rating-service", "ratings", "rating-service", 8089),
    NOTIFICATION("notification-service", "notifications", "notification-service", 8090),
    TAX("tax-service", "tax", "tax-service", 8091),
    PROMOTION("promotion-service", "promotions", "promotion-service", 8093),
    SEARCH("search-service", "search", "search-service", 8094);

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

    public String uri() {
        return ApiPaths.serviceUri(serviceName, port);
    }
}
