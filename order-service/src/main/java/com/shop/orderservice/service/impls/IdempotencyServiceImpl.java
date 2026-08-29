package com.shop.orderservice.service.impls;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.orderservice.dto.response.OrderResponse;
import com.shop.orderservice.entity.IdempotencyKey;
import com.shop.orderservice.repository.IdempotencyKeyRepository;
import com.shop.orderservice.service.IdempotencyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class IdempotencyServiceImpl implements IdempotencyService {

    private final IdempotencyKeyRepository repository;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<OrderResponse> begin(String key, UUID userId, String requestHash) {
        if (key == null) return Optional.empty();

        // ponytail: Hibernate sees @IdClass with non-null PK as "managed/merged" — saveAndFlush
        // bypasses the INSERT and silently merges, so DataIntegrityViolation never fires.
        // Check existence explicitly so the replay path actually short-circuits.
        Optional<IdempotencyKey> preExisting = repository.findByUserIdAndKey(userId, key);
        if (preExisting.isPresent()) {
            IdempotencyKey existing = preExisting.get();
            if (existing.getResponseStatus() != 0) {
                // Complete — but spec §5.6 requires hash match before replay.
                // Same key + different payload is a 409, not a silent replay.
                if (!existing.getRequestHash().equals(requestHash)) {
                    throw BusinessException.of(ErrorCode.ORDER_DUPLICATE_REQUEST, key);
                }
                return Optional.of(deserialize(existing.getResponseBody()));
            }
            // In-flight — reject
            throw BusinessException.of(ErrorCode.ORDER_DUPLICATE_REQUEST, key);
        }

        IdempotencyKey ik = new IdempotencyKey();
        ik.setUserId(userId);
        ik.setKey(key);
        ik.setRequestHash(requestHash);
        ik.setResponseStatus(0);  // in-flight
        ik.setResponseBody("");
        ik.setCreatedAt(Instant.now());
        ik.setExpiresAt(Instant.now().plus(24, ChronoUnit.HOURS));  // TTL from spec §3.7

        repository.saveAndFlush(ik);
        return Optional.empty();  // owner — proceed with saga
    }

    @Override
    @Transactional
    public void complete(String key, UUID userId, OrderResponse response, int status) {
        if (key == null) return;
        IdempotencyKey ik = repository.findByUserIdAndKey(userId, key)
            .orElseThrow(() -> new IllegalStateException("Idempotency row not found for complete: " + key));
        ik.setResponseStatus(status);
        ik.setResponseBody(serialize(response));
        repository.save(ik);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void abort(String key, UUID userId) {
        if (key == null) return;
        try {
            repository.findByUserIdAndKey(userId, key)
                .filter(ik -> ik.getResponseStatus() == 0)
                .ifPresent(repository::delete);
        } catch (Exception ex) {
            log.warn("Failed to abort in-flight idempotency key {}/{}: {}", userId, key, ex.getMessage());
            // Row will be TTL-purged (rev 2 fallback)
        }
    }

    private String serialize(OrderResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize OrderResponse for idempotency cache", ex);
        }
    }

    private OrderResponse deserialize(String body) {
        try {
            return objectMapper.readValue(body, OrderResponse.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to deserialize cached OrderResponse", ex);
        }
    }
}
