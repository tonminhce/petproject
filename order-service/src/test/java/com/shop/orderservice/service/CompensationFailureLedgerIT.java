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

class CompensationFailureLedgerIT extends AbstractOrderServiceIT {

    @Autowired
    private CompensationFailureService compensationFailureService;

    @Autowired
    private FailedCompensationRepository failedCompensationRepository;

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

        compensationFailureService.retryPending();

        FailedCompensation pending = rowsFor(reservationId).get(0);
        assertThat(pending.getStatus()).isEqualTo(FailedCompensation.STATUS_PENDING);
        assertThat(pending.getAttempts()).isEqualTo(1);
        assertThat(pending.getNextAttemptAt()).isNotNull();

        FailedCompensation scheduled = rowsFor(reservationId).get(0);
        assertThat(scheduled.getNextAttemptAt()).isAfter(Instant.now());

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
