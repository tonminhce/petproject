package com.shop.orderservice.service.impls;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.common.core.exception.BusinessException;
import com.shop.orderservice.dto.response.OrderResponse;
import com.shop.orderservice.entity.IdempotencyKey;
import com.shop.orderservice.constant.OrderStatus;
import com.shop.orderservice.repository.IdempotencyKeyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

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
    @Mock PlatformTransactionManager transactionManager;

    // Manual construction: the TTL is constructor-injected (review nit 8), and
    // @InjectMocks would leave the primitive parameter at 0.
    IdempotencyServiceImpl service;

    private final UUID userId = UUID.randomUUID();
    private final String key = "test-key";
    private final String requestHash = "abc123";
    private final OrderResponse response = new OrderResponse(
        UUID.randomUUID(), userId, OrderStatus.PENDING, List.of(),
        BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.TEN, null,
        Instant.now(), null, null, null, null);

    @BeforeEach
    void setUp() {
        service = new IdempotencyServiceImpl(repository, objectMapper, transactionManager, 24L);
        // The impl builds a REQUIRES_NEW TransactionTemplate over this manager;
        // this stub makes the template execute its callback inline and treat the
        // "transaction" as new, so the production code path runs unmodified.
        lenient().when(transactionManager.getTransaction(any()))
            .thenReturn(new SimpleTransactionStatus());
    }

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
        IdempotencyKey existing = completedRow(requestHash);
        when(repository.findByUserIdAndKey(userId, key)).thenReturn(Optional.of(existing));
        when(objectMapper.readValue(existing.getResponseBody(), OrderResponse.class)).thenReturn(response);

        Optional<OrderResponse> result = service.begin(key, userId, requestHash);

        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo(response);  // REPLAY — return cached
    }

    @Test
    void begin_collisionWithDifferentPayloadThrows409() {
        IdempotencyKey existing = completedRow("abc");  // different hash
        when(repository.findByUserIdAndKey(userId, key)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.begin(key, userId, requestHash))
            .isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo("ORD-4010"));  // DUPLICATE_REQUEST
    }

    @Test
    void begin_collisionInFlightThrows() {
        IdempotencyKey existing = inFlightRow(requestHash);
        when(repository.findByUserIdAndKey(userId, key)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.begin(key, userId, requestHash))
            .isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo("ORD-4010"));  // DUPLICATE_REQUEST
    }

    // ---------- begin: lost-race handling (review I1) ----------

    @Test
    void begin_lostRace_replaysCompletedWinner() throws Exception {
        // 1st read: empty (winner's insert not visible yet) → our insert hits the
        // unique composite PK → re-read finds the completed winner → REPLAY.
        when(repository.findByUserIdAndKey(userId, key))
            .thenReturn(Optional.empty())
            .thenReturn(Optional.of(completedRow(requestHash)));
        when(repository.saveAndFlush(any(IdempotencyKey.class)))
            .thenThrow(new DataIntegrityViolationException("uk_idempotency"));
        when(objectMapper.readValue(anyString(), eq(OrderResponse.class))).thenReturn(response);

        Optional<OrderResponse> result = service.begin(key, userId, requestHash);

        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo(response);
    }

    @Test
    void begin_lostRace_inFlightWinnerThrows409() {
        when(repository.findByUserIdAndKey(userId, key))
            .thenReturn(Optional.empty())
            .thenReturn(Optional.of(inFlightRow(requestHash)));
        when(repository.saveAndFlush(any(IdempotencyKey.class)))
            .thenThrow(new DataIntegrityViolationException("uk_idempotency"));

        assertThatThrownBy(() -> service.begin(key, userId, requestHash))
            .isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo("ORD-4010"));
    }

    @Test
    void begin_lostRace_rowVanished_rethrowsOriginal() {
        when(repository.findByUserIdAndKey(userId, key))
            .thenReturn(Optional.empty())
            .thenReturn(Optional.empty());
        DataIntegrityViolationException dive = new DataIntegrityViolationException("uk_idempotency");
        when(repository.saveAndFlush(any(IdempotencyKey.class))).thenThrow(dive);

        assertThatThrownBy(() -> service.begin(key, userId, requestHash))
            .isSameAs(dive);
    }

    // ---------- complete ----------

    @Test
    void complete_updatesExistingRow() throws Exception {
        IdempotencyKey existing = inFlightRow(requestHash);
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
    void abort_deletesInFlightRowWithMatchingHash() {
        IdempotencyKey inFlight = inFlightRow(requestHash);
        when(repository.findByUserIdAndKey(userId, key)).thenReturn(Optional.of(inFlight));

        service.abort(key, userId, requestHash);

        verify(repository).delete(inFlight);
    }

    @Test
    void abort_neverDeletesRowWithDifferentHash() {
        // Defense-in-depth for review I1: a row belonging to a different payload
        // (or a different racing execution) must never be deleted by ours.
        IdempotencyKey otherPayload = inFlightRow("other-hash");
        when(repository.findByUserIdAndKey(userId, key)).thenReturn(Optional.of(otherPayload));

        service.abort(key, userId, requestHash);

        verify(repository, never()).delete(any(IdempotencyKey.class));
    }

    @Test
    void abort_keepsCompletedRow() {
        IdempotencyKey completed = completedRow(requestHash);
        when(repository.findByUserIdAndKey(userId, key)).thenReturn(Optional.of(completed));

        service.abort(key, userId, requestHash);

        verify(repository, never()).delete(any(IdempotencyKey.class));
    }

    // ---------- helpers ----------

    private IdempotencyKey inFlightRow(String hash) {
        return IdempotencyKey.builder()
            .userId(userId).key(key).requestHash(hash)
            .responseStatus(0)
            .responseBody("")
            .createdAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600))
            .build();
    }

    private IdempotencyKey completedRow(String hash) {
        return IdempotencyKey.builder()
            .userId(userId).key(key).requestHash(hash)
            .responseStatus(201)
            .responseBody("{\"cached\":\"json\"}")
            .createdAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600))
            .build();
    }
}
