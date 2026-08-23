package com.shop.gateway.routing;

public final class ApiPaths {

    public static final String API_V1_PREFIX = "/api/v1";

    public static final String ACTUATOR_BASE = "/actuator";
    public static final String ACTUATOR_HEALTH = ACTUATOR_BASE + "/health";
    public static final String ACTUATOR_INFO = ACTUATOR_BASE + "/info";
    public static final String ACTUATOR_PROMETHEUS = ACTUATOR_BASE + "/prometheus";

    public static final String API_DOCS = "/v3/api-docs";
    public static final String SWAGGER_UI = "/swagger-ui";
    public static final String SWAGGER_UI_HTML = SWAGGER_UI + "/**";
    public static final String WEBJARS = "/webjars";

    private ApiPaths() {
    }

    public static String resourcePath(final String resource) {
        return API_V1_PREFIX + "/" + resource + "/**";
    }

    public static String serviceUri(final String serviceName, final int port) {
        return "http://" + serviceName + ":" + port;
    }
}