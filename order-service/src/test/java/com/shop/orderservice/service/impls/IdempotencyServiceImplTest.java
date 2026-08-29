package com.shop.orderservice.service.impls;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.common.core.exception.BusinessException;
import com.shop.orderservice.dto.response.OrderResponse;
import com.shop.orderservice.entity.IdempotencyKey;
import com.shop.orderservice.entity.OrderStatus;
import com.shop.orderservice.repository.IdempotencyKeyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IdempotencyServiceImplTest {

    @Mock IdempotencyKeyRepository repository;
    @Mock ObjectMapper objectMapper;

    @InjectMocks IdempotencyServiceImpl service;

    private final UUID userId = UUID.randomUUID();
    private final String key = "test-key";
    private final String requestHash = "abc123";
    private final OrderResponse response = new OrderResponse(
        UUID.randomUUID(), userId, OrderStatus.PENDING, List.of(),
        BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.TEN, null,
        Instant.now(), null, null, null, null);

    // ---------- begin ----------

    @Test
    void begin_nullKeyReturnsEmpty() {
        Optional<OrderResponse> result = service.begin(null, userId, requestHash);

        assertThat(result).isEmpty();
        verifyNoInteractions(repository);
    }

    @Test
    void begin_successSavesRowReturnsEmpty() {
        when(repository.saveAndFlush(any(IdempotencyKey.class))).thenAnswer(inv -> inv.getArgument(0));

        Optional<OrderResponse> result = service.begin(key, userId, requestHash);

        assertThat(result).isEmpty();  // owner — proceed with saga
        ArgumentCaptor<IdempotencyKey> captor = ArgumentCaptor.forClass(IdempotencyKey.class);
        verify(repository).saveAndFlush(captor.capture());
        IdempotencyKey saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(userId);
        assertThat(saved.getKey()).isEqualTo(key);
        assertThat(saved.getRequestHash()).isEqualTo(requestHash);
        assertThat(saved.getResponseStatus()).isEqualTo(0);  // in-flight
        assertThat(saved.getExpiresAt()).isAfter(saved.getCreatedAt());
    }

    @Test
    void begin_collisionWithCompleteReturnsCached() throws Exception {
        IdempotencyKey existing = IdempotencyKey.builder()
            .userId(userId).key(key).requestHash(requestHash)
            .responseStatus(201)
            .responseBody("{\"cached\":\"json\"}")
            .createdAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600))
            .build();
        when(repository.saveAndFlush(any(IdempotencyKey.class)))
            .thenThrow(new DataIntegrityViolationException("PK collision"));
        when(repository.findByUserIdAndKey(userId, key)).thenReturn(Optional.of(existing));
        when(objectMapper.readValue(existing.getResponseBody(), OrderResponse.class)).thenReturn(response);

        Optional<OrderResponse> result = service.begin(key, userId, requestHash);

        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo(response);  // REPLAY — return cached
    }

    @Test
    void begin_collisionInFlightThrows() {
        IdempotencyKey existing = IdempotencyKey.builder()
            .userId(userId).key(key).requestHash(requestHash)
            .responseStatus(0)  // in-flight
            .responseBody("")
            .createdAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600))
            .build();
        when(repository.saveAndFlush(any(IdempotencyKey.class)))
            .thenThrow(new DataIntegrityViolationException("PK collision"));
        when(repository.findByUserIdAndKey(userId, key)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.begin(key, userId, requestHash))
            .isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo("ORD-4010"));  // DUPLICATE_REQUEST
    }

    // ---------- complete ----------

    @Test
    void complete_updatesExistingRow() throws Exception {
        IdempotencyKey existing = IdempotencyKey.builder()
            .userId(userId).key(key).requestHash(requestHash)
            .responseStatus(0)
            .responseBody("")
            .createdAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600))
            .build();
        when(repository.findByUserIdAndKey(userId, key)).thenReturn(Optional.of(existing));
        when(objectMapper.writeValueAsString(response)).thenReturn("{\"json\":\"serialized\"}");
        when(repository.save(any(IdempotencyKey.class))).thenAnswer(inv -> inv.getArgument(0));

        service.complete(key, userId, response, 201);

        ArgumentCaptor<IdempotencyKey> captor = ArgumentCaptor.forClass(IdempotencyKey.class);
        verify(repository).save(captor.capture());
        IdempotencyKey saved = captor.getValue();
        assertThat(saved.getResponseStatus()).isEqualTo(201);
        assertThat(saved.getResponseBody()).isEqualTo("{\"json\":\"serialized\"}");
    }

    @Test
    void complete_nullKeyNoOp() {
        service.complete(null, userId, response, 201);

        verifyNoInteractions(repository);
    }

    // ---------- abort ----------

    @Test
    void abort_deletesInFlightRow() {
        IdempotencyKey inFlight = IdempotencyKey.builder()
            .userId(userId).key(key).requestHash(requestHash)
            .responseStatus(0)
            .responseBody("")
            .createdAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600))
            .build();
        when(repository.findByUserIdAndKey(userId, key)).thenReturn(Optional.of(inFlight));

        service.abort(key, userId);

        verify(repository).delete(inFlight);
    }

    @Test
    void abort_keepsCompletedRow() {
        IdempotencyKey completed = IdempotencyKey.builder()
            .userId(userId).key(key).requestHash(requestHash)
            .responseStatus(201)  // already complete — keep
            .responseBody("cached")
            .createdAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600))
            .build();
        when(repository.findByUserIdAndKey(userId, key)).thenReturn(Optional.of(completed));

        service.abort(key, userId);

        verify(repository, never()).delete(any(IdempotencyKey.class));
    }
}
