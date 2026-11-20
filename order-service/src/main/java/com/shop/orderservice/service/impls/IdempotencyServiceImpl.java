package com.shop.orderservice.service.impls;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.orderservice.dto.response.OrderResponse;
import com.shop.orderservice.entity.IdempotencyKey;
import com.shop.orderservice.repository.IdempotencyKeyRepository;
import com.shop.orderservice.service.IdempotencyService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;

@Service
@Slf4j
public class IdempotencyServiceImpl implements IdempotencyService {

    private final IdempotencyKeyRepository repository;
    private final ObjectMapper objectMapper;

    private final TransactionTemplate requiresNewTemplate;

    private final long ttlHours;

    public IdempotencyServiceImpl(IdempotencyKeyRepository repository,
                                  ObjectMapper objectMapper,
                                  PlatformTransactionManager transactionManager,
                                  @Value("${order.idempotency.ttl-hours:24}") long ttlHours) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.ttlHours = ttlHours;
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.requiresNewTemplate = template;
    }

    @Override
    public Optional<OrderResponse> begin(String key, String actor, String requestHash) {
        if (key == null) return Optional.empty();

        Optional<OrderResponse> result = requiresNewTemplate.execute(status -> {
            int claimed = repository.insertIfAbsent(actor, key, requestHash, expiresAt());
            if (claimed == 1) {
                return Optional.<OrderResponse>empty();
            }
            IdempotencyKey winner = repository.findByActorAndKey(actor, key)
                .orElseThrow(() -> BusinessException.of(ErrorCode.ORDER_DUPLICATE_REQUEST, key));
            return resolve(winner, requestHash, key);
        });
        return result == null ? Optional.empty() : result;
    }

    private Instant expiresAt() {
        return Instant.now().plus(ttlHours, ChronoUnit.HOURS);
    }

    private Optional<OrderResponse> resolve(IdempotencyKey existing, String requestHash, String key) {
        if (existing.getResponseStatus() != 0) {
            if (!existing.getRequestHash().equals(requestHash)) {
                throw BusinessException.of(ErrorCode.ORDER_DUPLICATE_REQUEST, key);
            }
            return Optional.of(deserialize(existing.getResponseBody()));
        }
        throw BusinessException.of(ErrorCode.ORDER_DUPLICATE_REQUEST, key);
    }

    @Override
    @Transactional
    public void complete(String key, String actor, OrderResponse response, int status) {
        if (key == null) return;
        IdempotencyKey ik = repository.findByActorAndKey(actor, key)
            .orElseThrow(() -> new IllegalStateException("Idempotency row not found for complete: " + key));
        ik.setResponseStatus(status);
        ik.setResponseBody(serialize(response));
        repository.save(ik);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void abort(String key, String actor, String requestHash) {
        if (key == null) return;
        try {
            repository.findByActorAndKey(actor, key)
                .filter(ik -> ik.getResponseStatus() == 0)
                .filter(ik -> Objects.equals(ik.getRequestHash(), requestHash))
                .ifPresent(repository::delete);
        } catch (Exception ex) {
            log.warn("Failed to abort in-flight idempotency key {}/{}: {}", actor, key, ex.getMessage());
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
