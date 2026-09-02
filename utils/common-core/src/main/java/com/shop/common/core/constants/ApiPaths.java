package com.shop.common.core.constants;

/**
 * Canonical API path prefixes. Every service must pin its {@code @RequestMapping}
 * to one of these constants so versioning stays consistent across the platform.
 */
public final class ApiPaths {

    public static final String API = "/api";
    public static final String V1 = "/v1";
    public static final String API_V1 = API + V1;

    // ---- Auth domain ----
    public static final String AUTH = API_V1 + "/auth";
    public static final String USERS = API_V1 + "/users";
    public static final String ROLES = API_V1 + "/roles";

    // ---- Catalog ----
    public static final String PRODUCTS = API_V1 + "/products";
    public static final String CATEGORIES = API_V1 + "/categories";
    public static final String BRANDS = API_V1 + "/brands";

    // ---- Commerce ----
    public static final String CARTS = API_V1 + "/carts";
    public static final String ORDERS = API_V1 + "/orders";
    public static final String PAYMENTS = API_V1 + "/payments";
    public static final String INVENTORY = API_V1 + "/inventory";
    public static final String SHIPPINGS = API_V1 + "/shippings";
    public static final String FAVOURITES = API_V1 + "/favourites";
    public static final String PROMOTIONS = API_V1 + "/promotions";
    public static final String MEDIAS = API_V1 + "/medias";
    public static final String BACKOFFICE_MEDIAS = API_V1 + "/backoffice/medias";

    // ---- Notifications ----
    public static final String NOTIFICATIONS = API_V1 + "/notifications";
    public static final String PAYMENT_NOTIFICATIONS = API_V1 + "/payment-notifications";
    public static final String EMAILS = API_V1 + "/emails";

    // ---- Ratings ----
    public static final String RATINGS = API_V1 + "/ratings";

    // ---- Product internal (SERVICE-token gated, fleet-internal only) ----
    public static final String INTERNAL_PRODUCT_MEDIA_REFERENCES = "/internal/products/media-references";

    // ---- Storefront / Backoffice ----
    public static final String BACKOFFICE_RATINGS = API_V1 + "/backoffice/ratings";
    public static final String BACKOFFICE_PRODUCTS = API_V1 + "/backoffice/products";
    public static final String BACKOFFICE_CATEGORIES = API_V1 + "/backoffice/categories";
    public static final String BACKOFFICE_BRANDS = API_V1 + "/backoffice/brands";
    public static final String SEARCH = API_V1 + "/search";
    public static final String BACKOFFICE_SEARCH = API_V1 + "/backoffice/search";
    public static final String BACKOFFICE_PROMOTIONS = API_V1 + "/backoffice/promotions";
    public static final String BACKOFFICE_TAX_CLASSES = API_V1 + "/backoffice/tax-classes";
    public static final String BACKOFFICE_TAX_RATES = API_V1 + "/backoffice/tax-rates";
    public static final String BACKOFFICE_NOTIFICATIONS = API_V1 + "/backoffice/notifications";

    // ---- Payments ----
    public static final String BACKOFFICE_PAYMENTS = API_V1 + "/backoffice/payments";
    public static final String WEBHOOK_PAYMENTS = API_V1 + "/webhooks/payments";

    // ---- Shipping ----
    public static final String BACKOFFICE_SHIPMENTS = API_V1 + "/backoffice/shipments";
    public static final String WEBHOOK_SHIPPING = API_V1 + "/webhooks/shipping";

    private ApiPaths() {
    }
}
