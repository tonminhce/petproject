package com.shop.orderservice.service;

import com.shop.orderservice.entity.FailedCompensation;
import com.shop.orderservice.repository.FailedCompensationRepository;
import com.shop.orderservice.support.AbstractOrderServiceIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ledger of the compensations that failed.
 *
 * <p>The checkout saga releases the stock reservations when it fails and
 * deletes the order row right after. When the release call itself fails, the
 * hold is orphaned: nothing references it any more, and the only way back was
 * inventory-service's TTL sweep - 900 seconds of unavailable stock with a log
 * line as the only trace. The failure must leave a durable record instead, and
 * the retry must release the hold.</p>
 */
class CompensationFailureLedgerIT extends AbstractOrderServiceIT {

    @Autowired
    private CompensationFailureService compensationFailureService;

    @Autowired
    private FailedCompensationRepository failedCompensationRepository;

    /** The ledger is shared by the whole suite, so every assertion is scoped. */
    private List<FailedCompensation> rowsFor(UUID reservationId) {
        return failedCompensationRepository.findAll().stream()
                .filter(row -> reservationId.equals(row.getReservationId()))
                .toList();
    }

    @Test
    void aFailedCompensationLeavesADurableRecord() {
        UUID orderId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();

        compensationFailureService.record(orderId, reservationId,
                FailedCompensation.KIND_RESERVATION_RELEASE, "inventory unavailable");

        List<FailedCompensation> rows = rowsFor(reservationId);
        assertThat(rows).hasSize(1);
        FailedCompensation row = rows.get(0);
        assertThat(row.getOrderId()).isEqualTo(orderId);
        assertThat(row.getReservationId()).isEqualTo(reservationId);
        assertThat(row.getStatus()).isEqualTo(FailedCompensation.STATUS_PENDING);
        assertThat(row.getLastError()).contains("inventory unavailable");
    }

    @Test
    void theSameReservationIsNotRecordedTwice() {
        UUID orderId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();

        compensationFailureService.record(orderId, reservationId,
                FailedCompensation.KIND_RESERVATION_RELEASE, "first");
        compensationFailureService.record(orderId, reservationId,
                FailedCompensation.KIND_RESERVATION_RELEASE, "second");

        List<FailedCompensation> rows = rowsFor(reservationId);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getLastError()).contains("first");
    }

    @Test
    void aPendingCompensationIsRetriedUntilItSucceeds() {
        UUID orderId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();

        compensationFailureService.record(orderId, reservationId,
                FailedCompensation.KIND_RESERVATION_RELEASE, "inventory unavailable");

        // the retry runs against the real inventory client: it fails while the
        // inventory service is down, and the row stays PENDING with the attempt
        // counted.
        compensationFailureService.retryPending();

        FailedCompensation pending = rowsFor(reservationId).get(0);
        assertThat(pending.getStatus()).isEqualTo(FailedCompensation.STATUS_PENDING);
        assertThat(pending.getAttempts()).isEqualTo(1);
        assertThat(pending.getNextAttemptAt()).isNotNull();

        // the failed attempt pushed the next attempt into the future (the
        // backoff), so the row is not due yet
        FailedCompensation scheduled = rowsFor(reservationId).get(0);
        assertThat(scheduled.getNextAttemptAt()).isAfter(Instant.now());

        // with the inventory service answering again, the due retry releases
        // the hold and the row is closed.
        scheduled.setNextAttemptAt(Instant.now().minusSeconds(1));
        failedCompensationRepository.save(scheduled);
        inventoryServer.stubFor(com.github.tomakehurst.wiremock.client.WireMock
                .post(com.github.tomakehurst.wiremock.client.WireMock.urlMatching(
                        "/api/v1/inventory/reservations/.*/release"))
                .willReturn(com.github.tomakehurst.wiremock.client.WireMock.okJson("{}")));
        compensationFailureService.retryPending();

        FailedCompensation resolved = rowsFor(reservationId).get(0);
        assertThat(resolved.getStatus()).isEqualTo(FailedCompensation.STATUS_RESOLVED);
        assertThat(resolved.getResolvedAt()).isNotNull();
    }
}
