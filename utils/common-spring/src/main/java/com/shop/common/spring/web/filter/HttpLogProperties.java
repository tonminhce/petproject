package com.shop.common.spring.web.filter;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for {@link HttpLoggingFilter}, bound from {@code shop.web.logging.*}.
 *
 * <pre>{@code
 * shop:
 *   web:
 *     logging:
 *       request:
 *         enabled: true
 *         include-body: false
 *         max-body-bytes: 4096
 *       response:
 *         enabled: true
 *         include-body: false
 *         max-body-bytes: 4096
 * }</pre>
 */
@ConfigurationProperties(prefix = "shop.web.logging")
public class HttpLogProperties {

    private Request request = new Request();
    private Response response = new Response();

    public Request getRequest() {
        return request;
    }

    public void setRequest(Request request) {
        this.request = request;
    }

    public Response getResponse() {
        return response;
    }

    public void setResponse(Response response) {
        this.response = response;
    }

    public static class Request {
        private boolean enabled = false;
        private boolean includeBody = false;
        private int maxBodyBytes = 4096;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public boolean isIncludeBody() { return includeBody; }
        public void setIncludeBody(boolean includeBody) { this.includeBody = includeBody; }

        public int getMaxBodyBytes() { return maxBodyBytes; }
        public void setMaxBodyBytes(int maxBodyBytes) { this.maxBodyBytes = maxBodyBytes; }
    }

    public static class Response {
        private boolean enabled = false;
        private boolean includeBody = false;
        private int maxBodyBytes = 4096;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public boolean isIncludeBody() { return includeBody; }
        public void setIncludeBody(boolean includeBody) { this.includeBody = includeBody; }

        public int getMaxBodyBytes() { return maxBodyBytes; }
        public void setMaxBodyBytes(int maxBodyBytes) { this.maxBodyBytes = maxBodyBytes; }
    }
}