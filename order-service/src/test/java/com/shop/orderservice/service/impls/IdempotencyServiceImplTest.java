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

    IdempotencyServiceImpl service;

    private final String actor = UUID.randomUUID().toString();
    private final String key = "test-key";
    private final String requestHash = "abc123";
    private final OrderResponse response = new OrderResponse(
        UUID.randomUUID(), UUID.randomUUID(), OrderStatus.PENDING, List.of(),
        BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.TEN, "VND", null,
        Instant.now(), null, null, null, null);

    @BeforeEach
    void setUp() {
        service = new IdempotencyServiceImpl(repository, objectMapper, transactionManager, 24L);
        lenient().when(transactionManager.getTransaction(any()))
            .thenReturn(new SimpleTransactionStatus());
    }


    @Test
    void begin_nullKeyReturnsEmpty() {
        Optional<OrderResponse> result = service.begin(null, actor, requestHash);

        assertThat(result).isEmpty();
        verifyNoInteractions(repository);
    }

    @Test
    void begin_claimsTheKeyAtomically() {
        when(repository.insertIfAbsent(eq(actor), eq(key), eq(requestHash), any()))
            .thenReturn(1);

        Optional<OrderResponse> result = service.begin(key, actor, requestHash);

        assertThat(result).isEmpty();
        verify(repository).insertIfAbsent(eq(actor), eq(key), eq(requestHash), any());
    }

    @Test
    void begin_neverClaimsTheKeyThroughMergeSemantics() {
        when(repository.insertIfAbsent(eq(actor), eq(key), eq(requestHash), any()))
            .thenReturn(1);

        service.begin(key, actor, requestHash);

        verify(repository, never()).save(any(IdempotencyKey.class));
        verify(repository, never()).saveAndFlush(any(IdempotencyKey.class));
    }

    @Test
    void begin_collisionWithCompleteReturnsCached() throws Exception {
        IdempotencyKey existing = completedRow(requestHash);
        when(repository.insertIfAbsent(eq(actor), eq(key), eq(requestHash), any())).thenReturn(0);
        when(repository.findByActorAndKey(actor, key)).thenReturn(Optional.of(existing));
        when(objectMapper.readValue(existing.getResponseBody(), OrderResponse.class)).thenReturn(response);

        Optional<OrderResponse> result = service.begin(key, actor, requestHash);

        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo(response);
    }

    @Test
    void begin_collisionWithDifferentPayloadThrows409() {
        IdempotencyKey existing = completedRow("abc");
        when(repository.insertIfAbsent(eq(actor), eq(key), eq(requestHash), any())).thenReturn(0);
        when(repository.findByActorAndKey(actor, key)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.begin(key, actor, requestHash))
            .isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo("ORD-4010"));
    }

    @Test
    void begin_collisionInFlightThrows() {
        IdempotencyKey existing = inFlightRow(requestHash);
        when(repository.insertIfAbsent(eq(actor), eq(key), eq(requestHash), any())).thenReturn(0);
        when(repository.findByActorAndKey(actor, key)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.begin(key, actor, requestHash))
            .isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo("ORD-4010"));
    }


    @Test
    void begin_lostRace_replaysCompletedWinner() throws Exception {
        when(repository.insertIfAbsent(eq(actor), eq(key), eq(requestHash), any())).thenReturn(0);
        when(repository.findByActorAndKey(actor, key))
            .thenReturn(Optional.of(completedRow(requestHash)));
        when(objectMapper.readValue(anyString(), eq(OrderResponse.class))).thenReturn(response);

        Optional<OrderResponse> result = service.begin(key, actor, requestHash);

        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo(response);
    }

    @Test
    void begin_lostRace_inFlightWinnerThrows409() {
        when(repository.insertIfAbsent(eq(actor), eq(key), eq(requestHash), any())).thenReturn(0);
        when(repository.findByActorAndKey(actor, key))
            .thenReturn(Optional.of(inFlightRow(requestHash)));

        assertThatThrownBy(() -> service.begin(key, actor, requestHash))
            .isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo("ORD-4010"));
    }

    @Test
    void begin_lostRace_rowVanishedReportsDuplicate() {
        when(repository.insertIfAbsent(eq(actor), eq(key), eq(requestHash), any())).thenReturn(0);
        when(repository.findByActorAndKey(actor, key)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.begin(key, actor, requestHash))
            .isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo("ORD-4010"));
    }


    @Test
    void complete_updatesExistingRow() throws Exception {
        IdempotencyKey existing = inFlightRow(requestHash);
        when(repository.findByActorAndKey(actor, key)).thenReturn(Optional.of(existing));
        when(objectMapper.writeValueAsString(response)).thenReturn("{\"json\":\"serialized\"}");
        when(repository.save(any(IdempotencyKey.class))).thenAnswer(inv -> inv.getArgument(0));

        service.complete(key, actor, response, 201);

        ArgumentCaptor<IdempotencyKey> captor = ArgumentCaptor.forClass(IdempotencyKey.class);
        verify(repository).save(captor.capture());
        IdempotencyKey saved = captor.getValue();
        assertThat(saved.getResponseStatus()).isEqualTo(201);
        assertThat(saved.getResponseBody()).isEqualTo("{\"json\":\"serialized\"}");
    }

    @Test
    void complete_nullKeyNoOp() {
        service.complete(null, actor, response, 201);

        verifyNoInteractions(repository);
    }


    @Test
    void abort_deletesInFlightRowWithMatchingHash() {
        IdempotencyKey inFlight = inFlightRow(requestHash);
        when(repository.findByActorAndKey(actor, key)).thenReturn(Optional.of(inFlight));

        service.abort(key, actor, requestHash);

        verify(repository).delete(inFlight);
    }

    @Test
    void abort_neverDeletesRowWithDifferentHash() {
        IdempotencyKey otherPayload = inFlightRow("other-hash");
        when(repository.findByActorAndKey(actor, key)).thenReturn(Optional.of(otherPayload));

        service.abort(key, actor, requestHash);

        verify(repository, never()).delete(any(IdempotencyKey.class));
    }

    @Test
    void abort_keepsCompletedRow() {
        IdempotencyKey completed = completedRow(requestHash);
        when(repository.findByActorAndKey(actor, key)).thenReturn(Optional.of(completed));

        service.abort(key, actor, requestHash);

        verify(repository, never()).delete(any(IdempotencyKey.class));
    }


    private IdempotencyKey inFlightRow(String hash) {
        return IdempotencyKey.builder()
            .actor(actor).key(key).requestHash(hash)
            .responseStatus(0)
            .responseBody("")
            .createdAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600))
            .build();
    }

    private IdempotencyKey completedRow(String hash) {
        return IdempotencyKey.builder()
            .actor(actor).key(key).requestHash(hash)
            .responseStatus(201)
            .responseBody("{\"cached\":\"json\"}")
            .createdAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600))
            .build();
    }
}
