package com.shop.common.core.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessExceptionTest {

    private enum CustomDomainError implements ErrorCatalog {
        CUSTOM_CONFLICT("CUST-9999", "custom.error.key", HttpStatus.CONFLICT);

        private final String code;
        private final String messageKey;
        private final HttpStatus httpStatus;

        CustomDomainError(String code, String messageKey, HttpStatus httpStatus) {
            this.code = code;
            this.messageKey = messageKey;
            this.httpStatus = httpStatus;
        }

        @Override
        public String getCode() {
            return code;
        }

        @Override
        public String getMessageKey() {
            return messageKey;
        }

        @Override
        public HttpStatus getHttpStatus() {
            return httpStatus;
        }
    }

    @Test
    void shouldAcceptCustomErrorCatalogImplementation() {
        BusinessException ex = BusinessException.of(CustomDomainError.CUSTOM_CONFLICT);

        assertThat(ex.getErrorCode()).isEqualTo("CUST-9999");
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ex.getMessage()).isEqualTo("custom.error.key");
    }

    @Test
    void shouldAcceptStandardErrorCode() {
        BusinessException ex = BusinessException.of(ErrorCode.NOT_FOUND);

        assertThat(ex.getErrorCode()).isEqualTo("ERR-0404");
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
