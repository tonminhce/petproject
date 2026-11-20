package com.shop.gateway.ratelimit;

import org.junit.jupiter.api.Test;

import java.util.HashSet;

import static org.assertj.core.api.Assertions.assertThat;

class FleetRateLimitBudgetTest {

    private static FleetRateLimitBudget budget(final int lanes, final int fairShareClients, final int replicas) {
        return FleetRateLimitBudget.from(
                new GlobalRateLimitProperties(true, 2_000, 4_000, 1, lanes, fairShareClients, replicas));
    }

    @Test
    void theFleetBudgetIsSplitIntoFixedLanesAndPerClientShares() {
        var budget = budget(8, 20, 1);

        assertThat(budget.fleetReplenishRate()).isEqualTo(2_000);
        assertThat(budget.laneReplenishRate()).isEqualTo(250);
        assertThat(budget.laneBurstCapacity()).isEqualTo(500);
        assertThat(budget.clientReplenishRate()).isEqualTo(100);
        assertThat(budget.clientBurstCapacity()).isEqualTo(200);
    }

    @Test
    void theFleetBudgetIsTheSameWhicheverReplicaCountIsConfigured() {
        var oneReplica = budget(8, 20, 1);
        var threeReplicas = budget(8, 20, 3);
        var tenReplicas = budget(8, 20, 10);

        assertThat(threeReplicas.fleetReplenishRate()).isEqualTo(oneReplica.fleetReplenishRate());
        assertThat(tenReplicas.fleetReplenishRate()).isEqualTo(oneReplica.fleetReplenishRate());
        assertThat(threeReplicas.laneReplenishRate()).isEqualTo(oneReplica.laneReplenishRate());
        assertThat(tenReplicas.laneReplenishRate()).isEqualTo(oneReplica.laneReplenishRate());
        assertThat(threeReplicas.laneBurstCapacity()).isEqualTo(oneReplica.laneBurstCapacity());
        assertThat(tenReplicas.laneBurstCapacity()).isEqualTo(oneReplica.laneBurstCapacity());
        assertThat(threeReplicas.clientReplenishRate()).isEqualTo(oneReplica.clientReplenishRate());
        assertThat(tenReplicas.clientReplenishRate()).isEqualTo(oneReplica.clientReplenishRate());
        assertThat(threeReplicas.clientBurstCapacity()).isEqualTo(oneReplica.clientBurstCapacity());
        assertThat(tenReplicas.clientBurstCapacity()).isEqualTo(oneReplica.clientBurstCapacity());
    }

    @Test
    void onlyTheReportedPerReplicaShareDependsOnTheReplicaCount() {
        var oneReplica = budget(8, 20, 1);
        var tenReplicas = budget(8, 20, 10);

        assertThat(oneReplica.perReplicaReplenishRate()).isEqualTo(2_000);
        assertThat(tenReplicas.perReplicaReplenishRate()).isEqualTo(200);
    }

    @Test
    void theLaneOfAClientIsStableAndInsideTheLaneRange() {
        var budget = budget(8, 20, 1);

        for (int i = 0; i < 200; i++) {
            var clientKey = "user:client-" + i;
            assertThat(budget.laneIndex(clientKey)).isBetween(0, budget.lanes() - 1);
            assertThat(budget.laneIndex(clientKey)).isEqualTo(budget.laneIndex(clientKey));
        }
    }

    @Test
    void theClientsAreSpreadOverTheLanes() {
        var budget = budget(8, 20, 1);

        var lanes = new HashSet<Integer>();
        for (int i = 0; i < 200; i++) {
            lanes.add(budget.laneIndex("user:client-" + i));
        }

        assertThat(lanes).hasSize(budget.lanes());
    }

    @Test
    void aSingleLaneAndASingleFairClientReproduceTheOldSingleBucketBudget() {
        var budget = budget(1, 1, 1);

        assertThat(budget.laneReplenishRate()).isEqualTo(2_000);
        assertThat(budget.clientReplenishRate()).isEqualTo(2_000);
        assertThat(budget.laneBurstCapacity()).isEqualTo(4_000);
        assertThat(budget.clientBurstCapacity()).isEqualTo(4_000);
    }
}
