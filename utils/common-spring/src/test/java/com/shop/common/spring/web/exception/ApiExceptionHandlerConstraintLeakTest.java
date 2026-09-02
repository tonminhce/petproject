package com.shop.common.spring.web.exception;

import com.shop.common.core.viewmodel.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H32 — {@link ApiExceptionHandler} must NOT echo raw DB constraint names
 * (e.g. {@code users_username_key}, {@code ck_payment_status_d}) back to the
 * client. Those messages reveal schema layout (column names, check names,
 * unique indexes) — the kind of leak an attacker uses to map the data model
 * before crafting follow-up requests.
 *
 * <p>The handler continues to log the root cause at ERROR (so operators can
 * still debug), but the client-facing {@link ApiResponse#getMessage()} carries
 * only the canonical i18n string for the error code, with no DB-side detail.</p>
 */
class ApiExceptionHandlerConstraintLeakTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void dataIntegrityViolationReturnsGenericInternalErrorMessageNoDbLeak() {
        // Simulated Hibernate path: SqlIntegrityConstraintViolationException
        // -> ConstraintViolationException -> DataIntegrityViolationException
        // The cause's message contains a real schema detail we must NOT echo.
        SQLException sql = new SQLException(
                "ERROR: duplicate key value violates unique constraint \"users_username_key\""
                        + "\n  Detail: Key (username)=(admin) already exists.",
                "23505");
        DataIntegrityViolationException ex = new DataIntegrityViolationException(
                "could not execute statement", sql);

        ResponseEntity<ApiResponse<Void>> response = handler.handleDataIntegrityViolation(
                ex, servletWebRequest("/api/v1/users"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("ERR-0409");
        String body = response.getBody().message();
        assertThat(body)
                .as("client-facing message must NOT echo the DB constraint name")
                .doesNotContain("users_username_key")
                .doesNotContain("username")
                .doesNotContain("admin")
                .doesNotContain("23505")
                .doesNotContain("duplicate key")
                .doesNotContain("constraint")
                .doesNotContainIgnoringCase("violat");
        assertThat(body).isNotBlank();
    }

    @Test
    void dataIntegrityViolationDoesNotSurfaceCheckConstraintTypeDetail() {
        // Postgres/MySQL check-constraint failures leak the type, the table, the
        // column, and the failed boolean expression — strip all of that.
        SQLException sql = new SQLException(
                "ERROR: new row for relation \"payment_events\" violates check constraint"
                        + " \"ck_payment_events_status\"\n"
                        + "  Detail: Failing row contains (FAILED_RETRYABLE, ...).",
                "23514");
        DataIntegrityViolationException ex = new DataIntegrityViolationException(
                "could not execute statement", sql);

        ResponseEntity<ApiResponse<Void>> response = handler.handleDataIntegrityViolation(
                ex, servletWebRequest("/api/v1/payments/webhook"));

        String body = response.getBody().message();
        assertThat(body)
                .doesNotContain("ck_payment_events_status")
                .doesNotContain("payment_events")
                .doesNotContain("FAILED_RETRYABLE")
                .doesNotContain("check constraint");
    }

    private static ServletWebRequest servletWebRequest(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI(uri);
        return new ServletWebRequest(request);
    }
}