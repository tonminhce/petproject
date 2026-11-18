# ADR-003: One gateway rate-limit policy, one Lua script, one failure mode

- Status: Accepted
- Context: The gateway applies three token buckets: a per-route bucket and a fleet-wide
  bucket from the framework, and a custom edge bucket. The framework layers fail open on
  a Redis outage, the custom layer fails closed, and the rejection contract differs
  between them. The edge bucket computes its refill from the gateway JVM clock while
  the framework buckets use the Redis clock.
- Decision: Collapse the three layers into one Lua script and one policy object. The
  script applies the per-route budget, the fleet-wide budget and the edge budget in a
  single round trip and returns the decision with the remaining counts. The edge bucket
  reads the clock from Redis. The Redis outage policy is uniform and fail-closed for
  the privileged scopes.
- Consequences: one round trip instead of two or three, one rejection contract, one
  failure mode. The cross-replica atomicity that the current implementation already has
  is preserved because every mutation remains a single Lua script.
- Evidence: `gateway-service/.../config/RoutesConfig.java:37-43`,
  `gateway-service/.../filter/GlobalRateLimitFilter.java:20-21`,
  `gateway-service/.../filter/RedisEdgeRateLimiter.java:45-73`.
