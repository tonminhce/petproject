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
import org.springframework.dao.DataIntegrityViolationException;
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
import java.util.UUID;

@Service
@Slf4j
public class IdempotencyServiceImpl implements IdempotencyService {

    private final IdempotencyKeyRepository repository;
    private final ObjectMapper objectMapper;

    /**
     * Programmatic REQUIRES_NEW template for the in-flight insert. Hibernate sees an
     * {@code @IdClass} with a non-null PK as "managed/merged" — {@code saveAndFlush}
     * would bypass the INSERT and silently merge, so {@code DataIntegrityViolation}
     * never fires on a plain save; the explicit existence check is what short-circuits
     * replays, and the unique composite PK is the final arbiter on concurrent inserts.
     *
     * <p>The insert runs in its own committed transaction (independent of the caller's
     * saga TX) so other requests can observe the in-flight row during the saga, and a
     * lost same-key race rolls back cleanly without poisoning the caller's transaction
     * (review finding I1).</p>
     *
     * <p>Pool note (review finding 7): while the caller's transaction is active, this
     * template holds a SECOND pooled connection for the whole saga — including the
     * remote pricing/reserve calls. Hikari's pool must be sized with that in mind
     * (connections ≈ 2 × concurrent checkouts), or the in-flight insert should move
     * outside the saga TX in a future revision.</p>
     */
    private final TransactionTemplate requiresNewTemplate;

    /** TTL from spec §3.7 — wired to the {@code order.idempotency.ttl-hours} knob. */
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
    public Optional<OrderResponse> begin(String key, UUID userId, String requestHash) {
        if (key == null) return Optional.empty();

        Optional<IdempotencyKey> preExisting = repository.findByUserIdAndKey(userId, key);
        if (preExisting.isPresent()) {
            return resolve(preExisting.get(), requestHash, key);
        }

        IdempotencyKey ik = newInFlight(key, userId, requestHash);
        try {
            requiresNewTemplate.executeWithoutResult(status -> repository.saveAndFlush(ik));
            return Optional.empty();  // owner — proceed with saga
        } catch (DataIntegrityViolationException ex) {
            // Lost a same-key race: the winner's insert committed first. This re-read
            // runs in the caller's (saga) persistence context — safe because nothing
            // has been loaded into it yet, so the query sees the winner's committed
            // row. Resolve exactly like any other collision — never leak a raw
            // constraint violation as a 500 (review finding I1).
            IdempotencyKey winner = repository.findByUserIdAndKey(userId, key)
                .orElseThrow(() -> ex);
            return resolve(winner, requestHash, key);
        }
    }

    private Optional<OrderResponse> resolve(IdempotencyKey existing, String requestHash, String key) {
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

    private IdempotencyKey newInFlight(String key, UUID userId, String requestHash) {
        IdempotencyKey ik = new IdempotencyKey();
        ik.setUserId(userId);
        ik.setKey(key);
        ik.setRequestHash(requestHash);
        ik.setResponseStatus(0);  // in-flight
        ik.setResponseBody("");
        ik.setCreatedAt(Instant.now());
        ik.setExpiresAt(Instant.now().plus(ttlHours, ChronoUnit.HOURS));
        return ik;
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
    public void abort(String key, UUID userId, String requestHash) {
        if (key == null) return;
        try {
            repository.findByUserIdAndKey(userId, key)
                .filter(ik -> ik.getResponseStatus() == 0)
                // Defense-in-depth: only ever delete the row THIS execution created —
                // a hash mismatch means the row belongs to a different payload.
                .filter(ik -> Objects.equals(ik.getRequestHash(), requestHash))
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
