package com.shop.gateway.ratelimit;

public record FleetRateLimitBudget(
        boolean enabled,
        int fleetReplenishRate,
        long fleetBurstCapacity,
        int requestedTokens,
        int lanes,
        int fairShareClients,
        int replicas
) {

    private static final long MINIMUM_IDLE_SECONDS = 60L;

    public static FleetRateLimitBudget from(final GlobalRateLimitProperties properties) {
        return new FleetRateLimitBudget(
                properties.enabled(),
                properties.replenishRate(),
                properties.burstCapacity(),
                properties.requestedTokens(),
                properties.lanes(),
                properties.fairShareClients(),
                properties.replicas());
    }

    public int laneReplenishRate() {
        return Math.max(1, fleetReplenishRate / lanes);
    }

    public long laneBurstCapacity() {
        return Math.max(requestedTokens, fleetBurstCapacity / lanes);
    }

    public int clientReplenishRate() {
        return Math.max(1, fleetReplenishRate / fairShareClients);
    }

    public long clientBurstCapacity() {
        return Math.max(requestedTokens, fleetBurstCapacity / fairShareClients);
    }

    public int laneIndex(final String clientKey) {
        return Math.floorMod(clientKey.hashCode(), lanes);
    }

    public int perReplicaReplenishRate() {
        return Math.max(1, fleetReplenishRate / Math.max(1, replicas));
    }

    public long laneTtlSeconds() {
        return ttlSeconds(laneBurstCapacity(), laneReplenishRate());
    }

    public long clientTtlSeconds() {
        return ttlSeconds(clientBurstCapacity(), clientReplenishRate());
    }

    private static long ttlSeconds(final long capacity, final int rate) {
        return Math.max(MINIMUM_IDLE_SECONDS, (long) Math.ceil((capacity / (double) rate) * 2));
    }
}
