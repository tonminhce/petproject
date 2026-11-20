package com.shop.common.spring.web.filter;

import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class HttpLoggingFilterBoundedBufferTest {

    private static final int BUFFER_LIMIT = 1024;
    private static final int BODY_SIZE = 5 * 1024 * 1024;

    @Test
    @DisplayName("the retained response buffer stays within the configured cap for a large body")
    void theRetainedResponseBufferStaysWithinTheConfiguredCap() throws Exception {
        HttpLogProperties props = new HttpLogProperties();
        props.getResponse().setMaxBufferBytes(BUFFER_LIMIT);

        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletResponse> wrapped = new AtomicReference<>();
        byte[] body = new byte[BODY_SIZE];

        new HttpLoggingFilter(props).doFilter(request, response, (req, res) -> {
            wrapped.set(res);
            res.getOutputStream().write(body);
        });

        BoundedContentCachingResponseWrapper wrapper =
                (BoundedContentCachingResponseWrapper) wrapped.get();

        assertThat(wrapper.getContentAsByteArray().length).isLessThanOrEqualTo(BUFFER_LIMIT);
        assertThat(wrapper.isTruncated()).isTrue();
        assertThat(response.getContentAsByteArray()).hasSize(BODY_SIZE);
    }

    @Test
    @DisplayName("a small response is retained completely")
    void aSmallResponseIsRetainedCompletely() throws Exception {
        HttpLogProperties props = new HttpLogProperties();
        props.getResponse().setMaxBufferBytes(BUFFER_LIMIT);

        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletResponse> wrapped = new AtomicReference<>();
        byte[] body = "{\"ok\":true}".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        new HttpLoggingFilter(props).doFilter(request, response, (req, res) -> {
            wrapped.set(res);
            res.getOutputStream().write(body);
        });

        BoundedContentCachingResponseWrapper wrapper =
                (BoundedContentCachingResponseWrapper) wrapped.get();

        assertThat(wrapper.getContentAsByteArray()).isEqualTo(body);
        assertThat(wrapper.isTruncated()).isFalse();
    }

    @Test
    @DisplayName("the wrapper never retains more than the configured cap even when written directly")
    void theWrapperNeverRetainsMoreThanTheConfiguredCap() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        BoundedContentCachingResponseWrapper wrapper =
                new BoundedContentCachingResponseWrapper(response, 64);

        wrapper.getOutputStream().write(new byte[4096]);

        assertThat(wrapper.getContentAsByteArray().length).isEqualTo(64);
        assertThat(wrapper.isTruncated()).isTrue();
        assertThat(response.getContentAsByteArray()).hasSize(4096);
    }
}