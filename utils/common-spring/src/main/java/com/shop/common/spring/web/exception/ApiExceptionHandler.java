package com.shop.common.spring.web.exception;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.common.core.i18n.Messages;
import com.shop.common.core.viewmodel.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.ArrayList;
import java.util.List;

/**
 * Global, cross-service exception translator. Every uncaught exception in any service
 * is converted to the canonical {@link ApiResponse} envelope so clients see one shape.
 *
 * <h3>Why one handler per exception type instead of a generic mapper?</h3>
 * Each branch encodes a deliberate translation: which {@link ErrorCode}, whether to surface
 * the original message (validation) or a canned one (auth), and whether to log at WARN or ERROR.
 * A table-driven mapper hides those decisions and tends to drift.
 */
@Order(Ordered.LOWEST_PRECEDENCE)
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private static final String ERROR_LOG_TEMPLATE = "ApiError uri={} status={} code={} message={} cause={}";

    // ------------------- Business -------------------
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException exception, WebRequest request) {
        return buildErrorResponse(
                exception.getStatus(),
                exception.getErrorCode(),
                exception.getMessage(),
                null,
                request,
                exception,
                false
        );
    }

    // ------------------- Validation -------------------
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception, WebRequest request) {

        List<String> errorMessages = new ArrayList<>();
        for (FieldError fieldError : exception.getBindingResult().getFieldErrors()) {
            errorMessages.add(fieldError.getField() + ": " + fieldError.getDefaultMessage());
        }
        return handleValidationError(errorMessages, request, exception);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiResponse<Void>> handleHandlerMethodValidation(
            HandlerMethodValidationException exception, WebRequest request) {

        List<String> errorMessages = new ArrayList<>();
        for (org.springframework.context.MessageSourceResolvable error : exception.getAllErrors()) {
            if (error instanceof FieldError fieldError) {
                errorMessages.add(fieldError.getField() + ": " + fieldError.getDefaultMessage());
            } else {
                errorMessages.add(error.getDefaultMessage());
            }
        }
        return handleValidationError(errorMessages, request, exception);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(
            ConstraintViolationException exception, WebRequest request) {

        List<String> errorMessages = new ArrayList<>();
        for (var violation : exception.getConstraintViolations()) {
            errorMessages.add(violation.getPropertyPath() + ": " + violation.getMessage());
        }
        return handleValidationError(errorMessages, request, exception);
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiResponse<Void>> handleMalformedRequest(Exception exception, WebRequest request) {
        return buildErrorResponse(
                HttpStatus.BAD_REQUEST,
                ErrorCode.BAD_REQUEST.getCode(),
                exception.getMessage(),
                null,
                request,
                exception,
                false
        );
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadableMessage(
            HttpMessageNotReadableException exception, WebRequest request) {
        // Deliberately do NOT surface exception.getMessage(): it can contain the raw
        // JSON payload and internal deserialization details.
        String safeMessage = Messages.get(ErrorCode.BAD_REQUEST.getMessageKey());
        return buildErrorResponse(
                HttpStatus.BAD_REQUEST,
                ErrorCode.BAD_REQUEST.getCode(),
                safeMessage,
                null,
                request,
                exception,
                false
        );
    }

    // ------------------- Routing -------------------
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException exception, WebRequest request) {
        return buildErrorResponse(
                HttpStatus.METHOD_NOT_ALLOWED,
                ErrorCode.METHOD_NOT_ALLOWED.getCode(),
                exception.getMessage(),
                null,
                request,
                exception,
                false
        );
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMediaTypeNotSupported(
            HttpMediaTypeNotSupportedException exception, WebRequest request) {
        return buildErrorResponse(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                ErrorCode.UNSUPPORTED_MEDIA_TYPE.getCode(),
                exception.getMessage(),
                null,
                request,
                exception,
                false
        );
    }

    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<ApiResponse<Void>> handleNotFound(Exception exception, WebRequest request) {
        String message = Messages.get(ErrorCode.NOT_FOUND.getMessageKey());
        return buildErrorResponse(
                HttpStatus.NOT_FOUND,
                ErrorCode.NOT_FOUND.getCode(),
                message,
                null,
                request,
                exception,
                false
        );
    }

    // ------------------- Upload / Payload -------------------
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleMaxUploadSize(
            MaxUploadSizeExceededException exception, WebRequest request) {
        String message = Messages.get(ErrorCode.PAYLOAD_TOO_LARGE.getMessageKey());
        return buildErrorResponse(
                HttpStatus.PAYLOAD_TOO_LARGE,
                ErrorCode.PAYLOAD_TOO_LARGE.getCode(),
                message,
                null,
                request,
                exception,
                false
        );
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ApiResponse<Void>> handleMultipart(
            MultipartException exception, WebRequest request) {
        return buildErrorResponse(
                HttpStatus.BAD_REQUEST,
                ErrorCode.BAD_REQUEST.getCode(),
                exception.getMessage(),
                null,
                request,
                exception,
                false
        );
    }

    // ------------------- Data integrity -------------------
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataIntegrityViolation(
            DataIntegrityViolationException exception, WebRequest request) {
        // H32 (Wave A fix-up) — the most-specific cause's message carries raw JDBC
        // text (constraint name, table, column, SQL state). ECHO NOTHING OF IT.
        // Keep 409 + domain ErrorCode so callers and operators still see the right
        // signal; the i18n string for `error.conflict` is the generic, leak-free
        // message. Full cause is logged at ERROR for operators.
        log.error("DataIntegrityViolationException swallowed into 409 ERR-0409 — see cause", exception);
        String safeMessage = Messages.get(ErrorCode.CONFLICT.getMessageKey());
        return buildErrorResponse(
                HttpStatus.CONFLICT,
                ErrorCode.CONFLICT.getCode(),
                safeMessage,
                null,
                request,
                exception,
                true
        );
    }

    // ------------------- Concurrent modification -------------------
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiResponse<Void>> handleOptimisticLocking(
            OptimisticLockingFailureException exception, WebRequest request) {
        // Another transaction updated the same row between our read and write
        // (e.g. two admins racing confirm/cancel on one order). That is a
        // client-visible conflict, not a server fault — canonical 409, never
        // the 500 fallback (order-service review finding 3).
        String message = Messages.get(ErrorCode.CONFLICT.getMessageKey());
        return buildErrorResponse(
                HttpStatus.CONFLICT,
                ErrorCode.CONFLICT.getCode(),
                message,
                null,
                request,
                exception,
                true
        );
    }

    // ------------------- Security -------------------
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(
            AccessDeniedException exception, WebRequest request) {
        String message = Messages.get(ErrorCode.FORBIDDEN.getMessageKey());
        return buildErrorResponse(
                HttpStatus.FORBIDDEN,
                ErrorCode.FORBIDDEN.getCode(),
                message,
                null,
                request,
                exception,
                false
        );
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResponse<Void>> handleAuthentication(
            AuthenticationException exception, WebRequest request) {
        String message = Messages.get(ErrorCode.UNAUTHORIZED.getMessageKey());
        return buildErrorResponse(
                HttpStatus.UNAUTHORIZED,
                ErrorCode.UNAUTHORIZED.getCode(),
                message,
                null,
                request,
                exception,
                false
        );
    }

    // ------------------- Fallback -------------------
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleOtherException(Exception exception, WebRequest request) {
        String message = Messages.get(ErrorCode.INTERNAL_SERVER_ERROR.getMessageKey());
        return buildErrorResponse(
                HttpStatus.INTERNAL_SERVER_ERROR,
                ErrorCode.INTERNAL_SERVER_ERROR.getCode(),
                message,
                null,
                request,
                exception,
                true
        );
    }

    // ------------------- Helpers -------------------
    private ResponseEntity<ApiResponse<Void>> handleValidationError(
            List<String> errorMessages, WebRequest request, Exception exception) {

        return buildErrorResponse(
                HttpStatus.BAD_REQUEST,
                ErrorCode.VALIDATION_FAILED.getCode(),
                Messages.get(ErrorCode.VALIDATION_FAILED.getMessageKey()),
                errorMessages,
                request,
                exception,
                false
        );
    }

    private ResponseEntity<ApiResponse<Void>> buildErrorResponse(
            HttpStatus status,
            String errorCode,
            String message,
            List<String> errorMessages,
            WebRequest request,
            Exception exception,
            boolean shouldLogAsError) {

        String requestUri = extractRequestUri(request);
        Throwable rootCause = findRootCause(exception);

        // Log at the severity the translation branch asked for
        if (shouldLogAsError) {
            log.error(ERROR_LOG_TEMPLATE, requestUri, status.value(), errorCode, message, rootCause.getMessage(), exception);
        } else {
            log.warn(ERROR_LOG_TEMPLATE, requestUri, status.value(), errorCode, message, rootCause.getMessage(), exception);
        }

        ApiResponse<Void> responseBody;
        if (errorMessages != null) {
            responseBody = ApiResponse.error(errorCode, message, errorMessages, requestUri);
        } else {
            responseBody = ApiResponse.error(errorCode, message, requestUri);
        }

        return ResponseEntity.status(status).body(responseBody);
    }

    private String extractRequestUri(WebRequest request) {
        if (request instanceof ServletWebRequest servletWebRequest) {
            return servletWebRequest.getRequest().getRequestURI();
        }
        return null;
    }

    private Throwable findRootCause(Throwable throwable) {
        Throwable rootCause = throwable;
        while (rootCause.getCause() != null) {
            rootCause = rootCause.getCause();
        }
        return rootCause;
    }
}