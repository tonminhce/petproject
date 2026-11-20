package com.shop.common.spring.web.filter;

import com.shop.common.core.constants.MdcKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationIdFilterTest {

    private static final String SCRIPT_HEADER = "<script>alert(1)</script>";

    @Test
    @DisplayName("a script payload in the correlation header is replaced by a generated identifier")
    void aScriptPayloadInTheCorrelationHeaderIsReplaced() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, SCRIPT_HEADER);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> correlationId = new AtomicReference<>();
        AtomicReference<String> requestId = new AtomicReference<>();

        new CorrelationIdFilter().doFilter(request, response, (req, res) -> {
            correlationId.set(MDC.get(MdcKey.CORRELATION_ID));
            requestId.set(MDC.get(MdcKey.REQUEST_ID));
        });

        assertThat(correlationId.get())
                .isNotEqualTo(SCRIPT_HEADER)
                .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        assertThat(requestId.get()).matches("[0-9a-f-]{36}");
        assertThat(response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER))
                .isEqualTo(correlationId.get());
    }

    @Test
    @DisplayName("an oversized correlation header never reaches the MDC")
    void anOversizedCorrelationHeaderNeverReachesTheMdc() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, "A".repeat(10_000));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> correlationId = new AtomicReference<>();

        new CorrelationIdFilter().doFilter(request, response, (req, res) ->
                correlationId.set(MDC.get(MdcKey.CORRELATION_ID)));

        assertThat(correlationId.get())
                .hasSizeLessThanOrEqualTo(CorrelationIdFilter.MAX_IDENTIFIER_LENGTH)
                .isNotEqualTo("A".repeat(10_000));
        assertThat(response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER))
                .hasSizeLessThanOrEqualTo(CorrelationIdFilter.MAX_IDENTIFIER_LENGTH);
    }

    @Test
    @DisplayName("a header carrying a line break is rejected")
    void aHeaderCarryingALineBreakIsRejected() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, "abc\r\ndefg");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> correlationId = new AtomicReference<>();

        new CorrelationIdFilter().doFilter(request, response, (req, res) ->
                correlationId.set(MDC.get(MdcKey.CORRELATION_ID)));

        assertThat(correlationId.get()).doesNotContain("\n").doesNotContain("\r");
    }

    @Test
    @DisplayName("a well-formed correlation header is preserved")
    void aWellFormedCorrelationHeaderIsPreserved() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, "order-4711_abc");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> correlationId = new AtomicReference<>();

        new CorrelationIdFilter().doFilter(request, response, (req, res) ->
                correlationId.set(MDC.get(MdcKey.CORRELATION_ID)));

        assertThat(correlationId.get()).isEqualTo("order-4711_abc");
        assertThat(response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER)).isEqualTo("order-4711_abc");
    }
}