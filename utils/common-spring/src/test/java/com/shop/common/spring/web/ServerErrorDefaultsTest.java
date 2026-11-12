package com.shop.common.spring.web;

import com.shop.common.core.exception.ErrorCode;
import com.shop.common.core.viewmodel.ApiResponse;
import com.shop.common.spring.web.exception.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shared error defaults must not echo internal exception text.
 *
 * <p>{@code server.error.include-message=always} only affects the errors that
 * never reach the platform handler (filter-chain failures, unmapped MVC errors)
 * - and there it echoes SQL fragments, host names and class names straight to
 * the client. The handler itself already emits the curated message: a generic
 * one for 5xx, a detailed one for 4xx client errors.</p>
 */
class ServerErrorDefaultsTest {

    @SuppressWarnings("unchecked")
    private Map<String, Object> errorDefaults() throws Exception {
        ClassPathResource resource = new ClassPathResource("application.yml");
        try (InputStream in = resource.getInputStream()) {
            Map<String, Object> root = new Yaml().load(in);
            Map<String, Object> server = (Map<String, Object>) root.get("server");
            return (Map<String, Object>) server.get("error");
        }
    }

    @Test
    void theSharedDefaultsNeverIncludeTheInternalMessage() throws Exception {
        Map<String, Object> error = errorDefaults();

        assertThat(error.get("include-message"))
                .as("the internal exception text must not reach the client")
                .isEqualTo("never");
        assertThat(error.get("include-stacktrace")).isEqualTo("never");
        assertThat(error.get("include-exception")).isEqualTo(false);
        assertThat(error.get("include-binding-errors"))
                .as("binding failures are 4xx client errors - their field detail may stay")
                .isEqualTo("always");
    }

    @Test
    void anUnhandledServerErrorIsAnsweredWithTheCuratedGenericMessage() {
        ApiExceptionHandler handler = new ApiExceptionHandler();
        RuntimeException internal = new IllegalStateException(
                "connection to host db-primary.internal:5432 refused (password=admin)");

        ResponseEntity<ApiResponse<Void>> response = handler.handleOtherException(
                internal, new ServletWebRequest(new MockHttpServletRequest()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(ErrorCode.INTERNAL_SERVER_ERROR.getCode());
        assertThat(response.getBody().message())
                .doesNotContain("db-primary.internal")
                .doesNotContain("5432")
                .doesNotContain("password")
                .isNotBlank();
    }
}
