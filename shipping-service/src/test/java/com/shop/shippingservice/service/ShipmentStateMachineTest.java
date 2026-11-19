package com.shop.shippingservice.service;

import com.shop.common.core.exception.BusinessException;
import com.shop.shippingservice.constant.ShipmentStatus;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static com.shop.shippingservice.constant.ShipmentStatus.CANCELLED;
import static com.shop.shippingservice.constant.ShipmentStatus.CREATED;
import static com.shop.shippingservice.constant.ShipmentStatus.DELIVERED;
import static com.shop.shippingservice.constant.ShipmentStatus.DELIVERY_FAILED;
import static com.shop.shippingservice.constant.ShipmentStatus.IN_TRANSIT;
import static com.shop.shippingservice.constant.ShipmentStatus.OUT_FOR_DELIVERY;
import static com.shop.shippingservice.constant.ShipmentStatus.PICKED_UP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class ShipmentStateMachineTest {

    private static final Set<Map.Entry<ShipmentStatus, ShipmentStatus>> LEGAL = Set.of(
            Map.entry(CREATED, PICKED_UP),
            Map.entry(PICKED_UP, IN_TRANSIT),
            Map.entry(IN_TRANSIT, OUT_FOR_DELIVERY),
            Map.entry(OUT_FOR_DELIVERY, DELIVERED),
            Map.entry(PICKED_UP, DELIVERY_FAILED),
            Map.entry(IN_TRANSIT, DELIVERY_FAILED),
            Map.entry(OUT_FOR_DELIVERY, DELIVERY_FAILED),
            Map.entry(CREATED, CANCELLED),
            Map.entry(PICKED_UP, CANCELLED),
            Map.entry(IN_TRANSIT, CANCELLED),
            Map.entry(OUT_FOR_DELIVERY, CANCELLED));

    @Test
    void happyChainWalkReachesDelivered() {
        assertThat(ShipmentStateMachine.transition(CREATED, PICKED_UP)).isEqualTo(PICKED_UP);
        assertThat(ShipmentStateMachine.transition(PICKED_UP, IN_TRANSIT)).isEqualTo(IN_TRANSIT);
        assertThat(ShipmentStateMachine.transition(IN_TRANSIT, OUT_FOR_DELIVERY)).isEqualTo(OUT_FOR_DELIVERY);
        assertThat(ShipmentStateMachine.transition(OUT_FOR_DELIVERY, DELIVERED)).isEqualTo(DELIVERED);
    }

    @Test
    void exhaustivePairMatrixMatchesExactlyTheLegalSet() {
        assertThat(LEGAL).hasSize(11);

        for (ShipmentStatus from : ShipmentStatus.values()) {
            for (ShipmentStatus to : ShipmentStatus.values()) {
                if (LEGAL.contains(Map.entry(from, to))) {
                    assertThat(ShipmentStateMachine.transition(from, to)).isEqualTo(to);
                } else {
                    BusinessException ex = catchThrowableOfType(
                            () -> ShipmentStateMachine.transition(from, to), BusinessException.class);
                    assertThat(ex).isNotNull();
                    assertThat(ex.getErrorCode()).isEqualTo("SHP-10003");
                }
            }
        }
    }

    @Test
    void deliveryFailedCannotGoBackToInTransitThroughTheCarrierTransition() {
        BusinessException ex = catchThrowableOfType(
                () -> ShipmentStateMachine.transition(DELIVERY_FAILED, IN_TRANSIT), BusinessException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getErrorCode()).isEqualTo("SHP-10003");
    }

    @Test
    void operatorRetryIsTheOnlyPathOutOfDeliveryFailed() {
        assertThat(ShipmentStateMachine.operatorRetry(DELIVERY_FAILED)).isEqualTo(IN_TRANSIT);
    }

    @Test
    void operatorRetryFromAnyOtherStatusIsRejected() {
        for (ShipmentStatus from : ShipmentStatus.values()) {
            if (from == DELIVERY_FAILED) {
                continue;
            }
            BusinessException ex = catchThrowableOfType(
                    () -> ShipmentStateMachine.operatorRetry(from), BusinessException.class);
            assertThat(ex).as("operator retry from %s", from).isNotNull();
            assertThat(ex.getErrorCode()).isEqualTo("SHP-10003");
        }
    }

    @Test
    void autoDeliverIsDeclaredForEveryInFlightStatus() {
        assertThat(ShipmentStateMachine.autoDeliver(PICKED_UP)).isEqualTo(DELIVERED);
        assertThat(ShipmentStateMachine.autoDeliver(IN_TRANSIT)).isEqualTo(DELIVERED);
        assertThat(ShipmentStateMachine.autoDeliver(OUT_FOR_DELIVERY)).isEqualTo(DELIVERED);
    }

    @Test
    void autoDeliverFromATerminalStatusIsRejected() {
        for (ShipmentStatus from : ShipmentStatus.values()) {
            if (ShipmentStatus.inFlight(from)) {
                continue;
            }
            BusinessException ex = catchThrowableOfType(
                    () -> ShipmentStateMachine.autoDeliver(from), BusinessException.class);
            assertThat(ex).as("auto delivery from %s", from).isNotNull();
            assertThat(ex.getErrorCode()).isEqualTo("SHP-10003");
        }
    }

    @Test
    void carrierStatusAcceptsAnUnchangedStatusAsIdempotent() {
        assertThat(ShipmentStateMachine.carrierStatus(IN_TRANSIT, IN_TRANSIT)).isEqualTo(IN_TRANSIT);
        assertThat(ShipmentStateMachine.carrierStatus(DELIVERY_FAILED, DELIVERY_FAILED)).isEqualTo(DELIVERY_FAILED);
    }

    @Test
    void carrierStatusCannotResurrectADeliveryFailedShipment() {
        BusinessException ex = catchThrowableOfType(
                () -> ShipmentStateMachine.carrierStatus(DELIVERY_FAILED, IN_TRANSIT), BusinessException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getErrorCode()).isEqualTo("SHP-10003");
    }

    @Test
    void carrierStatusWithNullEndpointIsRejected() {
        BusinessException nullFrom = catchThrowableOfType(
                () -> ShipmentStateMachine.carrierStatus(null, IN_TRANSIT), BusinessException.class);
        assertThat(nullFrom).isNotNull();
        assertThat(nullFrom.getErrorCode()).isEqualTo("SHP-10003");

        BusinessException nullTo = catchThrowableOfType(
                () -> ShipmentStateMachine.carrierStatus(CREATED, null), BusinessException.class);
        assertThat(nullTo).isNotNull();
        assertThat(nullTo.getErrorCode()).isEqualTo("SHP-10003");
    }

    @Test
    void nullFromOrToIsRejected() {
        BusinessException nullFrom = catchThrowableOfType(
                () -> ShipmentStateMachine.transition(null, PICKED_UP), BusinessException.class);
        assertThat(nullFrom).isNotNull();
        assertThat(nullFrom.getErrorCode()).isEqualTo("SHP-10003");

        BusinessException nullTo = catchThrowableOfType(
                () -> ShipmentStateMachine.transition(CREATED, null), BusinessException.class);
        assertThat(nullTo).isNotNull();
        assertThat(nullTo.getErrorCode()).isEqualTo("SHP-10003");

        BusinessException bothNull = catchThrowableOfType(
                () -> ShipmentStateMachine.transition(null, null), BusinessException.class);
        assertThat(bothNull).isNotNull();
        assertThat(bothNull.getErrorCode()).isEqualTo("SHP-10003");
    }
}
