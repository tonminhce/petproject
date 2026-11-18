# ADR-004: The rate-limit identity comes from the transport peer, not a forwarding header

- Status: Accepted
- Context: `ClientIpResolver` used the framework resolver with a hop count, which picks
  an entry of the `X-Forwarded-For` header by index without ever checking the transport
  peer. No trusted proxy allowlist exists. A non-literal entry also triggered a blocking
  name resolution on the event loop and became a fresh rate-limit key per request.
- Decision: Resolve the client address from the peer address. Consult the forwarding
  header only through an explicit trusted proxy allowlist. Validate the address as a
  literal before it becomes a bucket key, so a crafted header can neither bypass the
  budget nor cause a name resolution on the event loop.
- Consequences: the admin allowlist and every per-address budget can no longer be
  bypassed by a crafted header. The production deployment must declare its proxy hops
  and the ingress must be configured consistently.
- Evidence: `gateway-service/.../filter/ClientIpResolver.java:16-30`,
  `docker-compose.prod.yml:46-48`.
