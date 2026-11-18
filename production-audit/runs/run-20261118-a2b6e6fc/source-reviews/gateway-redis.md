# Gateway / Redis Source Review — A9 (Redis/Gateway) + A10 (Security)

- **Repository:** `/Users/tonminh-mac/IdeaProjects/untitled5`
- **Git revision audited:** `a2b6e6fc336f42f851166cab0cf0d1bba42db12f` (HEAD, working tree clean for all files below)
- **Scope:** `gateway-service` (30 main + 22 test `.java`), `gateway-service/src/main/resources/application.yml`, all repo-wide Redis / Bucket4j / RateLimiter usage, `docker-compose.yml` + `docker-compose.prod.yml` gateway/redis sections, `k8s/gateway-service.yaml`, `k8s/ingress.yaml`.
- **Method:** every `.java` file in `gateway-service` read **in full** with the `read` tool; infra files read at the cited ranges. Framework behaviour was proven against the **resolved** dependency jars (`spring-cloud-gateway-server-webflux:5.0.0`, `spring-web:7.0.9`, `spring-security-web:7.1.1`, `spring-security-config:7.1.1`, Lettuce `7.5.2`, Boot `4.1.1`), not from documentation.
- **Constraint honoured:** no application source file was modified by me. This report is the only file I wrote.

> **Line-number anchor / working-tree note.** Every `file:line` in this report is anchored to **HEAD `a2b6e6fc`** — the audited revision — and each inventory entry records the `git rev-parse HEAD:<path>` blob SHA so the citation can be re-verified against that exact content. While this review was in progress, **another process in the shared working tree stripped Javadoc/`//` comments from ~570 lines across the repository** (`git diff --stat HEAD` shows **575 deletions and 0 insertions** repo-wide — comment blocks only; **no executable statement was altered**). The working-tree copies of the gateway files therefore now have *different* line numbers than HEAD. Re-verify with `git show a2b6e6fc:<path>` (or `git stash`-free read of the blob), not against the working tree. The code excerpts quoted below are byte-identical in both revisions.

---

## 0. TL;DR verdict

| Question in the brief | Verdict from source |
| --- | --- |
| "Spring Cloud Gateway" | **TRUE.** `spring-cloud-gateway-server-webflux:5.0.0` is the edge. The root `pom.xml:49` comment *"removed: spring-cloud (gateway migrated to Apache APISIX)"* is **factually false** — there is no APISIX, no nginx, no Envoy, no Traefik anywhere in the repo. |
| "two token-bucket layers" | **UNDERCOUNT — there are THREE**, all Redis-backed. The brief's "two" is the two Spring-Cloud-Gateway layers; the repo's own custom Lua token bucket (edge scopes) is a third. |
| "bucket state shared across replicas?" | **YES for all three layers** — all mutate Redis via a single Lua `EVAL` (atomic). The *old* per-JVM bucket4j cache the comments describe is gone. **But** the edge layer uses the *gateway JVM clock* instead of the Redis clock, so cross-replica budget is only as good as NTP. |
| "X-Forwarded-For trusted unconditionally?" | **YES, effectively — CRITICAL.** The hop count is an index into an attacker-supplied header; the transport peer is never checked against a trusted-proxy list. Direct access to `:8080` ⇒ full bypass of the admin IP allowlist **and** of the per-IP rate limits. |
| "user id from JWT or spoofable header?" | **JWT (verified) — CORRECT.** `exchange.getPrincipal()` resolves through Spring Security's `SecurityContextServerWebExchange` to the validated `JwtAuthenticationToken`; `getName()` = JWT `sub`. No spoofable header is used for identity. |
| "blocking call on the event loop?" | **YES — two paths.** (a) `X-Forwarded-For` hostname resolution (`new InetSocketAddress(host, 0)` → DNS) on the Netty event loop; (b) the lazy JWT decoder performs a **blocking OIDC discovery + JWKS fetch inside `decode()`**, i.e. on the event loop, on the first authenticated request. |

---

## 1. FILE INVENTORY

`git rev-parse HEAD:<path>` for every file read. "Ranges read" = lines actually read with the `read` tool.

### 1.1 `gateway-service` — MAIN (30 files, 100% read)

| # | Path | `git rev-parse HEAD:<path>` | Lines | Ranges read |
| --- | --- | --- | --- | --- |
| 1 | `gateway-service/src/main/java/com/shop/gateway/GatewayServiceApplication.java` | `2b0154f02ba369a87f484b8934ed25bbf1b6458e` | 14 | 1–14 (full) |
| 2 | `gateway-service/src/main/java/com/shop/gateway/config/EdgeFiltersConfiguration.java` | `66a3743e189ca592e7ba1de1b0a5767573139f56` | 66 | 1–66 (full) |
| 3 | `gateway-service/src/main/java/com/shop/gateway/config/GatewayJwtProperties.java` | `812dcc57a183cf56bcef0907cc5b9c55f0a7d329` | 32 | 1–32 (full) |
| 4 | `gateway-service/src/main/java/com/shop/gateway/config/GatewayJwtSecurityConfig.java` | `2956ff90f3918c7e43ef5560e3e51ac5940ec82c` | 106 | 1–106 (full) |
| 5 | `gateway-service/src/main/java/com/shop/gateway/config/GatewayProperties.java` | `2b680e2e097aa9a03d15bf94d081a372d125d8a5` | 22 | 1–22 (full) |
| 6 | `gateway-service/src/main/java/com/shop/gateway/config/GatewaySecurityPostureValidator.java` | `e14c6f98e0eea861f5f51c9aaf204b2e5fc232e1` | 128 | 1–128 (full) |
| 7 | `gateway-service/src/main/java/com/shop/gateway/config/LazyReactiveJwtDecoder.java` | `c5b893dfaca3abeedec9cbec4d62f8636d6bbdda` | 57 | 1–57 (full) |
| 8 | `gateway-service/src/main/java/com/shop/gateway/config/RouteTargetProperties.java` | `5afa380f9b536b36173afc3af755347b8dd7a247` | 26 | 1–26 (full) |
| 9 | `gateway-service/src/main/java/com/shop/gateway/config/RoutesConfig.java` | `4ebd3d19141630480111df7da6d9b635d9bdf18a` | 49 | 1–49 (full) |
| 10 | `gateway-service/src/main/java/com/shop/gateway/config/SecurityConfig.java` | `7653260e1cfb464c955a5c5f38d29d796a21f23c` | 64 | 1–64 (full) |
| 11 | `gateway-service/src/main/java/com/shop/gateway/constant/ServiceRoute.java` | `17a72ba25c3297b236c292d5b9793c79c5325c48` | 98 | 1–98 (full) |
| 12 | `gateway-service/src/main/java/com/shop/gateway/filter/AdminIpAllowlistFilter.java` | `1c7d45c46d7ad368cd735ebd968183e51974e290` | 151 | 1–151 (full) |
| 13 | `gateway-service/src/main/java/com/shop/gateway/filter/AdminIpAllowlistProperties.java` | `6ce45555ba3d8f8d765041c9b0337644ea8d9b0a` | 30 | 1–30 (full) |
| 14 | `gateway-service/src/main/java/com/shop/gateway/filter/AdminRoleGateFilter.java` | `54be7bcd495764f20b067403f37ea96edd827331` | 122 | 1–122 (full) |
| 15 | `gateway-service/src/main/java/com/shop/gateway/filter/ClientIpResolver.java` | `c713524d6d8609c75fe7aa2eefacfe493eff29fc` | 31 | 1–31 (full) |
| 16 | `gateway-service/src/main/java/com/shop/gateway/filter/EdgeRateLimitProperties.java` | `8591a1f93e4263a6582a8fd926fd837c7194dc97` | 32 | 1–32 (full) |
| 17 | `gateway-service/src/main/java/com/shop/gateway/filter/FilterOrder.java` | `b901689d39c6086da83fd5e9b2b55adf0fdcc42e` | 23 | 1–23 (full) |
| 18 | `gateway-service/src/main/java/com/shop/gateway/filter/GatewayErrorResponseWriter.java` | `f407a4373d9d0c2ccb373bb14c266fc8123e37df` | 116 | 1–116 (full) |
| 19 | `gateway-service/src/main/java/com/shop/gateway/filter/RateLimitFilter.java` | `a2c2b9428716747aba444c71a777404b5b5ddea9` | 119 | 1–119 (full) |
| 20 | `gateway-service/src/main/java/com/shop/gateway/filter/RequestPathGuard.java` | `13c256c4dba7348f0f5a42f005926920d03fade5` | 72 | 1–72 (full) |
| 21 | `gateway-service/src/main/java/com/shop/gateway/filter/RequestPathMatcher.java` | `3be78a3a36ffdcb1da0a4029d1dde2fa614ae964` | 37 | 1–37 (full) |
| 22 | `gateway-service/src/main/java/com/shop/gateway/ratelimit/EdgeRateLimitDecision.java` | `96e78ab9a1328e790f39c75955062749bc5c032d` | 18 | 1–18 (full) |
| 23 | `gateway-service/src/main/java/com/shop/gateway/ratelimit/EdgeRateLimiter.java` | `7a1fd3df0fc506b5c856e62c870f44317e09db13` | 25 | 1–25 (full) |
| 24 | `gateway-service/src/main/java/com/shop/gateway/ratelimit/GlobalRateLimitFilter.java` | `4d361d38ab345864fbe3aab30a19dd44e64670a2` | 69 | 1–69 (full) |
| 25 | `gateway-service/src/main/java/com/shop/gateway/ratelimit/GlobalRateLimitProperties.java` | `08f66e75c613a5ab69d41b0647c8d3f987f081d8` | 26 | 1–26 (full) |
| 26 | `gateway-service/src/main/java/com/shop/gateway/ratelimit/RateLimitConfiguration.java` | `9cf6adae725f46c3ace2c3ef93396eeb9ee0d5bd` | 45 | 1–45 (full) |
| 27 | `gateway-service/src/main/java/com/shop/gateway/ratelimit/RateLimitKeyResolver.java` | `bae3b79a77430d2847933d97a749902238fbe35e` | 67 | 1–67 (full) |
| 28 | `gateway-service/src/main/java/com/shop/gateway/ratelimit/RateLimitProperties.java` | `5fcdec1e5d9b2636b5bac346226ad2dae55e05b3` | 31 | 1–31 (full) |
| 29 | `gateway-service/src/main/java/com/shop/gateway/ratelimit/RedisEdgeRateLimiter.java` | `326be4b1181f9635d287d6587dc84cca7c1368b0` | 141 | 1–141 (full) |
| 30 | `gateway-service/src/main/java/com/shop/gateway/routing/ApiPaths.java` | `635d746f65f17308f1d18887c97959b1b293b3ff` | 28 | 1–28 (full) |

### 1.2 `gateway-service` — TEST (22 files, 100% read)

| # | Path | `git rev-parse HEAD:<path>` | Lines | Ranges read |
| --- | --- | --- | --- | --- |
| 31 | `gateway-service/src/test/java/com/shop/gateway/GatewayRateLimitContextTest.java` | `84d0be9f0f5b56264663f6bf423e4a04616b0ed6` | 73 | 1–73 (full) |
| 32 | `gateway-service/src/test/java/com/shop/gateway/KeycloakRealmImportIT.java` | `76a729e03a98e56ebd18d0a134d36712861c12aa` | 474 | 1–474 (full) |
| 33 | `gateway-service/src/test/java/com/shop/gateway/WebFluxIpAllowlistTests.java` | `52e089126388f4524dbdeed65a15f1bbd9309f93` | 182 | 1–182 (full) |
| 34 | `gateway-service/src/test/java/com/shop/gateway/WebFluxRateLimitTests.java` | `feb1462b3cd2dcc3c32da568c518406652175776` | 199 | 1–199 (full) |
| 35 | `gateway-service/src/test/java/com/shop/gateway/WebFluxRouteTests.java` | `bdc2c41167bb728736eb659df99dec3bc3f12e4b` | 235 | 1–235 (full) |
| 36 | `gateway-service/src/test/java/com/shop/gateway/config/GatewayJwtSecurityConfigTest.java` | `143cddeb442bc8d535c4308a972b13cf6953271a` | 65 | 1–65 (full) |
| 37 | `gateway-service/src/test/java/com/shop/gateway/config/GatewaySecurityPostureValidatorTest.java` | `f6ce6dc71344fb25569afdf48dbf2716e31213f5` | 96 | 1–96 (full) |
| 38 | `gateway-service/src/test/java/com/shop/gateway/config/RoutesConfigTest.java` | `fbbcbf4b32ee7d8648b91e4ff35dde1650e9be06` | 158 | 1–158 (full) |
| 39 | `gateway-service/src/test/java/com/shop/gateway/config/SecurityConfigTest.java` | `0d5d1ebb8cdff1e14ef866af79ca72538cc90970` | 39 | 1–39 (full) |
| 40 | `gateway-service/src/test/java/com/shop/gateway/filter/AdminIpAllowlistFilterTest.java` | `5b31b67c08b795f15195b728a9430b751c52bf28` | 307 | 1–307 (full) |
| 41 | `gateway-service/src/test/java/com/shop/gateway/filter/AdminRoleGateFilterTest.java` | `02cb621ecd8bcbeb0cbb828a2eca3e83d975574b` | 226 | 1–226 (full) |
| 42 | `gateway-service/src/test/java/com/shop/gateway/filter/ClientIpResolverTest.java` | `d81be21b8e2066f2f94614b48d5fe6a9be52798f` | 50 | 1–50 (full) |
| 43 | `gateway-service/src/test/java/com/shop/gateway/filter/FilterOrderTest.java` | `731efb28bcf7c48286f8ab9e1be33fd1188a098c` | 22 | 1–22 (full) |
| 44 | `gateway-service/src/test/java/com/shop/gateway/filter/GatewayErrorResponseWriterTest.java` | `291b73ebb00b3edb2fe889052786187d8c0a3856` | 79 | 1–79 (full) |
| 45 | `gateway-service/src/test/java/com/shop/gateway/filter/RateLimitFilterTest.java` | `27464339eba4b46001a1b107ee10d7a551099c1c` | 269 | 1–269 (full) |
| 46 | `gateway-service/src/test/java/com/shop/gateway/filter/RequestPathMatcherTest.java` | `243fc7c9da35eb4a92adfe84542fae9a9187468a` | 45 | 1–45 (full) |
| 47 | `gateway-service/src/test/java/com/shop/gateway/ratelimit/GlobalRateLimitFilterTest.java` | `39ec11812ac7bca1df8c0d7f82fe002123219205` | 97 | 1–97 (full) |
| 48 | `gateway-service/src/test/java/com/shop/gateway/ratelimit/GlobalRateLimitPropertiesTest.java` | `06ea7071cf1364ea558581f5e84dfb22d754044a` | 26 | 1–26 (full) |
| 49 | `gateway-service/src/test/java/com/shop/gateway/ratelimit/RateLimitKeyResolverTest.java` | `969b43d2a209828c755c2a24e1104404f0898021` | 86 | 1–86 (full) |
| 50 | `gateway-service/src/test/java/com/shop/gateway/ratelimit/RateLimitPropertiesTest.java` | `421f366c558825fe97fdcfc7108d35254ed740df` | 34 | 1–34 (full) |
| 51 | `gateway-service/src/test/java/com/shop/gateway/ratelimit/RedisEdgeRateLimiterTest.java` | `3651bf592302d4b8bc57310cbb0e65abe367dbdb` | 119 | 1–119 (full) |
| 52 | `gateway-service/src/test/java/com/shop/gateway/support/TestKeys.java` | `2c6f8610fbe39badef9bb6ef3d760776559fff3d` | 104 | 1–104 (full) |

### 1.3 Configuration / infra (ranges read as stated)

| Path | `git rev-parse HEAD:<path>` | Lines | Ranges read |
| --- | --- | --- | --- |
| `gateway-service/src/main/resources/application.yml` | (tracked; sha below in §1.4) | 149 | 1–149 (full) |
| `gateway-service/pom.xml` | (tracked) | 124 | 1–124 (full) |
| `pom.xml` (root) | (tracked) | 553 | 25–60, plus `grep -n` for `gateway\|apisix\|nginx\|bucket4j\|resilience4j` |
| `docker-compose.yml` | `51374a0ac0b5cf9cfb9e7abe05c0062d1b9c30e0` | 766 | 60–95 (Redis block), 275–340 (gateway-service block), plus `grep` for gateway/redis/nginx/apisix |
| `docker-compose.prod.yml` | `d98d02163a784fc833b25ba1a57a7f99274cb4eb` | 205 | 1–81 (header + gateway/redis blocks) |
| `k8s/gateway-service.yaml` | `15aae8f0b6eaf4b72a2250e9031e6033d0b262d2` | 92 | 1–92 (full) |
| `k8s/ingress.yaml` | `a51170cec1759f7438e0e2a1ccd14e4540925940` | 27 | 1–27 (full) |
| `k8s/microservices.yaml` | `bd7cd8f75b68ee473dc7168010e02d31677ca055` | 767 | `grep` only (kind/name census — see §2.4) |
| `.env.example` | `d6bb5c15bc85228f7930fdebf2eda9b43e9d8a00` | 237 | `grep` REDIS/GATEWAY (lines 27–42) |
| `.env.prod.example` | `3dcf40d8a49dc5625dafa9b6e21fe29e6d980e55` | 191 | `grep` REDIS/GATEWAY (lines 20–46, 88–91) |
| `utils/common-core/src/main/java/com/shop/common/core/exception/ErrorCode.java` | `e831125a781614fe4cdd7f5e63c24623fc0bfc78` | 161 | 1–80 (enum head) |

### 1.4 Framework sources read (from the resolved `.m2` artifacts — not documentation)

| Artifact | File read | Purpose |
| --- | --- | --- |
| `spring-cloud-gateway-server-webflux-5.0.0-sources.jar` | `filter/ratelimit/RedisRateLimiter.java` | Layer 1/2 algorithm, Redis key format, **fail-open** behaviour |
| `spring-cloud-gateway-server-webflux-5.0.0-sources.jar` | `filter/factory/RequestRateLimiterGatewayFilterFactory.java` | Layer 1 filter, empty-key handling, header emission |
| `spring-cloud-gateway-server-webflux-5.0.0-sources.jar` | `support/ipresolver/XForwardedRemoteAddressResolver.java` | **Trusted-proxy semantics** (§5) |
| `spring-cloud-gateway-server-webflux-5.0.0-sources.jar` | `config/GatewayRedisAutoConfiguration.java` | Lua script bean wiring |
| `spring-cloud-gateway-server-webflux-5.0.0-sources.jar` | `filter/ratelimit/AbstractRateLimiter.java`, `support/AbstractStatefulConfigurable.java` | Mutable config map (§8) |
| `spring-cloud-gateway-server-webflux-5.0.1.jar` | `META-INF/scripts/request_rate_limiter.lua` | Layer 1/2 Lua script (identical in 5.0.0) |
| `spring-web-7.0.9-sources.jar` | `server/ServerWebExchange.java`, `server/adapter/DefaultServerWebExchange.java`, `server/adapter/HttpWebHandlerAdapter.java` | `getPrincipal()` semantics (§5.3) |
| `spring-security-web-7.1.1-sources.jar` | `web/server/context/SecurityContextServerWebExchange.java`, `...WebFilter.java` | Principal propagation |
| `spring-security-config-7.1.1-sources.jar` | `config/web/server/ServerHttpSecurity.java` (lines 1176–1201) | `SecurityContextServerWebExchangeWebFilter` registration |

---

## 2. THE REAL GATEWAY STACK

### 2.1 Verdict: Spring Cloud Gateway (WebFlux). The APISIX claim is false.

`pom.xml:49`:
```
        <!-- removed: spring-cloud (gateway migrated to Apache APISIX) -->
```
This comment is **in a `<properties>` block of a POM that still imports `spring-cloud-dependencies`** (`pom.xml:42,80-81`), and the gateway module depends on it:

`gateway-service/pom.xml:37-40`:
```xml
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-gateway-server-webflux</artifactId>
        </dependency>
```

Resolved tree (offline `mvnw -o -pl gateway-service dependency:tree`):
```
+- org.springframework.boot:spring-boot-starter-webflux:jar:4.1.1:compile
|     +- org.springframework:spring-webflux:jar:7.0.9:compile
+- org.springframework:spring-web:jar:7.0.9:compile
+- org.springframework.cloud:spring-cloud-gateway-server-webflux:jar:5.0.0:compile
+- org.springframework.boot:spring-boot-starter-oauth2-resource-server:jar:4.1.1:compile
+- org.springframework.boot:spring-boot-starter-data-redis:jar:4.1.1:compile
|     +- io.lettuce:lettuce-core:jar:7.5.2.RELEASE:compile
+- org.springframework.security:spring-security-web:jar:7.1.1:compile
```

Repo-wide `grep -ril "apisix"` returns only: `pom.xml`, `docs/ROADMAP.md`, `plan.md`, `.gitnexus/lbug`, and the audit's own `comments-scan.json`. **There is no APISIX config, no APISIX dependency, no APISIX container.** The same `grep` for `*nginx*`/`*apisix*`/`*.conf` returns **zero** files — there is no nginx configuration in this repository at all.

### 2.2 The real request path

**Production (Kubernetes), the only topology with a real front door:**

```
client
  → nginx ingress controller  (k8s/ingress.yaml:6  kubernetes.io/ingress.class: nginx)
      host api.shop.example.com, path / → service gateway-service:8080   (k8s/ingress.yaml:23-29)
  → gateway-service (Spring Cloud Gateway, WebFlux, Netty)  (k8s/gateway-service.yaml:1-46, 3 replicas + HPA 2..10)
      Reactor Netty :8080
  → Spring Security WebFilter chain (SecurityConfig.java:37-49)  → JWT validation (GatewayJwtSecurityConfig.java:57-65)
  → GlobalFilter chain (FilterOrder.java:15-19)
      AdminIpAllowlistFilter (HIGHEST_PRECEDENCE)
      GlobalRateLimitFilter    (HIGHEST_PRECEDENCE — TIE, see GW-08)
      RateLimitFilter          (HIGHEST_PRECEDENCE+10)
      AdminRoleGateFilter      (HIGHEST_PRECEDENCE+20)
  → per-route filter chain (RoutesConfig.java:31-48)
      RequestRateLimiter (RequestRateLimiterGatewayFilterFactory) → RedisRateLimiter
  → NettyRoutingFilter → http://<service>:<port>  (ServiceRoute.java:10-39, ApiPaths.java:26-28)
  → backend microservice
```

Evidence per hop:

| Hop | Evidence |
| --- | --- |
| nginx ingress → gateway | `k8s/ingress.yaml:5-6` (`ingress.class: nginx`), `k8s/ingress.yaml:23-29` (`service: gateway-service / port 8080`) |
| gateway replicas | `k8s/gateway-service.yaml:10` (`replicas: 3`), `k8s/gateway-service.yaml:74-92` (HPA `minReplicas: 2`, `maxReplicas: 10`) |
| gateway listens | `gateway-service/src/main/resources/application.yml:2` (`server.port: 8080`) |
| security chain | `config/SecurityConfig.java:37-49` (`SecurityWebFilterChain`, `oauth2ResourceServer(oauth2 -> oauth2.jwt(...))`) |
| JWT decoder | `config/GatewayJwtSecurityConfig.java:57-65` (`NimbusReactiveJwtDecoder.withIssuerLocation(issuerUri)`) |
| global filter order | `filter/FilterOrder.java:15,17,19`; `ratelimit/GlobalRateLimitFilter.java:56-59` |
| edge rate limit | `filter/RateLimitFilter.java:65-86` |
| role gate | `filter/AdminRoleGateFilter.java:54-80` |
| route table | `config/RoutesConfig.java:31-48`; `constant/ServiceRoute.java:10-39` |
| route target | `routing/ApiPaths.java:26-28` (`"http://" + serviceName + ":" + port`), e.g. `ServiceRoute.java:29` → `http://product-service:8086` |

**Docker Compose (dev / prod-override):**

```
client → host port 8080 (docker-compose.yml:287-288 "8080:8080";
         docker-compose.prod.yml:46-48 "${GATEWAY_INGRESS_PORT:-8080}:8080")
       → gateway-service container (Spring Cloud Gateway)
       → http://<service>:<port> on the `ecommerce-network` bridge
```

`docker-compose.yml:279-282` names the section *"GATEWAY (Spring Cloud Gateway - new)"* — the compose file itself contradicts the POM comment.

**There is NO reverse proxy inside the compose stack.** `docker-compose.prod.yml:24-28` states the requirement in a comment only:
```
# ⚠ TRUSTED-PROXY REQUIREMENT — docs/PRODUCTION-READINESS.md §4.1:
#   The gateway trusts the FIRST X-Forwarded-For entry (allowlist + rate-limit
#   keying). This topology is only sound behind a trusted reverse proxy / LB
#   that OVERWRITES XFF before traffic reaches :8080. Never expose the gateway
#   port directly to the internet.
```
Three separate problems with that comment, all evidenced below:
1. **"FIRST entry" is wrong** — the code selects the **rightmost** entry (§5.1).
2. **"rate-limit key: XFF entry at index 1"** (`docker-compose.prod.yml:61`) is wrong for the same reason.
3. The requirement is **not enforced by anything** — the gateway still publishes `:8080` on the host, and nothing in the code verifies that the TCP peer is the LB. → **GW-01**.

### 2.3 What actually fronts the microservices — summary

There is exactly **one** HTTP edge implementation: the Spring Cloud Gateway `gateway-service`. APISIX/nginx exist only as prose. The "two token-bucket layers" in the brief are the two Spring-Cloud-Gateway limiters; the third, custom Lua bucket (edge scopes) is the repo's own and is the one the brief's F-034 comments talk about.

### 2.4 Kubernetes manifest census (relevant to §2 + GW-11)

`grep -h "^kind:" k8s/*.yaml | sort | uniq -c`:
```
       2 kind: ConfigMap
      15 kind: Deployment      <-- 14 microservices + gateway-service
       1 kind: HorizontalPodAutoscaler
       1 kind: Ingress
       1 kind: Namespace
       1 kind: PrometheusRule
       1 kind: Secret
      15 kind: Service
```
`grep -rn "redis" k8s/*.yaml` returns **exactly one line**:
```
k8s/gateway-service.yaml:31:              value: "redis.shop-system.svc.cluster.local"
```
⇒ **No Redis Deployment or Service exists in any k8s manifest in this repo**, yet the gateway is pointed at one. See **GW-11**.

---

## 3. RATE LIMITING — the three layers that actually exist

### 3.0 Summary table

| # | Layer | Where | Algorithm | Library | State | Key | Limits | TTL | Rejection | Redis outage |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| **L1** | Per-route `RequestRateLimiter` on all 29 routes | `config/RoutesConfig.java:37-43` | token bucket | Spring Cloud Gateway `RedisRateLimiter` (Lua) | **Redis** | `request_rate_limiter.{<routeId>.<user:sub \| ip:addr>}` | replenish **100 tok/s**, burst **200**, 1 tok/req | `floor(capacity/rate*2)` = 4 s | **429**, no body, `X-RateLimit-Remaining/Replenish-Rate/Burst-Capacity/Requested-Tokens` | **FAIL-OPEN** → allowed |
| **L2** | Fleet-wide capacity guard | `ratelimit/GlobalRateLimitFilter.java:44-54` | token bucket | SCG `RedisRateLimiter` (Lua) | **Redis** | `request_rate_limiter.{gateway-system.system}` — **ONE key for the whole fleet** | replenish **2000 tok/s**, burst **4000**, 1 tok/req | 4 s | **429** + fleet `ApiResponse` envelope + 4 `X-RateLimit-*` headers | **FAIL-OPEN** → allowed |
| **L3** | Edge scopes: backoffice + search | `filter/RateLimitFilter.java:65-102` | token bucket (custom) | **own Lua script** (`RedisEdgeRateLimiter.java:45-73`) | **Redis** | `gateway:edge-rate-limit:<SCOPE>:<clientIp>` (+ `:tokens`, `:timestamp`) | backoffice **10 req/min** (capacity 10, refill 10/60 s⁻¹), search **60 req/min** | `ceil((capacity/rate)*2)` = **120 s** | **429** + fleet `ApiResponse` envelope + `X-RateLimit-Remaining: 0` | **FAIL-CLOSED** → HTTP 500 |

### 3.1 L1 — per-route limiter (the brief's "layer 1")

Wiring — `config/RoutesConfig.java:31-48`:
```java
    @Bean
    public RouteLocator gatewayRoutes(final RouteLocatorBuilder builder) {
        final var routesBuilder = builder.routes();
        for (final var route : ServiceRoute.values()) {
            routesBuilder.route(route.id(), spec -> {
                final var routeSpec = spec.path(route.path());
                if (rateLimitProperties.enabled()) {
                    routeSpec.filters(filters -> filters.requestRateLimiter(config -> config
                            .setRateLimiter(rateLimiter)
                            .setKeyResolver(keyResolver)
                            .setDenyEmptyKey(true)
                            .setEmptyKeyStatus("429")));
                }
                return routeSpec.uri(routeTargetProperties.resolve(route.serviceName(), route.uri()));
            });
        }
        return routesBuilder.build();
    }
```
- **Algorithm:** SCG token bucket, `META-INF/scripts/request_rate_limiter.lua` (verbatim from the resolved jar):
  ```lua
  local rate = tonumber(ARGV[1])
  local capacity = tonumber(ARGV[2])
  local now = tonumber(ARGV[3]) or redis.call('TIME')[1]
  ...
  local delta = math.max(0, now-last_refreshed)
  local filled_tokens = math.min(capacity, last_tokens+(delta*rate))
  local allowed = filled_tokens >= requested
  local new_tokens = allowed and filled_tokens - requested or filled_tokens
  if ttl > 0 then
    redis.call("setex", tokens_key, ttl, new_tokens)
    redis.call("setex", timestamp_key, ttl, now)
  end
  ```
- **Library:** `org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter` — **not** Bucket4j, **not** Resilience4j. (The framework also ships `Bucket4jRateLimiter`, but it is **not used** — see §3.4.)
- **State:** Redis, keys `request_rate_limiter.{<routeId>.<key>}.tokens` / `.timestamp` (SCG `RedisRateLimiter.getKeys`). The `{...}` hash tag guarantees the two keys share a slot → cluster-safe. ✔
- **Atomicity:** a single Lua `EVAL` → read + refill + consume + write are one atomic step. ✔ **PROVEN CORRECT.**
- **Key construction:** `ratelimit/RateLimitKeyResolver.java:36-44` → `user:<jwt sub>` if authenticated, else `ip:<client ip>`.
- **Limits / units:** `RateLimitProperties.java:15-21` — `replenishRate=100`, `burstCapacity=200`, `requestedTokens=1`. **The unit is tokens per SECOND** (SCG semantics), i.e. 100 rps sustained, 200 burst, *per route, per key*. See **GW-17** for the unit ambiguity.
- **TTL:** `floor(capacity/rate*2)` = `floor(200/100*2)` = **4 s**.
- **Rejection:** `RequestRateLimiterGatewayFilterFactory` → `setResponseStatus(exchange, config.getStatusCode())` with default `HttpStatus.TOO_MANY_REQUESTS` (429) and `exchange.getResponse().setComplete()` → **429 with an EMPTY body**, i.e. it does **not** use the fleet `ApiResponse` envelope that L2 and L3 use. Inconsistent error contract → **GW-07**.
- **Empty key:** `setDenyEmptyKey(true)` + `setEmptyKeyStatus("429")` → a request with neither a principal nor a remote address gets a bare 429.
- **Redis outage:** **FAIL-OPEN.** SCG `RedisRateLimiter.isAllowed`:
  ```java
  Flux<List<Long>> flux = this.redisTemplate.execute(this.script, keys, scriptArgs);
  return flux.onErrorResume(throwable -> {
      log.error("Error calling rate limiter lua", throwable);
      return Flux.just(Arrays.asList(1L, -1L));
  })
  ```
  `[1, -1]` → `allowed = true`, `tokensLeft = -1` → **the request is allowed**. The outer `catch (Exception e)` also returns `Mono.just(new Response(true, ...))`. Verified present in the resolved **5.0.1** bytecode (`javap -c` shows `Flux.onErrorResume` + the literal `"Error calling rate limiter lua"`), so the behaviour is identical in the 5.0.0 the build resolves.

### 3.2 L2 — the "global" fleet limiter

`ratelimit/GlobalRateLimitFilter.java:18-59`:
```java
public final class GlobalRateLimitFilter implements GlobalFilter, Ordered {

    private static final String GLOBAL_ROUTE_ID = "gateway-system";
    private static final String GLOBAL_KEY = "system";
    ...
    @Override
    public Mono<Void> filter(final ServerWebExchange exchange, final GatewayFilterChain chain) {
        if (!rateLimitProperties.enabled() || !globalRateLimitProperties.enabled()) {
            return chain.filter(exchange);
        }

        return rateLimiter.isAllowed(GLOBAL_ROUTE_ID, GLOBAL_KEY)
                .flatMap(response -> response.isAllowed()
                        ? chain.filter(exchange)
                        : reject(exchange, response.getHeaders()));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
```
- **Key: `("gateway-system", "system")` — a single, fleet-wide, non-partitioned bucket.** Every request in the fleet (every user, every route, every IP) consumes from the same 4000-token bucket. → **GW-04** (no fairness; one client can starve the whole fleet; the cap does not scale with the 2–10 k8s replicas).
- **Limits:** `GlobalRateLimitProperties.java:14-19` — replenish **2000 tok/s**, burst **4000**, 1 tok/req. TTL 4 s.
- **Rejection:** 429 + **fleet `ApiResponse` envelope** (`ERR-0429`) via `GatewayErrorResponseWriter`, plus the four `X-RateLimit-*` headers (`GlobalRateLimitFilter.java:61-68`).
- **Headers on ALLOW:** **absent** — `reject()` is the only place that copies `response.getHeaders()`. So a successful request carries no `X-RateLimit-*` from L2 (inconsistent with L1, which always emits them). → **GW-07**.
- **Redis outage:** **FAIL-OPEN** (same SCG code path as L1).
- **Note:** `GlobalRateLimitFilter` is only wired when **both** `gateway.rate-limit.enabled` and `gateway.rate-limit.global.enabled` are true (`GlobalRateLimitFilter.java:46`). `application.yml:88` and `:101` default both to `true`.

### 3.3 L3 — the edge scopes (backoffice + search), the repo's own Lua bucket

`filter/RateLimitFilter.java:65-102`:
```java
    @Override
    public Mono<Void> filter(final ServerWebExchange exchange, final GatewayFilterChain chain) {
        if (!properties.enabled()) {
            return chain.filter(exchange);
        }
        final String rawPath = exchange.getRequest().getPath().value();
        if (RequestPathGuard.containsMatrixVariable(rawPath)) {
            log.warn("Rejected matrix-variable path in an edge scope: {}", rawPath);
            return errorResponseWriter.write(exchange, ErrorCode.BAD_REQUEST);
        }
        final Scope scope = scopeFor(RequestPathGuard.decoded(rawPath));
        if (scope == null) {
            return chain.filter(exchange);
        }

        final String clientIp = clientIpResolver.resolve(exchange);
        final int requestsPerMinute = scope == Scope.BACKOFFICE
                ? properties.backofficeRequestsPerMinute()
                : properties.searchRequestsPerMinute();
        return rateLimiter.acquire(scope.name(), clientIp, requestsPerMinute)
                .flatMap(decision -> applyDecision(exchange, chain, decision, rawPath));
    }
```
and `ratelimit/RedisEdgeRateLimiter.java:88-109`:
```java
    @Override
    public Mono<EdgeRateLimitDecision> acquire(final String scope, final String clientIp, final int requestsPerMinute) {
        final double rate = requestsPerMinute / 60.0d;
        final long now = Instant.now().getEpochSecond();
        final List<String> keys = List.of(
                KEY_PREFIX + scope + ":" + clientIp + ":tokens",
                KEY_PREFIX + scope + ":" + clientIp + ":timestamp");
        return redis.execute(TOKEN_BUCKET, keys,
                        Integer.toString(requestsPerMinute),
                        Double.toString(rate),
                        Long.toString(now),
                        "1")
                .next()
                .map(this::toDecision)
                .doOnError(error -> log.error("Edge rate limiter failed for scope {} - the budget of this "
                        + "client cannot be metered", scope, error));
    }
```
- **Algorithm:** custom token bucket, `RedisEdgeRateLimiter.java:45-73`:
  ```lua
  local stored = redis.call('get', KEYS[1])
  local refreshed = redis.call('get', KEYS[2])
  local tokens = capacity
  local last = now
  ...
  local delta = math.max(0, now - last)
  local filled = math.min(capacity, tokens + (delta * rate))
  local allowed = 0
  if filled >= requested then
      allowed = 1
      filled = filled - requested
  end
  local ttl = math.ceil((capacity / rate) * 2)
  if ttl > 0 then
      redis.call('setex', KEYS[1], ttl, filled)
      redis.call('setex', KEYS[2], ttl, now)
  end
  return {allowed, math.floor(filled)}
  ```
- **Library:** none — hand-written Lua, executed via `ReactiveStringRedisTemplate.execute(RedisScript, keys, args)` (**non-blocking Lettuce reactive** ✔).
- **State:** Redis. Keys `gateway:edge-rate-limit:<SCOPE>:<clientIp>:tokens` / `:timestamp`. **No `{...}` hash tag** → in Redis Cluster the two keys may hash to different slots and `EVAL` fails with `CROSSSLOT` → **GW-12**.
- **Atomicity:** a single `EVAL` → **atomic** ✔ **PROVEN CORRECT** (the brief's "separate GET then SET = race?" answer is: **no race**, it is one script).
- **Key construction:** `scope` ∈ {`BACKOFFICE`, `SEARCH`} × **client IP only** — never the user. See `RedisEdgeRateLimiter.java:92-94`.
- **Limits:** capacity = `requestsPerMinute`, refill = `requestsPerMinute/60` tokens per second. `EdgeRateLimitProperties.java:23-27`: backoffice **10**, search **60**. `application.yml:113-114` maps them to `GATEWAY_EDGE_RATE_LIMIT_BACKOFFICE_RPM` / `..._SEARCH_RPM`.
- **TTL:** `ceil((capacity/rate)*2)` = `ceil(60*2)` = **120 s** for both scopes.
- **Rejection:** `RateLimitFilter.java:92-95` → `X-RateLimit-Remaining: 0` + fleet `ApiResponse` envelope `ERR-0429` + **429**. No `Retry-After` anywhere in the module (`grep -rn "Retry-After" gateway-service/src/main/` → 0 hits) → **GW-18**.
- **Redis outage:** **FAIL-CLOSED.** The error is *not* swallowed (`doOnError` only logs), so the `Mono` errors → the gateway's error handler produces **HTTP 500**. And the comment at `RedisEdgeRateLimiter.java:102-108`:
  ```java
                // A Redis outage is NOT reported as "too many requests": the
                // error propagates, so the gateway answers with a server error
                // exactly like the two Redis-backed tiers above do. Hiding it
                // behind a 429 would tell the caller to slow down while the
                // real cause is the shared limiter being unavailable.
  ```
  **This comment is FALSE.** The two tiers above fail **open** (§3.1/§3.2). → **GW-03**.
- **Note (PROVEN CORRECT):** a script that returns nothing is treated as a rejection (`RedisEdgeRateLimiter.java:111-116`) → fail-closed, good.

### 3.4 "Two token-bucket layers" — the brief is wrong, and so are the in-code comments about Bucket4j

- **No Bucket4j dependency exists anywhere.** `grep -n "bucket4j\|Bucket4j" pom.xml gateway-service/pom.xml utils/*/pom.xml` → **0 hits**. The framework ships `Bucket4jRateLimiter` but the repo does not use it.
- The Javadoc/comments in `RedisEdgeRateLimiter.java:16-28`, `RateLimitFilter.java:20-25` and `EdgeRateLimiter.java:5-12` all describe an **in-process bucket4j cache** as the *previous* implementation. That code no longer exists — the comments are accurate history, but they repeatedly assert *"the two Redis-backed tiers above"*, which is how the "two layers" belief propagated.
- **No Resilience4j in the gateway** (resilience4j is declared in the root POM `pom.xml:153-160` and used by `utils/common-spring/pom.xml:95-96`, i.e. the servlet services). There is **no circuit breaker and no bulkhead** at the gateway. `GatewayResilience4JCircuitBreakerAutoConfiguration` exists in the SCG jar but is not activated (no `spring.cloud.gateway.filter.circuit-breaker.enabled`).

### 3.5 Per-request Redis cost

| Request type | Redis round trips |
| --- | --- |
| any route | **2** — L2 `EVAL` + L1 `EVAL` |
| `/api/v1/backoffice/**` or `/api/v1/search/**` | **3** — L2 + L3 + L1 |

At the highest-QPS tier this is 2–3 serialised Redis round trips per request, and L3 is largely redundant with L1 for the same scope. → **GW-06**.

---

## 4. CRITICAL: is the bucket state shared across replicas? Is the mutation atomic?

### 4.1 Verdict: **YES, shared — and YES, atomic.** The "N replicas = N × limit" bug is genuinely fixed.

The old design is documented in the source as history:
- `ratelimit/EdgeRateLimiter.java:5-12`:
  > *"The implementation MUST be fleet-wide: an in-process bucket multiplies the effective limit by the replica count, so with four gateway replicas a single client could make 4 x 10 backoffice requests per minute."*
- `ratelimit/RedisEdgeRateLimiter.java:17-21`:
  > *"The bucket lives in Redis, so every gateway replica meters the same client against the same budget."*

**Proof of shared state (L3)** — `RedisEdgeRateLimiter.java:45-73, 88-109`: all state is in the two Redis keys; there is **no** `Map`, `ConcurrentHashMap`, `Cache` or any other in-JVM bucket anywhere in `gateway-service` (`grep -rn "ConcurrentHashMap\|HashMap\|Caffeine\|Cache<" gateway-service/src/main/` → only `GatewayErrorResponseWriter.java:34` `Map.of(...)` for the immutable error-message table and `RouteTargetProperties.java:20` `Map.copyOf`). The proof is also executable: `gateway-service/src/test/java/com/shop/gateway/ratelimit/RedisEdgeRateLimiterTest.java:58-78`
```java
    @Test
    @DisplayName("two replicas meter the client against ONE shared budget")
    void twoReplicasShareOneBucket() {
        var properties = new EdgeRateLimitProperties(true, 3, 60);
        var replicaA = limiter(properties);
        var replicaB = limiter(properties);
        ...
        var third = replicaB.acquire("BACKOFFICE", "203.0.113.7", 3).block();
        assertThat(third.allowed()).isTrue();
        assertThat(third.remaining()).isZero();
        assertThat(replicaA.acquire("BACKOFFICE", "203.0.113.7", 3).block().allowed()).isFalse();
        assertThat(replicaB.acquire("BACKOFFICE", "203.0.113.7", 3).block().allowed()).isFalse();
    }
```
Two independent limiter instances against one Redis share one budget. ✔

**Proof of atomicity (L3)** — one `redis.execute(TOKEN_BUCKET, keys, ...)` (`RedisEdgeRateLimiter.java:95-99`) with a Lua script that does `GET`/`GET`/compute/`SETEX`/`SETEX` inside a single `EVAL`. Redis executes a script atomically. There is **no** GET-then-SET race. ✔ **PROVEN CORRECT.**

**Proof of atomicity + shared state (L1/L2)** — SCG `RedisRateLimiter` uses the same pattern: one `redisTemplate.execute(this.script, keys, scriptArgs)` with `META-INF/scripts/request_rate_limiter.lua`. ✔ **PROVEN CORRECT.**

### 4.2 …but the edge bucket uses the **gateway JVM clock**, not the Redis clock

`RedisEdgeRateLimiter.java:90-91`:
```java
        final double rate = requestsPerMinute / 60.0d;
        final long now = Instant.now().getEpochSecond();
```
`now` is taken from **each gateway replica's own wall clock** and passed as `ARGV[3]`. The SCG script deliberately does the opposite (`META-INF/scripts/request_rate_limiter.lua`):
```lua
local now = tonumber(ARGV[3]) or redis.call('TIME')[1]
```
and SCG passes `""` for `ARGV[3]` → the **Redis server** clock is used.

Consequence: the edge bucket's refill is computed from `max(0, now - last)` where `now` is a *client* timestamp that is also **written back** as the new `last` (`RedisEdgeRateLimiter.java:69` `redis.call('setex', KEYS[2], ttl, now)`). With N replicas whose clocks differ by Δ:
- a replica whose clock is **ahead** by Δ computes `delta = Δ + elapsed` → **over-refills** by `Δ × rate` tokens and then persists the skewed `last`, so the *next* replica (possibly correctly clocked) computes `delta = max(0, small_now - skewed_last) = 0` → **no refill at all** for that interval.
- the effective limit therefore becomes a function of the clock spread across replicas, and a replica with a fast clock can spend the budget of a replica with a slow clock.

This is the one place where the "fleet-wide budget" property **does not hold exactly**. → **GW-05**.

### 4.3 Remaining cross-replica caveats

| Caveat | Where | Severity |
| --- | --- | --- |
| Gateway-JVM clock vs Redis clock (above) | `RedisEdgeRateLimiter.java:91` | GW-05 (HIGH) |
| L3 keys have no Redis-Cluster hash tag → `CROSSSLOT` | `RedisEdgeRateLimiter.java:92-94` | GW-12 (MEDIUM) |
| All three layers share **one** Redis instance with the service caches (`inventory`, `order`, `product` services also use Redis — `grep -ril redis` hits `inventory-service/.../CacheConfig.java`, `order-service/.../CacheConfig.java`, `product-service/.../CacheConfig.java`) ⇒ rate-limiter traffic and cache traffic compete for the same `maxmemory`/eviction budget | `docker-compose.yml:68-81` | GW-14 (LOW/MEDIUM) |
| L2 is a single fleet-wide bucket → the fleet-wide cap is 2000 rps **regardless of replica count** (HPA scales 2→10 but the cap does not move) | `GlobalRateLimitFilter.java:20-21` | GW-04 (HIGH) |

---

## 5. TRUSTED-PROXY IDENTITY

### 5.1 How the client IP is actually extracted — and why the hop count is not a trust boundary

`filter/ClientIpResolver.java:10-30`:
```java
public final class ClientIpResolver {
    private static final String UNKNOWN_IP = "unknown";
    private final Optional<XForwardedRemoteAddressResolver> forwardedAddressResolver;

    public ClientIpResolver() { this(0); }

    public ClientIpResolver(final int trustedProxyHops) {
        if (trustedProxyHops < 0) throw new IllegalArgumentException("trustedProxyHops must be >= 0");
        this.forwardedAddressResolver = trustedProxyHops == 0 ? Optional.empty()
                : Optional.of(XForwardedRemoteAddressResolver.maxTrustedIndex(trustedProxyHops));
    }

    public String resolve(final ServerWebExchange exchange) {
        final InetSocketAddress address = forwardedAddressResolver.map(r -> r.resolve(exchange))
                .orElseGet(() -> exchange.getRequest().getRemoteAddress());
        if (address == null) return UNKNOWN_IP;
        if (address.getAddress() != null) return address.getAddress().getHostAddress();
        final String host = address.getHostString();
        final int colon = host.lastIndexOf(':');
        return colon > 0 && host.indexOf(':') == colon ? host.substring(0, colon) : host;
    }
}
```

The framework method it delegates to — `spring-cloud-gateway-server-webflux-5.0.0-sources.jar`,
`org/springframework/cloud/gateway/support/ipresolver/XForwardedRemoteAddressResolver.java`:
```java
	public static XForwardedRemoteAddressResolver maxTrustedIndex(int maxTrustedIndex) {
		Assert.isTrue(maxTrustedIndex > 0, "An index greater than 0 is required");
		return new XForwardedRemoteAddressResolver(maxTrustedIndex);
	}

	@Override
	public InetSocketAddress resolve(ServerWebExchange exchange) {
		List<String> xForwardedValues = extractXForwardedValues(exchange);
		if (!xForwardedValues.isEmpty()) {
			int index = Math.max(0, xForwardedValues.size() - maxTrustedIndex);
			return new InetSocketAddress(xForwardedValues.get(index), 0);
		}
		return defaultRemoteIpResolver.resolve(exchange);
	}

	private List<String> extractXForwardedValues(ServerWebExchange exchange) {
		List<String> xForwardedValues = exchange.getRequest().getHeaders().get(X_FORWARDED_FOR);
		if (xForwardedValues == null || xForwardedValues.isEmpty()) {
			return Collections.emptyList();
		}
		if (xForwardedValues.size() > 1) {
			log.warn("Multiple X-Forwarded-For headers found, discarding all");
			return Collections.emptyList();
		}
		String[] values = StringUtils.tokenizeToStringArray(xForwardedValues.get(0), ",");
		...
	}
```

**What this means, precisely:**

1. With `trustedProxyHops = 1` (the shipped default — `application.yml:99`, `docker-compose.prod.yml:62`), the selected entry is `xForwardedValues.get(size - 1)` = the **rightmost** entry of the single `X-Forwarded-For` header.
2. The resolver **never inspects `exchange.getRequest().getRemoteAddress()`** when the header is present. There is **no allow-list of trusted proxy addresses** anywhere in the codebase (`grep -rn "trustedProxies\|proxy-allowlist\|TRUSTED_PROXY" gateway-service/src/` → 0 hits).
3. ⇒ **The rightmost `X-Forwarded-For` entry is fully attacker-controlled** whenever the client can reach the gateway without traversing the trusted LB (or when the LB passes the header through / appends after the attacker's value).
4. The repo's own test pins this behaviour: `filter/ClientIpResolverTest.java:12-16`
   ```java
       void selectsClientAddressFromConfiguredTrustedChain() {
           var exchange = exchangeWithXff("203.0.113.7, 70.41.3.18, 10.0.0.1");
           assertThat(resolver.resolve(exchange)).isEqualTo("10.0.0.1");
       }
   ```
   and `filter/ClientIpResolverTest.java:39-42` shows a **port-suffixed** entry is accepted (`"203.0.113.7:41234"` → `"203.0.113.7"`), i.e. the entry is not even required to be a bare IP.
5. `X-Real-IP` is **not** read anywhere (`grep -rn "X-Real-IP" gateway-service/` → 0 hits) ✔ — one spoofing vector is genuinely closed.

**Where the trust model is asserted but not enforced**

| Assertion | Evidence | Reality |
| --- | --- | --- |
| "The gateway trusts the FIRST X-Forwarded-For entry" | `docker-compose.prod.yml:25-26` | **Wrong** — it is the rightmost entry. |
| "rate-limit key: XFF entry at index 1" | `docker-compose.prod.yml:61` | **Wrong** — index is `size-1` from the right. |
| "only sound behind a trusted reverse proxy / LB that OVERWRITES XFF … Never expose the gateway port directly" | `docker-compose.prod.yml:24-28` | The compose file **does** publish the port (`docker-compose.prod.yml:46-48`, `docker-compose.yml:287-288`) and nothing enforces the LB. |
| "The gateway MUST resolve the client IP itself … with 'framework' Spring rewrites the remote address from the client-supplied XFF, taking the LEFTMOST entry" | `application.yml:4-15` | The comment about `framework` is **accurate** (Boot's `ForwardedHeaderTransformer` takes the leftmost). The chosen value is `none` ✔, so `ForwardedHeaderTransformer` is **not** registered and `getRemoteAddress()` is the true TCP peer. ✔ **PROVEN CORRECT.** |
| "A hop count of 0 … makes the client-IP trust model either spoofable (behind the ForwardedHeaderTransformer) or collapsed onto the proxy's address" | `config/GatewaySecurityPostureValidator.java:73-82` | Accurate ✔. But the validator only fires for `hops <= 0` **and** a non-`none` strategy (`GatewaySecurityPostureValidator.java:114-127`); it never validates that the peer is a trusted proxy ⇒ **GW-20**. |

**Dev vs prod divergence**
- dev `docker-compose.yml:301` → `GATEWAY_RATE_LIMIT_TRUSTED_PROXY_HOPS: ${...:-0}` and `.env.example:38` → `0` ⇒ `ClientIpResolver` falls back to the socket address ⇒ in dev the IP is genuine (the port is published directly). ✔
- prod `docker-compose.prod.yml:62` → `1` ⇒ XFF is honoured ⇒ **spoofable if the published port is reachable**.
- k8s `k8s/gateway-service.yaml` does not set the variable ⇒ `application.yml:99` default `1` ⇒ XFF honoured, with the nginx ingress in front (`k8s/ingress.yaml:6`). The nginx ingress controller appends the peer address to the chain (`$proxy_add_x_forwarded_for`), so the **rightmost** entry is the true client — ✔ *by nginx's behaviour*, not by any check in this repo. If the ingress were ever configured to pass the header through, or if a pod is reached directly, the bypass returns.

⇒ **GW-01 (CRITICAL).**

### 5.2 The rate-limit key material is not validated as an IP → bypass + unbounded Redis growth + event-loop blocking

The `XForwardedRemoteAddressResolver` constructs `new InetSocketAddress(xForwardedValues.get(index), 0)` — the **hostname-resolving** constructor, which calls `InetAddress.getAllByName(hostname)` **synchronously**.

`ClientIpResolver.java:25-29` then falls back to the raw hostname when resolution fails:
```java
        if (address.getAddress() != null) return address.getAddress().getHostAddress();
        final String host = address.getHostString();
```
So `X-Forwarded-For: a1.attacker.example` (NXDOMAIN) yields the literal key material `a1.attacker.example`, which `RedisEdgeRateLimiter.java:92-94` embeds verbatim into the Redis key:
```
gateway:edge-rate-limit:SEARCH:a1.attacker.example:tokens
gateway:edge-rate-limit:SEARCH:a1.attacker.example:timestamp
```

Three consequences in one request:
1. **Rate-limit bypass** — every distinct hostname/value gets a brand-new, full bucket. The 60 req/min search budget and the 10 req/min backoffice budget are defeated by rotating the header value.
2. **Unbounded Redis key growth** — 2 new keys per request, never reused, TTL 120 s. A sustained attack creates ~2 keys/second/client of *live* keys; at 10 k req/s that is 20 k live keys and unbounded cumulative churn against the **same** Redis that serves the `inventory`/`order`/`product` caches.
3. **Blocking DNS on the event loop** — `InetAddress.getAllByName` is a blocking resolver call executed synchronously inside `RateLimitFilter.filter()` → `clientIpResolver.resolve(exchange)` (`RateLimitFilter.java:80`), i.e. on the Reactor Netty event-loop thread.

The **admin IP allowlist is not affected** by the hostname case, because `AdminIpAllowlistFilter.isAllowed` guards with a literal-IP regex first (`AdminIpAllowlistFilter.java:106-121`):
```java
    private boolean isAllowed(final String clientIp) {
        // Literal-IP guard: InetAddress.getByName would otherwise perform a
        if (!isLiteralAddress(clientIp)) {
            return false;
        }
        ...
    }

    private static boolean isLiteralAddress(final String value) {
        return value.matches("[0-9a-fA-F.:]+");
    }
```
— but that regex is itself **too permissive** (see **GW-10**: `"cafe"` matches `[0-9a-fA-F.:]+` and `InetAddress.getByName("cafe")` performs a DNS lookup). And the rate-limit path has **no** such guard at all.

⇒ **GW-02 (CRITICAL).**

### 5.3 Is the authenticated user id from the JWT (verified) or from a spoofable header?

**VERIFIED JWT — PROVEN CORRECT.** Chain of evidence:

1. `SecurityConfig.java:41-48`:
   ```java
           return http
                   .csrf(ServerHttpSecurity.CsrfSpec::disable)
                   .cors(Customizer.withDefaults())
                   .authorizeExchange(authorize -> authorize
                           .pathMatchers(publicPaths).permitAll()
                           .anyExchange().authenticated())
                   .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                   .build();
   ```
   Only the OAuth2 **JWT** resource server can produce an authentication; there is no header-based, pre-authenticated or API-key filter.
2. `config/GatewayJwtSecurityConfig.java:57-65` builds the `NimbusReactiveJwtDecoder` with the issuer + timestamp + `azp`/`aud` validator chain (§7) — a token that fails validation never becomes an `Authentication`.
3. `ratelimit/RateLimitKeyResolver.java:36-51`:
   ```java
       @Override
       public Mono<String> resolve(final ServerWebExchange exchange) {
           return exchange.getPrincipal()
                   .filter(this::isUsableAuthentication)
                   .map(Principal::getName)
                   .filter(name -> name != null && !name.isBlank())
                   .map(name -> USER_KEY_PREFIX + name)
                   .switchIfEmpty(Mono.defer(() -> resolveIpKey(exchange)));
       }

       private boolean isUsableAuthentication(final Principal principal) {
           if (principal instanceof AnonymousAuthenticationToken) {
               return false;
           }
           return !(principal instanceof Authentication authentication) || authentication.isAuthenticated();
       }
   ```
4. `exchange.getPrincipal()` is **not** a stub: `spring-web-7.0.9` `DefaultServerWebExchange.java:238-240` returns `Mono.empty()`, **but** Spring Security replaces the exchange with `SecurityContextServerWebExchange`:
   - `spring-security-web-7.1.1` `SecurityContextServerWebExchangeWebFilter.java:38` → `chain.filter(new SecurityContextServerWebExchange(exchange, ReactiveSecurityContextHolder.getContext()))`
   - `SecurityContextServerWebExchange.java` → `getPrincipal()` returns `this.context.mapNotNull(ctx -> (T) ctx.getAuthentication())`
   - `spring-security-config-7.1.1` `ServerHttpSecurity.java:1176-1177` registers that filter unconditionally:
     ```java
             this.addFilterAt(new SecurityContextServerWebExchangeWebFilter(),
                     SecurityWebFiltersOrder.SECURITY_CONTEXT_SERVER_WEB_EXCHANGE);
     ```
   ⇒ `exchange.getPrincipal()` yields the **`JwtAuthenticationToken`** built by the resource server.
5. `JwtAuthenticationToken.getName()` returns the JWT **`sub`** claim — a **signature-verified** claim. The `"user:"` / `"ip:"` prefixes (`RateLimitKeyResolver.java:20-21`) keep the two namespaces disjoint ⇒ **no identity key collision** between a user called `1.2.3.4` and an IP. ✔ **PROVEN CORRECT.**
6. Empirical proof from the repo's own end-to-end test that the security context reaches the Gateway filters: `WebFluxRouteTests.java:91-105` (`userTokenOnBackofficePathIs403Envelope` → HTTP 403 through the real chain) and `AdminRoleGateFilterTest.java:101-107` (`missingSecurityContextGets403`).

**No spoofable header is used for identity anywhere in the gateway.** ✔

**Caveats that remain:**
- The **edge** limiter (L3) keys by **IP only** and never by user (`RedisEdgeRateLimiter.java:92-94`), while the per-route limiter (L1) keys by user. The two layers therefore have **different key spaces** — → **GW-15**.
- The `RateLimitKeyResolver` unit test mocks the exchange (`RateLimitKeyResolverTest.java:80-85`: `when(exchange.getPrincipal()).thenReturn(principal)`), so it verifies the resolver logic but **not** the framework wiring. A regression in the Spring Security filter ordering would silently turn every L1 key into an IP key with **no test failure**. → **GW-21** (test gap).

### 5.4 Summary table — trusted-proxy / spoofing verdict

| Question | Answer | Evidence |
| --- | --- | --- |
| Is `X-Forwarded-For` trusted unconditionally? | **Effectively yes** — the hop count indexes an attacker-supplied header; the peer is never checked | `ClientIpResolver.java:16-30` + SCG `XForwardedRemoteAddressResolver.resolve` |
| Is the client IP trusted only from known proxies? | **No** — there is no trusted-proxy list anywhere | `grep -rn "trustedProxies\|TRUSTED_PROXY" gateway-service/src/` → 0 hits |
| Is `X-Real-IP` honoured? | **No** (good) | `grep -rn "X-Real-IP" gateway-service/` → 0 hits |
| Is the user id from a verified JWT? | **Yes** (good) | `RateLimitKeyResolver.java:36-44`; §5.3 chain |
| Is the user id from a spoofable header? | **No** (good) | no `X-User-*` header is read |
| Is the admin IP allowlist spoofable? | **Yes**, if the gateway port is reachable directly | `docker-compose.prod.yml:46-48` publishes `:8080`; `AdminIpAllowlistFilter.java:84-88` uses the same resolver |

---

## 6. REACTIVE EVENT-LOOP BLOCKING

### 6.1 Blocking DNS on the event loop (L3 client-IP resolution)

`filter/RateLimitFilter.java:80` → `filter/ClientIpResolver.java:22-24` → SCG `XForwardedRemoteAddressResolver.resolve` → `new InetSocketAddress(xForwardedValues.get(index), 0)`.

`InetSocketAddress(String, int)` performs `InetAddress.getAllByName(hostname)` **synchronously** in its constructor. For a literal IPv4/IPv6 this is a fast in-memory parse; for anything else it is a **blocking DNS query**. This runs on the Reactor Netty event-loop thread because `GlobalFilter.filter(...)` is invoked synchronously on the event loop.

Two call sites, both on the event loop:
- `filter/RateLimitFilter.java:80` — `clientIpResolver.resolve(exchange)`
- `filter/AdminIpAllowlistFilter.java:84` — `final String clientIp = clientIpResolver.resolve(exchange);`

⇒ **GW-02**.

### 6.2 Blocking DNS on the event loop (admin allowlist CIDR parsing)

`filter/AdminIpAllowlistFilter.java:106-121`:
```java
    private boolean isAllowed(final String clientIp) {
        // Literal-IP guard: InetAddress.getByName would otherwise perform a
        if (!isLiteralAddress(clientIp)) {
            return false;
        }
        try {
            final InetAddress address = InetAddress.getByName(clientIp);
            return rules.stream().anyMatch(rule -> rule.matches(new InetSocketAddress(address, 0)));
        } catch (final UnknownHostException e) {
            return false;
        }
    }

    private static boolean isLiteralAddress(final String value) {
        return value.matches("[0-9a-fA-F.:]+");
    }
```
The guard rejects anything with a letter outside `a-f`/`A-F` — but `"cafe"`, `"face"`, `"add"`, `"1.2.3.4.5"`, `":::"` all pass it and are **not** valid IPs, so `InetAddress.getByName` falls through to a **blocking DNS lookup**, again on the event loop, once per request. ⇒ **GW-10**.

(Also note the truncated comment on line 107-108 — *"Literal-IP guard: InetAddress.getByName would otherwise perform a"* — the sentence is cut off mid-way, which is why the hole is easy to miss.)

### 6.3 Blocking OIDC discovery + JWKS fetch on the event loop at first decode

`config/LazyReactiveJwtDecoder.java:24-56`:
```java
public final class LazyReactiveJwtDecoder implements ReactiveJwtDecoder {
    ...
    @Override
    public Mono<Jwt> decode(String token) throws JwtException {
        ReactiveJwtDecoder live = delegate.get();
        if (live == null) {
            live = buildOrFallback();
        }
        return live.decode(token);
    }

    private ReactiveJwtDecoder buildOrFallback() {
        try {
            ReactiveJwtDecoder built = factory.get();
            ...
```
and `config/GatewayJwtSecurityConfig.java:60-64`:
```java
        return new LazyReactiveJwtDecoder(() -> {
            NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withIssuerLocation(issuerUri).build();
            decoder.setJwtValidator(chain);
            return decoder;
        });
```
`NimbusReactiveJwtDecoder.withIssuerLocation(...).build()` performs a **blocking** OIDC-discovery HTTP GET (and JWKS fetch) using `RestOperations`. The class's own Javadoc (`LazyReactiveJwtDecoder.java:14-23`) states the problem:
> *"`NimbusReactiveJwtDecoder.withIssuerLocation(...).build()` performs a BLOCKING OIDC discovery fetch while the bean is being created. … This holder defers the discovery fetch to the first decode …"*

Deferring it **does not make it non-blocking** — it moves a blocking network call from the startup thread to the **first authenticated request**, executed on the event loop inside `decode(String)`. With the default `reactor-netty` event-loop model, one slow IdP (or one DNS failure) stalls **every** event-loop thread that touches the decoder. Also, concurrent first requests each call `factory.get()` (the `compareAndSet` at line 47 only decides the winner *after* the blocking fetch has already happened) ⇒ a thundering herd of discovery fetches at boot.

⇒ **GW-09**.

### 6.4 Things that are **NOT** blocking (PROVEN CORRECT)

| Candidate | Verdict | Evidence |
| --- | --- | --- |
| Blocking Redis client | **No** — `ReactiveStringRedisTemplate` (Lettuce reactive, non-blocking) | `config/EdgeFiltersConfiguration.java:49-52`; `ratelimit/RedisEdgeRateLimiter.java:79,95` |
| JDBC / JPA in the gateway | **No** — `spring-boot-starter-data-jpa` is explicitly **excluded** from `common-core` for the gateway | `gateway-service/pom.xml:25-36` |
| `Thread.sleep` | **No** in main code (only in a test) | `grep -rn "Thread.sleep" gateway-service/src/main/` → 0; `RedisEdgeRateLimiterTest.java:115` is test code |
| `synchronized` / locks | **No** | `grep -rn "synchronized\|ReentrantLock" gateway-service/src/main/` → 0 |
| `block()` / `.block()` | **No** in main code | `grep -rn "\.block()" gateway-service/src/main/` → 0 |
| `InetAddress.getByName` in `AdminIpAllowlistFilter.parseCidr` (`:130`) | Runs at **bean construction** (startup thread), not on the event loop — acceptable, and it fails fast on an invalid CIDR (good) | `filter/AdminIpAllowlistFilter.java:66,123-137` |
| `objectMapper.writeValueAsBytes` in `GatewayErrorResponseWriter` (`:69`) | CPU-bound, sub-millisecond, on the event loop — acceptable | `filter/GatewayErrorResponseWriter.java:66-73` |

---

## 7. AUTH / JWT VALIDATION IN THE GATEWAY

### 7.1 JWKS source

`config/GatewayJwtSecurityConfig.java:57-65`:
```java
    @Bean
    public ReactiveJwtDecoder reactiveJwtDecoder() {
        OAuth2TokenValidator<Jwt> chain = buildValidatorChain();
        return new LazyReactiveJwtDecoder(() -> {
            NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withIssuerLocation(issuerUri).build();
            decoder.setJwtValidator(chain);
            return decoder;
        });
    }
```
- **JWKS source:** OIDC discovery of the issuer ⇒ `<issuer>/.well-known/openid-configuration` ⇒ `jwks_uri`.
- **Issuer:** `${spring.security.oauth2.resourceserver.jwt.issuer-uri}` (`application.yml:30`, default `http://keycloak:8080/realms/ecommerce`). The `GatewayJwtProperties.keycloakIssuerUri` (`config/GatewayProperties.java:14-15`) is a **separate, unused-by-the-decoder** property — the decoder uses the `spring.security...` value (`GatewayJwtSecurityConfig.java:51-52`). Two sources of the same fact that can drift; the `gateway.keycloak-issuer-uri` value is only asserted to be non-blank. → **GW-22**.
- **JWKS caching / rotation:** handled by `NimbusReactiveJwtDecoder` (Nimbus `RemoteJWKSet` caches and re-fetches on an unknown `kid`). Not configured/tuned here.
- **Bootstrap:** discovery is deferred to the first decode → the gateway **starts** even if Keycloak is down (good for rolling restarts) but the first request then pays the blocking cost (§6.3).

### 7.2 Validator chain

`config/GatewayJwtSecurityConfig.java:67-97`:
```java
    private OAuth2TokenValidator<Jwt> buildValidatorChain() {
        List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
        validators.add(new JwtIssuerValidator(issuerUri));
        validators.add(new JwtTimestampValidator());

        if (!properties.expectedAudiences().isEmpty()) {
            validators.add(new JwtClaimValidator<Object>("aud", aud -> { ... }));
            log.info("Gateway JWT audience validation enabled for {}", properties.expectedAudiences());
        }

        if (!properties.expectedClientIds().isEmpty()) {
            validators.add(new JwtClaimValidator<Object>("azp", azp -> { ... }));
            log.info("Gateway JWT client (azp) validation enabled for {}", properties.expectedClientIds());
        } else {
            log.warn("Gateway JWT client validation is DISABLED - a token minted for any client "
                    + "in the realm is accepted at the edge. Set gateway.security.expected-client-ids.");
        }

        return new DelegatingOAuth2TokenValidator<>(validators);
    }
```
| Check | Present? | Detail |
| --- | --- | --- |
| **Issuer** | **YES, always** | `JwtIssuerValidator(issuerUri)` — exact string match on `iss` |
| **Audience (`aud`)** | Wired, **inactive by default** | `properties.expectedAudiences()` is empty ⇒ the validator is not added. `application.yml` does not set `GATEWAY_EXPECTED_AUDIENCES`. The class Javadoc (`GatewayJwtProperties.java:10-15`) explains why: the provisioned Keycloak 26 realm emits **no `aud` claim** (no audience mapper / client scopes), so `azp` is used instead. **This is a documented, deliberate gap — not a defect**, but it *is* a residual risk: any realm change that starts emitting `aud` without configuring `expected-audiences` leaves the audience check off. |
| **Client (`azp`)** | **YES, enabled by default** | `application.yml:56` → `expected-client-ids: ${GATEWAY_EXPECTED_CLIENT_IDS:ecommerce-client,order-service,rating-service,search-service,product-service,media-service}`. A token minted for any *other* client in the realm is rejected at the edge. The Javadoc (`GatewayJwtSecurityConfig.java:19-39`) documents that this check was **missing** before this configuration existed (Boot's reactive auto-config is issuer + timestamp only). ✔ **PROVEN CORRECT — a real fix, verified.** |
| **Clock skew** | **YES** | `JwtTimestampValidator()` default = **60 s** (`JwtTimestampValidator.DEFAULT_CLOCK_SKEW = Duration.ofSeconds(60)`). `exp` and `nbf` are validated; there is no configurable skew in this repo. |
| **Algorithm pinning** | **Implicit only** | No explicit `alg` allow-list. `NimbusReactiveJwtDecoder` accepts the RSA algorithms advertised by the JWKS (`RS256`/`RS384`/`RS512`/`PS256`…) and rejects `none` and HMAC (the JWKS is asymmetric-only, so `HS256` signed with the RSA public key is not accepted). ⇒ **acceptable, but not explicitly pinned** → the operator cannot restrict the gateway to `RS256` alone. → **GW-23** (LOW). |
| **`typ` / `cty` header checks** | **No** | — |
| **Revocation / introspection** | **No** | JWTs are not checked against an introspection or denylist. Access-token TTL is therefore the only revocation window. |

### 7.3 Failure mode: 401 vs 403

| Situation | Status | Body | Evidence |
| --- | --- | --- | --- |
| No `Authorization` header, non-public path | **401** | Spring Security default (empty body) | `SecurityConfig.java:44-46` (`.anyExchange().authenticated()`); `WebFluxRouteTests.java:84-89` asserts `isUnauthorized()` |
| Malformed / bad-signature / expired / wrong-issuer / wrong-`azp` token | **401** | Spring Security default | `WebFluxRouteTests.java:117-123` (`invalidTokenIs401`) |
| Valid token, **not** ADMIN, on a backoffice path | **403** | fleet `ApiResponse` envelope `ERR-0403-A` | `filter/AdminRoleGateFilter.java:77-79`; `WebFluxRouteTests.java:91-105` |
| Valid ADMIN token, IP not in the allowlist | **403** | fleet envelope `ERR-0403-A` | `filter/AdminIpAllowlistFilter.java:88`; `WebFluxIpAllowlistTests.java:84-99` |
| Path is percent-encoded / carries a matrix variable | **400** | fleet envelope `ERR-0400` | `filter/RequestPathGuard.java`; `RateLimitFilter.java:71-74, 97-100` |

The `AdminRoleGateFilter` Javadoc claim *"401 (no/invalid token) is already handled upstream by the resource-server security chain — this filter only ever sees authenticated requests"* (`AdminRoleGateFilter.java:34-35`) is **CORRECT**: the `WebFilter` chain (which contains the resource-server filters) runs before the `DispatcherHandler` that hosts the Gateway filters. ✔ **PROVEN CORRECT.**

**Note — inconsistent error bodies:** the 401/403-from-Spring-Security responses carry **no** `ApiResponse` envelope, while every gateway-authored 4xx does. Clients must handle two error shapes. → **GW-07**.

### 7.4 Identity propagation to downstream services

- The gateway performs **no header injection** of the identity and **no re-signing**. Spring Cloud Gateway's `NettyRoutingFilter` forwards the original request headers (including `Authorization: Bearer <JWT>`) unchanged to `http://<service>:<port>` (`config/RoutesConfig.java:44`, `routing/ApiPaths.java:26-28`).
- There is **no** `X-User-Id` / `X-Email` / `X-Roles` header written anywhere in `gateway-service` (`grep -rn "setHeader\|mutate().request\|header(" gateway-service/src/main/` → only `GatewayErrorResponseWriter.java:80` which *reads* `X-Correlation-Id`).
- ⇒ **PROVEN CORRECT and secure: downstream services re-validate the same JWT.** There is no spoofable identity header to trust downstream. ✔
- Consequence to note: each backend service performs its own JWKS fetch and validation, and the gateway's role gate (`AdminRoleGateFilter`) is a **second** authorisation layer in front of a service-level one — the services must still enforce the ADMIN role themselves. The gateway gate is network-edge only.
- The gateway does **not** strip the `Authorization` header before proxying — correct, because the downstream needs it.

### 7.5 The `X-Correlation-Id` passthrough

`filter/GatewayErrorResponseWriter.java:39,79-81` reads the caller's `X-Correlation-Id` and echoes it into the error envelope's `traceId` field. The header is **caller-supplied and unvalidated** — it is written into a JSON body (properly escaped, `GatewayErrorResponseWriter.java:90-115`) so there is no injection, but a caller can put arbitrary content into the `traceId` of an error response, and (in a log-correlation scheme) could collide with a genuine trace id. → **GW-24** (INFO/LOW).

---

## 8. CONCURRENCY

### 8.1 Mutable state inventory in `gateway-service` (main)

| Class | Field | Mutability | Thread-safety | Verdict |
| --- | --- | --- | --- | --- |
| `config/LazyReactiveJwtDecoder.java:28-29` | `private final Supplier<ReactiveJwtDecoder> factory;` / `private final AtomicReference<ReactiveJwtDecoder> delegate` | `delegate` is mutated | **AtomicReference + `compareAndSet`** (`:47`) | ✔ safe. But the *blocking* fetch inside the CAS is the GW-09 problem. |
| `config/RouteTargetProperties.java:17-21` | `Map<String,String> overrides` | `Map.copyOf(...)` in the compact constructor | **Immutable** | ✔ safe |
| `config/GatewayProperties.java:12-22` | record components | final | Immutable | ✔ safe |
| `config/GatewayJwtProperties.java:28-31` | record components | final | Immutable | ✔ safe |
| `filter/EdgeRateLimitProperties.java:23-27` | record components | final | Immutable | ✔ safe |
| `filter/AdminIpAllowlistProperties.java:18-22` | `cidrs` | `List.copyOf(...)` | Immutable | ✔ safe |
| `ratelimit/RateLimitProperties.java:15-21`, `ratelimit/GlobalRateLimitProperties.java:14-19` | record components | final | Immutable | ✔ safe |
| `filter/AdminIpAllowlistFilter.java:58` | `private final List<IpSubnetFilterRule> rules` | built once in the constructor (`:66`) from an immutable list | Immutable, thread-safe `IpSubnetFilterRule` | ✔ safe |
| `filter/RateLimitFilter.java:52` | `private final List<String> backofficePrefixes` | `ServiceRoute.backofficeRoutes()...toList()` (`:62`) | Immutable | ✔ safe |
| `filter/AdminRoleGateFilter.java:46` | `private final List<String> backofficePrefixes` | `toList()` (`:50`) | Immutable | ✔ safe |
| `filter/GatewayErrorResponseWriter.java:34-37,41` | `private static final Map<ErrorCode,String> EN_MESSAGES` / `private final ObjectMapper objectMapper` | `Map.of(...)` static, final | Immutable; `ObjectMapper` is thread-safe | ✔ safe |
| `filter/ClientIpResolver.java:12` | `private final Optional<XForwardedRemoteAddressResolver> forwardedAddressResolver` | final, never reassigned | `XForwardedRemoteAddressResolver` is stateless | ✔ safe |
| `ratelimit/RedisEdgeRateLimiter.java:79-80` | `private final ReactiveStringRedisTemplate redis; private final EdgeRateLimitProperties properties;` | final | `ReactiveStringRedisTemplate` is thread-safe | ✔ safe |
| `ratelimit/RedisEdgeRateLimiter.java:37,45-77` | `KEY_PREFIX`, `TOKEN_BUCKET_SCRIPT`, `TOKEN_BUCKET` | `static final` | `DefaultRedisScript` is immutable after construction | ✔ safe |
| `ratelimit/GlobalRateLimitFilter.java:23-26` | final collaborators | final | — | ✔ safe |
| `filter/ClientIpResolver.java:11` | `UNKNOWN_IP` | `static final` | — | ✔ safe |

**⇒ There is NO mutable shared state and NO in-JVM token-bucket map in `gateway-service`.** The "unbounded growth / memory leak / key collision" concern the brief raises for an in-memory bucket map **does not apply** — the bucket map does not exist. The equivalent risk is **unbounded Redis key cardinality** instead (**GW-02**, **GW-14**).

### 8.2 `@Scheduled` / background jobs

`grep -rn "@Scheduled\|@EnableScheduling\|TaskScheduler\|Schedulers\." gateway-service/src/main/` → **0 hits**. No background tasks, no eviction sweeper, no cache warmer.

### 8.3 Non-thread-safe collaborators inherited from the framework

| Component | Issue | Verdict |
| --- | --- | --- |
| `AbstractStatefulConfigurable.config` | a **plain `HashMap`** (`spring-cloud-gateway-server-webflux-5.0.0`, `support/AbstractStatefulConfigurable.java`), written by `AbstractRateLimiter.onApplicationEvent(FilterArgsEvent)` (`filter/ratelimit/AbstractRateLimiter.java:50-62` → `getConfig().put(routeId, routeConfig)`) on the **event-publication thread**, while `loadConfiguration(routeId)` reads it on the **request thread** | **Not currently reachable**: the repo defines all 29 routes programmatically (`RoutesConfig.java:34`), so no `FilterArgsEvent` is ever published and the map stays empty. It becomes a real data race only if YAML/actuator route definitions are introduced. → **GW-25** (LOW, latent) |
| `RedisRateLimiter.initialized` | `AtomicBoolean` | ✔ safe |
| `RedisRateLimiter.defaultConfig` | mutable via `setRequestedTokens` in the constructor, then read-only | ✔ safe |

### 8.4 Concurrency of the Redis mutation itself

| Layer | Pattern | Atomic? |
| --- | --- | --- |
| L1, L2 (SCG `RedisRateLimiter`) | one `EVAL` of `request_rate_limiter.lua` | **YES** ✔ |
| L3 (`RedisEdgeRateLimiter`) | one `EVAL` of `TOKEN_BUCKET_SCRIPT` | **YES** ✔ |
| All three key/timestamp pairs | SCG uses `{routeId.key}` hash tags ✔; L3 uses **no** hash tag ✘ | → **GW-12** |

**⇒ No GET-then-SET race exists anywhere.** The brief's "separate GET then SET = race?" concern is answered **negatively** — all three layers mutate Redis inside a single Lua script.

### 8.5 Key-collision analysis

| Key space | Construction | Collision risk |
| --- | --- | --- |
| L1 user key | `"user:" + jwt.sub` | **None** — `sub` is a Keycloak UUID, unique per principal within the realm, and the issuer is pinned. |
| L1 IP key | `"ip:" + host` | IPv4 vs IPv6 text forms of the same address are **different** keys → trivial bypass by switching representation (`::ffff:203.0.113.7` vs `203.0.113.7`). → **GW-15** |
| L1 user vs L1 IP | `"user:"` vs `"ip:"` prefixes | **None** ✔ **PROVEN CORRECT** |
| L2 global key | the constant `"system"` | **N/A** (one bucket by design) |
| L3 edge key | `"<SCOPE>:<clientIp>"` | Scope is separated ✔; but the IP material is **not validated** (hostname, port-suffixed, IPv4-mapped-IPv6 all accepted) → *distinct* key per attacker-supplied string → **bypass + unbounded growth** (**GW-02**). A genuine collision (two different clients sharing one key) happens when they are behind the same NAT — which is the **intended** per-IP semantics. |
| L1 vs L2 vs L3 namespaces | `request_rate_limiter.{…}` vs `gateway:edge-rate-limit:…` | **Disjoint** ✔ |

---

## 9. MANDATORY SCENARIO ANALYSIS

### S1 — Cross-replica concurrent requests against a global limit

**Setup:** 3 k8s replicas (`k8s/gateway-service.yaml:10`), one Redis, L2 configured 2000 tok/s / 4000 burst (`application.yml:102-103`).

**Actual:** all replicas call `rateLimiter.isAllowed("gateway-system", "system")` (`GlobalRateLimitFilter.java:50`) → the same Redis key `request_rate_limiter.{gateway-system.system}.tokens` → a single atomic Lua `EVAL`. 10 000 concurrent requests across 3 replicas consume **one** bucket of 4000 → 4000 allowed, 6000 rejected with 429. **The budget is genuinely fleet-wide.** ✔ **PROVEN CORRECT.**

**But:**
- The bucket is **not partitioned by client** ⇒ a single abusive client can consume all 4000 tokens and starve every other tenant (no fairness). → **GW-04**.
- The fleet-wide ceiling is a constant 2000 rps **independent of the replica count** — scaling from 2 to 10 pods does **not** raise the ceiling, so the HPA scales *cost* without scaling *capacity*, and the extra replicas only add latency. → **GW-04**.
- The L3 edge budget is shared across replicas too, **except** that its refill is computed from each replica's own clock (§4.2) ⇒ the effective edge limit drifts with clock skew. → **GW-05**.

### S2 — Burst at the boundary

**L3, backoffice (10 req/min, capacity 10, refill 0.1667 tok/s):**
- requests 1..10 in the same wall-clock second ⇒ `filled` = 10 → 10 allowed, `remaining` 0..9 ✔ (`RedisEdgeRateLimiterTest.java:66-78` proves the bucket is empty after the burst).
- request 11 in the same second ⇒ `filled` = 0 < 1 ⇒ `allowed = 0` ⇒ **429** with `X-RateLimit-Remaining: 0` and the fleet envelope (`RateLimitFilter.java:92-95`). ✔
- refill granularity is **whole seconds** (`now` and `last` are epoch seconds) ⇒ at 10 rpm the bucket refills in 1-token-per-6-seconds steps aligned to second boundaries. The repo acknowledges this (`RedisEdgeRateLimiterTest.java:100-107`: *"a request that crosses a second boundary gets a token back"*). Minor precision loss, not a defect.
- **TTL:** 120 s. The `:timestamp` key is refreshed on **every** call, including **rejected** ones (`RedisEdgeRateLimiter.java:66-70`) ⇒ a persistent abuser keeps the bucket alive indefinitely and the key never ages out. → **GW-16**.

**L1 (100 tok/s, burst 200):** requests 1..200 instantly allowed; 201st denied 429 **with an empty body** (no fleet envelope) — an inconsistent contract vs L2/L3. → **GW-07**.

**L2 (2000 tok/s, burst 4000):** 4000 instantly allowed fleet-wide; 4001st denied 429 with the envelope.

**Boundary interaction:** for a backoffice request, **L2 is consumed first**, then **L3**, then **L1**. A request rejected by L3 has *already* spent L2 and (if it gets that far) L1 tokens ⇒ **rejected traffic still burns the fleet-wide budget**, which makes the fleet cap easier to exhaust. → **GW-06**.

### S3 — Unknown user (no JWT)

- **Public paths** (`application.yml:62-86`: `/api/v1/auth/**`, `/api/v1/search/**`, `/api/v1/products/**`, webhooks, actuator, …) are `permitAll` ⇒ the request reaches the gateway filters with **no** authentication.
  - L1 key ⇒ `"ip:<clientIp>"` (`RateLimitKeyResolver.java:53-62`) ⇒ **100 rps per IP per route**. On a NAT/CGNAT egress this is one bucket for thousands of users. → **GW-15**.
  - L3 (search scope) ⇒ **60 req/min per IP** — a shared-NAT office gets 1 request/second for the whole office. → **GW-15**.
  - The IP itself is the attacker-controlled XFF value when `trusted-proxy-hops > 0` ⇒ **forge a new XFF per request and every per-IP limit is defeated** (§S5 / **GW-01**, **GW-02**). This is the single most exploitable path in the module.
- **Non-public paths** without a token ⇒ the Spring Security chain answers **401 before the gateway filters run** ⇒ **no rate-limit token is consumed at all**. ⇒ A flood of unauthenticated requests to e.g. `/api/v1/orders` is **not metered by any gateway limiter** (it is metered only by the upstream LB/ingress). ⇒ the gateway's own JWT-validation path is unprotected by the gateway's own limiters. → **GW-26** (MEDIUM).
- **Empty key** (no principal *and* no remote address) ⇒ `setDenyEmptyKey(true)` + `setEmptyKeyStatus("429")` (`RoutesConfig.java:41-42`) ⇒ a bare **429** with no body. ✔ correct fail-closed behaviour for L1; the body shape is inconsistent → **GW-07**.

### S4 — Redis outage

| Layer | Behaviour | Evidence |
| --- | --- | --- |
| L1 (per-route) | **FAIL-OPEN** — every request allowed; `X-RateLimit-Remaining: -1` is emitted (the sentinel leaks to the client) | SCG `RedisRateLimiter.isAllowed` `onErrorResume(... Arrays.asList(1L, -1L))` |
| L2 (global) | **FAIL-OPEN** — the 2000 rps fleet cap **disappears entirely** | same |
| L3 (edge) | **FAIL-CLOSED** — the error propagates ⇒ **HTTP 500** for every `/api/v1/backoffice/**` and `/api/v1/search/**` request | `RedisEdgeRateLimiter.java:95-108` (error not swallowed) |

Consequences:
1. The two SCG layers silently vanish while the custom layer hard-fails ⇒ **the fleet is half-limited and half-broken**. The in-code comment at `RedisEdgeRateLimiter.java:102-108` asserting *"exactly like the two Redis-backed tiers above do"* is **false**. → **GW-03**.
2. A Redis blip takes the **entire search + backoffice surface** down with 500s, while everything else sails through unlimited. Since the Redis is the **same instance** the `inventory`/`order`/`product` caches use, a cache-warming script or a `FLUSHALL` in a shared environment (as the tests do — `RedisEdgeRateLimiterTest.java:47-52`, `RateLimitFilterTest.java:58-63`) takes the edge down. → **GW-03**.
3. `docker-compose.yml:72` runs `redis-server --requirepass "${REDIS_PASSWORD}" --appendonly yes` with **no `--maxmemory` and no `--maxmemory-policy`** ⇒ under memory pressure Redis can start failing writes → the same outage path. → **GW-14**.
4. The gateway is the only component that hard-depends on Redis: `config/EdgeFiltersConfiguration.java:49-52` requires a `ReactiveStringRedisTemplate` bean at startup ⇒ if Redis is down at boot, the **gateway fails to start** (connection-factory creation may succeed; the failure surfaces on the first request). `docker-compose.yml:307-309` declares `depends_on: redis: condition: service_healthy` ✔.

### S5 — Identity key collision

| Case | Result |
| --- | --- |
| Two users with the same `sub` | **Impossible** within one realm (Keycloak UUID), and the issuer is pinned by `JwtIssuerValidator` ⇒ no cross-realm collision. ✔ **PROVEN CORRECT** |
| A user named `1.2.3.4` vs the IP `1.2.3.4` | **Impossible** — the `"user:"` / `"ip:"` prefixes (`RateLimitKeyResolver.java:20-21`) keep the namespaces disjoint. ✔ **PROVEN CORRECT** |
| An authenticated user and an anonymous client on the same IP | **Two different buckets** (user-keyed vs IP-keyed) ⇒ the anonymous traffic is not charged to the user's budget. Intentional, but it means **an authenticated attacker gets a *second*, independent budget** on the same IP: e.g. `user:X` (100 rps) **plus** `ip:Y` (100 rps) for the same physical client, and **plus** the L3 edge bucket. ⇒ **the effective limit for a request that carries a token is up to 3× the configured one**. → **GW-15** |
| An IPv4-mapped-IPv6 client (`::ffff:203.0.113.7`) vs `203.0.113.7` | **Two buckets** ⇒ trivial 2× bypass of every per-IP limit. → **GW-15** |
| The L3 key material is an arbitrary attacker string | **Every value is a distinct bucket** ⇒ unlimited bypass + unbounded Redis growth. → **GW-02** |
| The L2 key | A single constant ⇒ every client collides *by design* (the starvation problem, **GW-04**) |

---

## 10. DESIGN-PATTERN EVALUATION

| Pattern | Genuinely present? | Class sketch / evidence | Justified? |
| --- | --- | --- | --- |
| **Chain of Responsibility** | **YES, strongly** — the whole edge is an ordered `GlobalFilter` chain with named order constants | `filter/FilterOrder.java:15-19` (`ADMIN_IP_ALLOWLIST = HIGHEST_PRECEDENCE`, `RATE_LIMIT = +10`, `ADMIN_ROLE_GATE = +20`); the three filters implement `GlobalFilter, Ordered` and call `chain.filter(exchange)` to delegate. `filter/FilterOrderTest.java:17-21` pins the relative order. | **Yes.** The order is *declared* explicitly and test-enforced — but the contract is **broken for the global limiter**, which reuses `HIGHEST_PRECEDENCE` and ties with the allowlist (**GW-08**). |
| **Filter Chain (WebFlux)** | **YES** — `SecurityWebFilterChain` (Spring Security) + SCG's `FilteringWebHandler` | `config/SecurityConfig.java:37-49` | Yes — standard. |
| **Decorator (rate-limit wrapper)** | **PARTIAL** — the repo owns the *interface* `EdgeRateLimiter` (`ratelimit/EdgeRateLimiter.java:14-25`) and the *implementation* `RedisEdgeRateLimiter`, but there is **no** decorator in the chain | Sketch that would apply: `class FailClosedEdgeRateLimiter implements EdgeRateLimiter { private final EdgeRateLimiter delegate; ... }` — the seam already exists but is unused; **the decorator needed to unify the fail-open/fail-closed policy is missing** (**GW-03**) | **The seam is justified; the missing decorator is the defect.** |
| **Proxy** | **YES** — `LazyReactiveJwtDecoder` is a textbook lazy proxy over `ReactiveJwtDecoder` | `config/LazyReactiveJwtDecoder.java:24-56` | **Intent yes, execution no** — the proxy moves the blocking fetch instead of eliminating it (**GW-09**). |
| **Strategy / polymorphic key resolution** | **YES** — `EdgeRateLimiter` (interface) + `RedisEdgeRateLimiter` (impl); `KeyResolver` (framework interface) + `RateLimitKeyResolver` (impl) | `ratelimit/EdgeRateLimiter.java:24`; `ratelimit/RateLimitKeyResolver.java:18-19` | Yes — this is what made the "in-process → Redis" swap (F-034) a one-line change in `EdgeFiltersConfiguration.java:48-52`. **Good design.** |
| **Cache-Aside** | **YES, but in the *services*, not the gateway** | `inventory-service/src/main/java/com/shop/inventoryservice/config/CacheConfig.java`, `order-service/.../CacheConfig.java`, `product-service/.../CacheConfig.java` (from the repo-wide Redis grep) | The gateway holds **no** cache — it is a pass-through. Correct: caching at the edge would fight the rate limiter's freshness. |
| **Bulkhead** | **NO** | — | **MISSING.** There is no per-upstream concurrency limit, no connection-pool cap, no bulkhead between the gateway and Redis, and no bulkhead between the gateway and the backends. The only "bulkhead-like" artefact is the **single** global bucket (**GW-04**) — which is a *throttle*, not a bulkhead: it limits rate but not *concurrency*, and it partitions nothing. A slow backend therefore consumes an unbounded number of in-flight gateway requests. → **GW-27** (MEDIUM). |
| **Circuit Breaker** | **NO** | The framework's `GatewayResilience4JCircuitBreakerAutoConfiguration` is present in the SCG jar but is **not** activated (no `spring.cloud.gateway.filter.circuit-breaker.enabled`, no `resilience4j` dependency in `gateway-service/pom.xml`). | **MISSING.** With no circuit breaker, a hung backend (or a hung Redis) holds gateway resources until the Netty/connector timeouts expire. → **GW-27**. |
| **Bucket4j** | **NO** — the framework's `Bucket4jRateLimiter` is **not** used and **bucket4j is not a dependency** (`grep` → 0 hits in every POM) | The bucket4j references in `RedisEdgeRateLimiter.java:16-28`, `RateLimitFilter.java:20-25`, `EdgeRateLimiter.java:5-12` describe a **removed** implementation | The comments are **accurate history** but they are the origin of the "two token-bucket layers" belief in the brief. |
| **Resilience4j** | **NO** in the gateway (used in `utils/common-spring` for the servlet services) | `pom.xml:153-160`, `utils/common-spring/pom.xml:95-96` | N/A for the gateway. |
| **Fail-Fast / Configuration Validation** | **YES — genuinely good work** | `config/GatewaySecurityPostureValidator.java:46-127`: refuses to boot on a wildcard CORS pattern with `allowCredentials(true)` (`:60-71`), on `trusted-proxy-hops == 0` combined with a non-`none` forwarded-header strategy (`:114-127`), and on an empty mandatory admin allowlist (`:96-112`) | **Yes** — this is exactly the right shape for the class. But its coverage has the hole in **GW-20** (it never checks the *peer* address). |

---

## 11. DEFECT LIST

Severity: **CRITICAL** = directly exploitable bypass of a security control in a reachable deployment; **HIGH** = breaks a stated invariant or a stated guarantee in the code comments; **MEDIUM** = production-impacting correctness/availability/consistency gap; **LOW** = hardening / maintainability; **INFO** = observation.

---

### GW-01 — CRITICAL — The trusted-proxy hop count is an index into an attacker-controlled header; the transport peer is never verified

**Evidence**
- `gateway-service/src/main/java/com/shop/gateway/filter/ClientIpResolver.java:16-30`
  ```java
        this.forwardedAddressResolver = trustedProxyHops == 0 ? Optional.empty()
                : Optional.of(XForwardedRemoteAddressResolver.maxTrustedIndex(trustedProxyHops));
  ...
        final InetSocketAddress address = forwardedAddressResolver.map(r -> r.resolve(exchange))
                .orElseGet(() -> exchange.getRequest().getRemoteAddress());
  ```
- Framework (`spring-cloud-gateway-server-webflux-5.0.0-sources.jar`, `support/ipresolver/XForwardedRemoteAddressResolver.java`):
  ```java
        if (!xForwardedValues.isEmpty()) {
            int index = Math.max(0, xForwardedValues.size() - maxTrustedIndex);
            return new InetSocketAddress(xForwardedValues.get(index), 0);
        }
  ```
- `gateway-service/src/main/resources/application.yml:99` → `trusted-proxy-hops: ${GATEWAY_RATE_LIMIT_TRUSTED_PROXY_HOPS:1}` (shipped default **1**).
- `docker-compose.prod.yml:62` → `GATEWAY_RATE_LIMIT_TRUSTED_PROXY_HOPS: ${...:-1}`; `docker-compose.prod.yml:46-48` publishes the gateway port: `ports: !override - "${GATEWAY_INGRESS_PORT:-8080}:8080"`.
- `docker-compose.prod.yml:24-28` states the requirement in a **comment only**.
- `k8s/gateway-service.yaml` never sets the variable ⇒ default 1, with the nginx ingress in front (`k8s/ingress.yaml:6`).
- No trusted-proxy CIDR list exists: `grep -rn "trustedProxies\|TRUSTED_PROXY\|proxyAllowlist" gateway-service/src/` → 0 hits.

**Actual behaviour.** With `trusted-proxy-hops = 1`, the client IP is the **rightmost** entry of the client's own `X-Forwarded-For` header, selected **without consulting the socket peer**. Any client that can open a TCP connection to `:8080` chooses its own rate-limit bucket and its own allowlist verdict.

**Violated invariant.** "The identity used for authorisation (admin IP allowlist) and for rate limiting is the real client, not a caller-controlled string." The `GatewaySecurityPostureValidator` Javadoc (`config/GatewaySecurityPostureValidator.java:74-81`) asserts exactly this invariant — the validator does not enforce it.

**Root cause.** `trusted-proxy-hops` models *"how many entries to trust from the right of the XFF chain"*, **not** *"which peers may set the header"*. The framework resolver has no peer allow-list and the repo added none.

**Proposed fix.**
1. Add `gateway.trusted-proxy-cidrs` (list of CIDRs of the LB/ingress). In `ClientIpResolver`, resolve the socket peer first; **only** if the peer is inside a trusted CIDR may the XFF chain be consulted (and then with `hops = number of trusted proxies`). Otherwise use the socket address and *strip* the incoming `X-Forwarded-For` from the request before it is forwarded downstream.
2. Make `GatewaySecurityPostureValidator.validateClientIpTrust()` fail fast when `trusted-proxy-hops > 0` and `trusted-proxy-cidrs` is empty.
3. Bind the gateway port to `127.0.0.1` only, or remove the published port and require an in-cluster LB.
4. Correct the wrong prose in `docker-compose.prod.yml:25-26` and `:61` ("FIRST entry" / "index 1" → the rightmost entry).

**Reproduction test.** `gateway-service/src/test/java/com/shop/gateway/filter/ClientIpResolverSpoofTest.java` (new):
```java
// peer = 203.0.113.9 (NOT a trusted proxy), header X-Forwarded-For: 10.42.0.7
assertThat(new ClientIpResolver(1, List.of("192.0.0.0/24")).resolve(exchange))
        .isEqualTo("203.0.113.9");
```

**Acceptance criterion.** A request whose socket peer is not in `trusted-proxy-cidrs` is metered and allow-listed under its **socket** address, and its `X-Forwarded-For` header cannot influence either verdict — proven by an integration test through `WebTestClient` that asserts 403 (not 200) for a forged `X-Forwarded-For` matching the allowlist.

---

### GW-02 — CRITICAL — A non-literal `X-Forwarded-For` entry becomes the rate-limit key: per-request bucket bypass, unbounded Redis key growth, and blocking DNS on the event loop

**Evidence**
- `gateway-service/src/main/java/com/shop/gateway/filter/ClientIpResolver.java:25-29`
  ```java
        if (address.getAddress() != null) return address.getAddress().getHostAddress();
        final String host = address.getHostString();
  ```
- Framework: `new InetSocketAddress(xForwardedValues.get(index), 0)` → the **hostname-resolving** constructor → blocking `InetAddress.getAllByName`.
- `gateway-service/src/main/java/com/shop/gateway/ratelimit/RedisEdgeRateLimiter.java:92-94`
  ```java
        final List<String> keys = List.of(
                KEY_PREFIX + scope + ":" + clientIp + ":tokens",
                KEY_PREFIX + scope + ":" + clientIp + ":timestamp");
  ```
- `gateway-service/src/main/java/com/shop/gateway/filter/RateLimitFilter.java:80` — `clientIpResolver.resolve(exchange)` runs **synchronously inside the reactive filter**, i.e. on the Reactor Netty event loop.
- No IP validation on the rate-limit path: the literal-IP guard exists **only** in the allowlist filter (`filter/AdminIpAllowlistFilter.java:108-110, 119-121`).

**Actual behaviour.** `X-Forwarded-For: <any-non-resolvable-hostname>` ⇒
1. a **blocking DNS lookup** on the event loop per request;
2. the raw hostname becomes the bucket identity ⇒ **a fresh, full bucket for every distinct value** ⇒ the 10 req/min backoffice and 60 req/min search budgets are **completely defeated**;
3. **2 new Redis keys per request** (`…:tokens`, `…:timestamp`, TTL 120 s) against the Redis that also serves the `inventory`/`order`/`product` caches.

**Violated invariant.** "A rate-limit key is derived from a bounded, validated identity" and "the rate limiter bounds the request rate of a client". Also "no blocking I/O on the event loop".

**Root cause.** The resolved "client IP" is used as key material without being validated as a literal IP, and the framework resolver performs DNS resolution as a side effect.

**Proposed fix.** In `ClientIpResolver`, parse the selected XFF entry **without DNS**: accept only a literal IPv4/IPv6 (manual parser or `InetAddresses.forString` from Guava / `InetAddress.getByName` guarded by a *strict* literal check that rejects names). If the entry is not a literal IP, **ignore the header entirely** and use the socket peer. Never let an unvalidated string reach the Redis key.

**Reproduction test.** New `gateway-service/src/test/java/com/shop/gateway/filter/ClientIpResolverTest.java` case:
```java
    @Test
    void nonLiteralForwardedEntryIsRejectedAndNeverBecomesAKey() {
        assertThat(resolver.resolve(exchangeWithXff("attacker.example"))).isEqualTo("127.0.0.1");
    }
```
plus a `RedisEdgeRateLimiterTest` case asserting that 100 requests with 100 distinct XFF values consume **one** bucket.

**Acceptance criterion.** After the fix, `X-Forwarded-For` values that are not literal IPs never influence the resolved client IP, never appear in a Redis key, and never trigger a DNS lookup; the number of distinct Redis keys created by a burst of N requests is bounded by the number of distinct **validated** client IPs.

---

### GW-03 — HIGH — Contradictory Redis-outage semantics across the three limiters, and the in-code justification is factually wrong

**Evidence**
- `gateway-service/src/main/java/com/shop/gateway/ratelimit/RedisEdgeRateLimiter.java:102-108`
  ```java
                // A Redis outage is NOT reported as "too many requests": the
                // error propagates, so the gateway answers with a server error
                // exactly like the two Redis-backed tiers above do. Hiding it
                // behind a 429 would tell the caller to slow down while the
                // real cause is the shared limiter being unavailable.
  ```
  **This is false.** The two tiers above use SCG `RedisRateLimiter`, whose `isAllowed` does:
  ```java
        return flux.onErrorResume(throwable -> {
            log.error("Error calling rate limiter lua", throwable);
            return Flux.just(Arrays.asList(1L, -1L));
        })
  ```
  → `allowed = true` ⇒ **fail-open**. (Verified in the resolved **5.0.1** bytecode via `javap -c`: `Flux.onErrorResume` + the literal `"Error calling rate limiter lua"` are present.)
- The edge limiter's error is **not** swallowed (`RedisEdgeRateLimiter.java:95-108`) ⇒ **fail-closed → HTTP 500**.

**Actual behaviour.** Redis down ⇒ L1 and L2 **silently vanish** (all traffic allowed, unlimited) while L3 turns every backoffice/search request into a **500**. The 2000 rps fleet cap disappears exactly when it might be needed (a Redis overload / OOM / failover is usually correlated with load).

**Violated invariant.** "A limiter's failure mode is uniform and documented." The comment states the opposite of the code.

**Root cause.** The custom limiter was written with an explicit fail-closed policy; the framework limiters were left with their default fail-open policy, and nobody noticed.

**Proposed fix.** Pick one policy and enforce it uniformly. Recommended **fail-closed with a degraded mode**: wrap both SCG limiters in a decorator that converts "Redis unavailable" into a *configurable* action (`503 Service Unavailable` with the fleet envelope, or allow with an alarm) and make the edge limiter use the same decorator. At minimum, correct the comment at `RedisEdgeRateLimiter.java:102-108`.

**Reproduction test.** Point the gateway at a closed Redis port; assert the status codes and that the three layers agree.

**Acceptance criterion.** With Redis unreachable, all three limiters produce the **same** status and the same body shape, documented in one place; and the behaviour is asserted by an integration test.

---

### GW-04 — HIGH — The "global" limiter is a single, non-partitioned bucket: no fairness, and the fleet cap does not scale with replicas

**Evidence**
- `gateway-service/src/main/java/com/shop/gateway/ratelimit/GlobalRateLimitFilter.java:20-21`
  ```java
      private static final String GLOBAL_ROUTE_ID = "gateway-system";
      private static final String GLOBAL_KEY = "system";
  ```
- `:50-53` — `rateLimiter.isAllowed(GLOBAL_ROUTE_ID, GLOBAL_KEY)` for **every** request.
- `gateway-service/src/main/java/com/shop/gateway/ratelimit/GlobalRateLimitProperties.java:16-17` — replenish 2000/s, burst 4000.
- `k8s/gateway-service.yaml:10` (`replicas: 3`) and `:74-92` (HPA `maxReplicas: 10`) — the fleet scales 2→10 but the cap stays 2000 rps.

**Actual behaviour.** One client sending 4000 rps consumes the entire fleet budget ⇒ every other client gets 429 until the bucket refills. And scaling the deployment out does not increase the permitted throughput ⇒ the HPA buys cost, not capacity.

**Violated invariant.** "A rate limiter protects the service; it must not let one tenant deny service to all others" and "the fleet-wide limit scales with the fleet".

**Root cause.** A single global key was chosen as the simplest possible fleet-wide cap, with no per-client partitioning and no relationship to the replica count.

**Proposed fix.** Hierarchical limiting: keep the global bucket but add a per-client bucket **in front of it** (so one client cannot dominate the global pool), and derive the global replenish rate from the replica count (or make the global bucket per-replica with a fleet-wide aggregate accounted in a separate observability metric). Alternatively, implement a fair scheduler (Redis sorted set / GCRA) that admits at most `k` requests per client per window while respecting the global ceiling.

**Reproduction test.** Two clients (distinct IPs) share one Redis; client A bursts at the global ceiling; assert client B is still served.

**Acceptance criterion.** A single client can consume at most its own share of the global budget; other clients continue to be served at the configured rate under a single-client flood.

---

### GW-05 — HIGH — The edge bucket computes its refill from the **gateway JVM clock** while the framework buckets use the **Redis server clock**

**Evidence**
- `gateway-service/src/main/java/com/shop/gateway/ratelimit/RedisEdgeRateLimiter.java:90-91`
  ```java
          final double rate = requestsPerMinute / 60.0d;
          final long now = Instant.now().getEpochSecond();
  ```
  and `:99` passes it as `ARGV[3]`, and the script writes it back as the new `last` (`:69` `redis.call('setex', KEYS[2], ttl, now)`).
- Framework Lua (`META-INF/scripts/request_rate_limiter.lua`): `local now = tonumber(ARGV[3]) or redis.call('TIME')[1]`, and SCG passes `""` ⇒ **Redis clock**.
- The script uses `local delta = math.max(0, now - last)` and `local filled = math.min(capacity, tokens + (delta * rate))` (`:58-59`).

**Actual behaviour.** With replicas whose clocks differ by Δ, the effective refill rate is `rate × (1 ± Δ/interval)` and the *stored* `last` is whichever replica wrote last. A replica with a fast clock over-refills and then **poisons** the bucket for the correctly-clocked replicas (`max(0, small_now - skewed_last) = 0` ⇒ no refill). The fleet-wide budget is therefore only as reliable as the clock spread.

**Violated invariant.** "The fleet-wide budget is identical for every replica."

**Root cause.** The custom script was written with a client-supplied timestamp instead of `redis.call('TIME')`.

**Proposed fix.** Drop `ARGV[3]` and use `redis.call('TIME')[1]` in `TOKEN_BUCKET_SCRIPT`, exactly like the framework script. (The script already runs server-side, so this costs nothing.)

**Reproduction test.** Unit-test the script against a Redis whose clock is fixed, and assert the bucket refills identically regardless of the caller's clock.

**Acceptance criterion.** The edge limiter's refill rate is independent of the gateway host clock; the script source contains no client-supplied timestamp.

---

### GW-06 — MEDIUM — Two to three serialised Redis round trips per request, and rejected traffic still burns the fleet-wide budget

**Evidence**
- `gateway-service/src/main/java/com/shop/gateway/config/RoutesConfig.java:37-43` → L1 `EVAL` per route.
- `gateway-service/src/main/java/com/shop/gateway/ratelimit/GlobalRateLimitFilter.java:50` → L2 `EVAL` per request.
- `gateway-service/src/main/java/com/shop/gateway/filter/RateLimitFilter.java:84` → L3 `EVAL` for backoffice/search.
- Order: L2 (HIGHEST_PRECEDENCE) → L3 (+10) → L1 (route filter, last). A request rejected by L3 has already spent an L2 token.

**Actual behaviour.** 2 Redis round trips on every route, 3 on the edge scopes, all serialised on the request's critical path. The fleet-wide budget is consumed even by requests that are subsequently rejected downstream of L2.

**Violated invariant.** "The edge adds a bounded, justified overhead per request."

**Root cause.** Three independent limiters were layered without consolidating the overlapping scopes.

**Proposed fix.** Merge L1 and L3 into a single per-route limiter whose key includes the scope, and derive the "global" cap from the same Redis script (e.g. a second key inside the same `EVAL`) so one round trip covers both. If the layers must stay separate, at least evaluate L3 **before** L2 so rejected traffic does not consume the fleet budget — or exempt the global bucket from counting rejected requests.

**Reproduction test.** Instrument the Redis client and assert exactly one `EVAL` per request.

**Acceptance criterion.** ≤ 1 Redis round trip per request; a request rejected by a downstream limiter does not decrement the fleet-wide bucket.

---

### GW-07 — MEDIUM — Inconsistent 429 contract: empty body from L1, duplicate `X-RateLimit-Remaining`, a `-1` sentinel leaked to clients, and no `Retry-After`

**Evidence**
- L1 rejects with `exchange.getResponse().setComplete()` (framework `RequestRateLimiterGatewayFilterFactory`) ⇒ **429 with an empty body**, no `ApiResponse` envelope.
- L2/L3 reject with the fleet envelope (`filter/GatewayErrorResponseWriter.java:47-64`, `ErrorCode.TOO_MANY_REQUESTS` = `ERR-0429`/`HttpStatus.TOO_MANY_REQUESTS`, `utils/common-core/.../ErrorCode.java:27`).
- Header collision: `filter/RateLimitFilter.java:93,96` uses `headers.set(...)`; the framework filter later uses `headers.add(...)` ⇒ a response for a backoffice/search request carries **two** `X-RateLimit-Remaining` values with different meanings (L3's remaining and L1's remaining).
- `RedisRateLimiter.getHeaders(config, tokensLeft)` is called with `-1L` on the Redis-error path ⇒ **`X-RateLimit-Remaining: -1`** reaches the client.
- L2 emits the `X-RateLimit-*` headers **only on rejection** (`GlobalRateLimitFilter.java:61-62`); L1 emits them on every request.
- `grep -rn "Retry-After" gateway-service/src/main/` → **0 hits**.
- `config/SecurityConfig.java:20-27` exposes all four `X-RateLimit-*` headers to browsers via CORS — so the duplicate/negative values are visible to JS clients.

**Actual behaviour.** Three different 429 shapes (empty body / envelope / envelope+headers) and a duplicated, possibly negative header. Clients cannot parse the limit reliably.

**Violated invariant.** "The edge returns one error contract."

**Root cause.** Three independently-authored rejection paths; no shared "reject with the fleet envelope" helper for the framework limiter.

**Proposed fix.** Replace the framework `RequestRateLimiter` with a custom `GlobalFilter` that reuses `GatewayErrorResponseWriter` (as L2/L3 already do), or wrap the framework limiter in a decorator that rewrites the response. Emit **one** `X-RateLimit-Remaining` (the strictest of the layers) via `set`, add `Retry-After` with the refill interval, and never emit a negative remaining.

**Reproduction test.** Assert on a rejected backoffice request: exactly one `X-RateLimit-Remaining` header, a JSON body with `code = "ERR-0429"`, and a `Retry-After` header.

**Acceptance criterion.** Every 429 from the gateway has an identical body shape and exactly one, non-negative `X-RateLimit-Remaining`, plus a `Retry-After`.

---

### GW-08 — MEDIUM — `GlobalRateLimitFilter` and `AdminIpAllowlistFilter` share the same order value; the documented "IP allowlist first" contract is not enforced

**Evidence**
- `gateway-service/src/main/java/com/shop/gateway/filter/FilterOrder.java:15`
  ```java
      public static final int ADMIN_IP_ALLOWLIST = Ordered.HIGHEST_PRECEDENCE;
  ```
- `gateway-service/src/main/java/com/shop/gateway/ratelimit/GlobalRateLimitFilter.java:56-59`
  ```java
      @Override
      public int getOrder() {
          return Ordered.HIGHEST_PRECEDENCE;
      }
  ```
- `Ordered.HIGHEST_PRECEDENCE == Integer.MIN_VALUE` ⇒ **identical** order values.
- The order test (`filter/FilterOrderTest.java:10-21`) and the integration test (`WebFluxRouteTests.java:178-191`) only compare the **three edge** filters — the global limiter is not covered.

**Actual behaviour.** Spring sorts equal-order filters by bean-registration order, which is not a contract. The global limiter may therefore run **before** the IP allowlist, so a request from a blocked IP consumes fleet budget and receives a 429 instead of the intended 403 (or vice versa), and the "fail-closed security control runs first" invariant in `filter/FilterOrder.java:12-15` is not guaranteed.

**Violated invariant.** "The backoffice IP allowlist is the first control to see a request."

**Root cause.** A new global filter was added at `HIGHEST_PRECEDENCE` without extending the `FilterOrder` contract.

**Proposed fix.** Give the global limiter a dedicated constant, e.g. `FilterOrder.GLOBAL_RATE_LIMIT = Ordered.HIGHEST_PRECEDENCE + 5` (after the allowlist, before the edge limiter), or `HIGHEST_PRECEDENCE + 1` if it must precede the allowlist. Extend `FilterOrderTest` and `WebFluxRouteTests.edgeFiltersAreRegisteredInBindingOrder` to include `GlobalRateLimitFilter`.

**Reproduction test.** Assert all four filters have **distinct** order values and that the allowlist's order is strictly less than the global limiter's.

**Acceptance criterion.** `FilterOrder` covers every `GlobalFilter` in the module; a test fails if two gateway filters share an order value.

---

### GW-09 — MEDIUM — The lazy JWT decoder performs a blocking OIDC discovery + JWKS fetch **on the event loop** at first decode

**Evidence**
- `gateway-service/src/main/java/com/shop/gateway/config/LazyReactiveJwtDecoder.java:35-56`
  ```java
      @Override
      public Mono<Jwt> decode(String token) throws JwtException {
          ReactiveJwtDecoder live = delegate.get();
          if (live == null) {
              live = buildOrFallback();
          }
          return live.decode(token);
      }

      private ReactiveJwtDecoder buildOrFallback() {
          try {
              ReactiveJwtDecoder built = factory.get();
  ```
- `gateway-service/src/main/java/com/shop/gateway/config/GatewayJwtSecurityConfig.java:60-64`
  ```java
          return new LazyReactiveJwtDecoder(() -> {
              NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withIssuerLocation(issuerUri).build();
  ```
- `NimbusReactiveJwtDecoder.withIssuerLocation(...).build()` performs a **blocking** `RestOperations` discovery + JWKS fetch.
- The class's own Javadoc (`config/LazyReactiveJwtDecoder.java:14-23`) states the intent.

**Actual behaviour.** The first authenticated request blocks its event-loop thread for the duration of the IdP round trip (and of any DNS resolution for the issuer host). With the default event-loop model this can starve the whole gateway. Concurrent first requests each perform their own discovery fetch (the `compareAndSet` at `:47` only picks the winner *after* the blocking work is done) ⇒ a thundering herd against Keycloak at boot.

**Violated invariant.** "No blocking I/O on the reactive event loop" — the class was written specifically to fix a startup-blocking problem and reintroduces it on the request path.

**Root cause.** The blocking call was *moved* rather than *offloaded*.

**Proposed fix.**
```java
        return Mono.fromSupplier(this::buildOrFallback)
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(decoder -> decoder.decode(token));
```
and/or pre-warm the decoder at startup on a `boundedElastic` scheduler so the first request never pays it.

**Reproduction test.** Point the issuer at a WireMock that delays the discovery response by 2 s; assert that (a) the gateway still answers other requests within the latency budget during the discovery, and (b) the discovery is made exactly once.

**Acceptance criterion.** No blocking call executes on a Reactor Netty event-loop thread; a test asserts the decoder is built on `boundedElastic`.

---

### GW-10 — MEDIUM — The "literal IP" guard in the admin allowlist accepts non-IP strings, so `InetAddress.getByName` performs a DNS lookup on the event loop

**Evidence**
- `gateway-service/src/main/java/com/shop/gateway/filter/AdminIpAllowlistFilter.java:106-121`
  ```java
      private boolean isAllowed(final String clientIp) {
          // Literal-IP guard: InetAddress.getByName would otherwise perform a
          if (!isLiteralAddress(clientIp)) {
              return false;
          }
          try {
              final InetAddress address = InetAddress.getByName(clientIp);
  ...
      private static boolean isLiteralAddress(final String value) {
          return value.matches("[0-9a-fA-F.:]+");
      }
  ```
  `"cafe"`, `"face"`, `"add"`, `"1.2.3.4.5"` all match `[0-9a-fA-F.:]+` but are **not** IP literals ⇒ `getByName` resolves them via DNS.
- Called from the event loop: `filter/AdminIpAllowlistFilter.java:84`.

**Actual behaviour.** A client that can influence the resolved client string can force a **blocking DNS lookup per request** on the event loop, even on the allowlist path.

**Violated invariant.** "The allowlist performs no name resolution."

**Root cause.** The regex was chosen to admit IPv6 (`:` and hex digits) but also admits any hex-looking word.

**Proposed fix.** Replace the regex with a strict literal parser (e.g. `InetAddresses.forString` / a hand-written IPv4+IPv6 validator) and never call the hostname-resolving `InetAddress.getByName`.

**Reproduction test.** `AdminIpAllowlistFilterTest`: an exchange with a non-IP XFF entry must be rejected **without** any DNS resolution (assert with a resolver that throws).

**Acceptance criterion.** `AdminIpAllowlistFilter` performs zero DNS lookups; only strictly-valid literal IPs are accepted.

---

### GW-11 — MEDIUM — The Kubernetes deployment points at a Redis that no manifest in this repo defines, sets no Redis password, and leaves the admin allowlist inactive

**Evidence**
- `k8s/gateway-service.yaml:30-32`
  ```yaml
              - name: SPRING_DATA_REDIS_HOST
                value: "redis.shop-system.svc.cluster.local"
              - name: SPRING_DATA_REDIS_PORT
                value: "6379"
  ```
- `grep -rn "redis" k8s/*.yaml` → **exactly one hit** (the two lines above). `grep -h "^kind:" k8s/*.yaml | sort | uniq -c` ⇒ 15 Deployments = 14 microservices + gateway ⇒ **no Redis workload exists in the repo**.
- No `SPRING_DATA_REDIS_PASSWORD` in `k8s/gateway-service.yaml`, while the compose Redis runs with `--requirepass` (`docker-compose.yml:72`).
- No `ADMIN_IP_ALLOWLIST` and no `GATEWAY_REQUIRE_ADMIN_IP_ALLOWLIST` in `k8s/gateway-service.yaml` ⇒ `AdminIpAllowlistProperties.active()` is `false` (`filter/AdminIpAllowlistProperties.java:27-29`) ⇒ the allowlist is **INACTIVE**, and `GatewaySecurityPostureValidator` only **warns** (`config/GatewaySecurityPostureValidator.java:109-111`) because `required` defaults to `false` (`application.yml:122`).
- `k8s/gateway-service.yaml:8` sets `SPRING_PROFILES_ACTIVE=prod` but **no `application-prod.yml` exists** in the module (only `application.yml`) ⇒ the prod profile adds nothing.

**Actual behaviour.** In the k8s topology the backoffice is reachable from **any** network origin (only the JWT `ADMIN` role protects it), and the rate limiter's Redis is either absent or unauthenticated. If it is absent ⇒ L1/L2 fail **open** and L3 fails **closed** (500 on search/backoffice) — i.e. the k8s deployment gets the worst combination of GW-03.

**Violated invariant.** "The production topology declares every dependency it needs and enforces the network-layer backoffice control."

**Root cause.** The k8s manifests were authored independently of the compose stack and were never reconciled with it.

**Proposed fix.** Add a Redis Deployment+Service (or an external-Secret reference) to `k8s/`, set `SPRING_DATA_REDIS_PASSWORD` from the Secret template, set `ADMIN_IP_ALLOWLIST` **and** `GATEWAY_REQUIRE_ADMIN_IP_ALLOWLIST=true` (as `docker-compose.prod.yml:58-59` already does), and add a `spring.profiles.active=prod` profile file — or remove the `prod` profile activation.

**Reproduction test.** A manifest lint test (`kubectl apply --dry-run=client` / a CI schema check) asserting that every env var referenced by the gateway deployment has a counterpart in the compose stack.

**Acceptance criterion.** `k8s/gateway-service.yaml` references only Services that exist in `k8s/`, sets the Redis credentials, and sets `GATEWAY_REQUIRE_ADMIN_IP_ALLOWLIST=true`.

---

### GW-12 — MEDIUM — The edge limiter's two Redis keys have no hash tag ⇒ `CROSSSLOT` failure in Redis Cluster

**Evidence**
- `gateway-service/src/main/java/com/shop/gateway/ratelimit/RedisEdgeRateLimiter.java:37,92-94`
  ```java
      private static final String KEY_PREFIX = "gateway:edge-rate-limit:";
  ...
          final List<String> keys = List.of(
                  KEY_PREFIX + scope + ":" + clientIp + ":tokens",
                  KEY_PREFIX + scope + ":" + clientIp + ":timestamp");
  ```
- Framework (`RedisRateLimiter.getKeys`) uses a hash tag explicitly:
  ```java
          String prefix = "request_rate_limiter.{" + routeId + "." + id + "}.";
  ```

**Actual behaviour.** In Redis Cluster (or any future sharded Redis), `:tokens` and `:timestamp` may hash to different slots ⇒ `EVAL` with two keys fails with `CROSSSLOT` ⇒ the edge limiter errors ⇒ **every backoffice/search request 500s**.

**Violated invariant.** "The two keys of one bucket always live in the same slot."

**Root cause.** The custom script's key layout was not derived from the framework's cluster-safe pattern.

**Proposed fix.** `KEY_PREFIX + "{" + scope + "." + clientIp + "}"` → `…{BACKOFFICE.203.0.113.7}.tokens` / `….timestamp`.

**Reproduction test.** Run the edge limiter against a Redis Cluster with ≥ 2 shards (or assert the key strings contain a hash tag).

**Acceptance criterion.** Both keys of a bucket share a `{…}` hash tag; a cluster-mode integration test passes.

---

### GW-13 — MEDIUM — Unauthenticated requests are not metered by any gateway limiter (401 is returned before the limiters run)

**Evidence**
- `gateway-service/src/main/java/com/shop/gateway/config/SecurityConfig.java:44-46` → `.anyExchange().authenticated()`.
- The Spring Security `WebFilter` chain runs **before** the `DispatcherHandler` that hosts the Gateway filters ⇒ an unauthenticated request to a non-public path is rejected **before** L1/L2/L3 run.
- Public paths (`application.yml:62-86`) include `/api/v1/auth/**`, `/api/v1/search/**`, `/api/v1/products/**`, `/api/v1/orders/track`, webhooks and the actuator.
- The L3 edge scopes cover only backoffice + search (`filter/RateLimitFilter.java:109-117`); there is **no** limiter scope for `/api/v1/auth/**`.

**Actual behaviour.**
1. A flood of requests to `/api/v1/orders` (or any protected path) with a bogus/expired token consumes **no** rate-limit budget at all — the 401 short-circuits before the limiters. Only the upstream ingress/LB limits that traffic.
2. **`/api/v1/auth/**` (login / sign-up) has no dedicated limiter at all.** It is only covered by L1 (100 rps **per IP**) and L2 (2000 rps fleet-wide) — and the L1 key is the **attacker-controlled** IP (§GW-01/GW-02), so credential-stuffing against `/api/v1/auth/login` is effectively unmetered.

**Violated invariant.** "The edge protects its own attack surface (JWT validation, authentication endpoints)."

**Root cause.** The limiter chain was placed downstream of the security chain and no scope covers the authentication endpoints.

**Proposed fix.** Move the L2/L3 limiters **ahead** of the authentication requirement (or add a `WebFilter` that applies a global + auth-endpoint-specific limiter before `SecurityWebFilterChain`), and add an explicit `AUTH` scope (e.g. 5 req/min per IP for `/api/v1/auth/login`) to the edge limiter.

**Reproduction test.** Send 1000 requests with an invalid token to a protected path and assert the global bucket was decremented (or that a dedicated pre-auth limiter returns 429).

**Acceptance criterion.** Unauthenticated traffic is metered by at least the global limiter; `/api/v1/auth/**` has a dedicated, IP-keyed, low limit.

---

### GW-14 — LOW/MEDIUM — No bound on the number of rate-limit keys, and the shared Redis has no memory policy

**Evidence**
- `gateway-service/src/main/java/com/shop/gateway/ratelimit/RedisEdgeRateLimiter.java:92-94` — one key pair per distinct client string, no cap, no cardinality limit.
- `docker-compose.yml:72` — `command: redis-server --requirepass "${REDIS_PASSWORD}" --appendonly yes` ⇒ **no `--maxmemory`, no `--maxmemory-policy`**, and `--appendonly yes` means every rate-limit key churn is also written to the AOF.
- The **same** Redis instance serves the `inventory`, `order` and `product` caches (`inventory-service/.../CacheConfig.java`, `order-service/.../CacheConfig.java`, `product-service/.../CacheConfig.java` — from the repo-wide grep) ⇒ cache eviction and limiter keys compete.
- TTL is 120 s (`RedisEdgeRateLimiter.java:66`) ⇒ live keys are bounded only by the request rate, not by a cap.

**Actual behaviour.** Under a key-flood (GW-02) the limiter's key space competes with the business caches in a Redis with no eviction policy ⇒ memory exhaustion ⇒ a Redis outage ⇒ (per GW-03) L1/L2 fail open and L3 500s.

**Violated invariant.** "The shared Redis has a bounded, prioritised key space."

**Root cause.** The limiter shares a general-purpose Redis with no namespace budget, and no `maxmemory` policy.

**Proposed fix.** (a) Give the limiter a dedicated Redis instance (or at least a dedicated key prefix + `--maxmemory` + `volatile-ttl` on a namespaced DB); (b) bound the key space: reject/ignore non-literal client identities (GW-02) and cap the number of tracked buckets; (c) disable AOF for the limiter keys or use a separate instance.

**Reproduction test.** A load test that drives N distinct client identities and asserts the Redis key count stays bounded and the cache keys are unaffected.

**Acceptance criterion.** The limiter's key count is bounded by a documented maximum; the shared Redis has an explicit memory policy; the business caches are unaffected by limiter key pressure.

---

### GW-15 — LOW — Inconsistent key spaces between the layers ⇒ an authenticated client gets up to 3× the configured budget; IPv4/IPv6 spellings are separate buckets

**Evidence**
- L1 key: `"user:" + jwt.sub` **or** `"ip:" + host` (`ratelimit/RateLimitKeyResolver.java:42,61`).
- L3 key: IP **only** (`ratelimit/RedisEdgeRateLimiter.java:92-94`), even for authenticated requests.
- `filter/ClientIpResolver.java:25-29` returns `getAddress().getHostAddress()` — the **IPv6** form for IPv4-mapped addresses (`::ffff:203.0.113.7`) while `RateLimitKeyResolver.java:64-66` returns the same form ⇒ the two layers agree with each other, but a client presenting `203.0.113.7` in one connection and `::ffff:203.0.113.7` in another gets **two** buckets in both layers.

**Actual behaviour.**
1. A client that presents a token gets **L1-user + L1-ip (for anonymous paths) + L2 + L3** — the per-route budget is effectively `100 rps (user) + 100 rps (ip)` on different paths.
2. A NAT'd office shares one L3 bucket for the whole office: 10 backoffice req/min and 60 search req/min **for everybody**.
3. Switching the IPv4/IPv6 spelling of the same address doubles every per-IP budget.

**Violated invariant.** "One client ⇒ one budget, and the budget is the configured one."

**Root cause.** Three layers chose their key spaces independently; the L3 layer has no notion of a user.

**Proposed fix.** Normalise the client identity once (a canonical `ClientIdentity` value object: validated IP in a single canonical form + optional user id) and derive all layer keys from it. Decide explicitly whether L3 should be per-user or per-IP and make L1 agree.

**Reproduction test.** The same client, authenticated, over both spellings, must not exceed one budget.

**Acceptance criterion.** A single client consumes at most one bucket per layer, regardless of the address spelling or the presence of a token.

---

### GW-16 — LOW — The edge bucket's TTL is refreshed on rejected requests, so an abuser's keys never expire and the key set only grows

**Evidence**
- `gateway-service/src/main/java/com/shop/gateway/ratelimit/RedisEdgeRateLimiter.java:66-70`
  ```lua
              local ttl = math.ceil((capacity / rate) * 2)
              if ttl > 0 then
                  redis.call('setex', KEYS[1], ttl, filled)
                  redis.call('setex', KEYS[2], ttl, now)
              end
  ```
  The `setex` runs on **every** call, including calls where `allowed = 0` (the script only writes `filled`/`now`; the `allowed` flag does not short-circuit the writes).

**Actual behaviour.** A client that keeps hammering a scope keeps its two keys alive forever; the 120 s TTL never elapses while traffic continues ⇒ the key set grows monotonically with the number of distinct clients, not with the number of *active* clients.

**Violated invariant.** "Idle buckets are reclaimed."

**Root cause.** The TTL is a "keep alive" TTL rather than an "idle since last *consumption*" TTL.

**Proposed fix.** Only refresh the TTL when a token was actually consumed, or track a separate `last_seen` key with a longer TTL and let the bucket expire after a genuine idle period.

**Reproduction test.** After a client is rejected continuously for > 120 s, assert that the bucket eventually expires.

**Acceptance criterion.** A bucket whose client has stopped making *accepted* requests is evicted within the documented idle window.

---

### GW-17 — LOW — `replenishRate` (tokens per **second**) and `requestsPerMinute` (requests per **minute**) live under two similarly named property namespaces

**Evidence**
- `gateway-service/src/main/java/com/shop/gateway/ratelimit/RateLimitProperties.java:15-21` — `replenishRate` (default 100), `burstCapacity` (200), `requestedTokens` (1). The SCG semantics are **tokens per second** (`META-INF/scripts/request_rate_limiter.lua`: `local rate = tonumber(ARGV[1])` used as `delta*rate` per second).
- `gateway-service/src/main/java/com/shop/gateway/filter/EdgeRateLimitProperties.java:23-27` — `backofficeRequestsPerMinute` (10), `searchRequestsPerMinute` (60).
- `application.yml:87-114` — two adjacent blocks, `gateway.rate-limit.*` and `gateway.edge-rate-limit.*`, with no unit stated in the YAML comments for the former.
- `docker-compose.yml:297` → `GATEWAY_RATE_LIMIT_REPLENISH_RATE: ${...:-100}`.

**Actual behaviour.** An operator reading `GATEWAY_RATE_LIMIT_REPLENISH_RATE=100` as "100 requests per minute" is off by a factor of **60**; the real value is 100 requests **per second per route per key** — i.e. 6000/min. A "rate limit" of 6000 rps is not a meaningful control.

**Violated invariant.** "A configured limit is unambiguous."

**Root cause.** Two different units under two similarly named namespaces, with the unit documented only in the Javadoc of one of them.

**Proposed fix.** Rename to `replenishTokensPerSecond` / add `...-per-second` to the property name and to the env var; document the unit in `application.yml` next to each knob.

**Reproduction test.** Assert the effective per-minute budget derived from the properties.

**Acceptance criterion.** Every rate-limit property name states its unit; the YAML comments state it too.

---

### GW-18 — LOW — No `Retry-After` header on any 429

**Evidence.** `grep -rn "Retry-After" gateway-service/src/main/` → **0 hits**. The rejections are `filter/RateLimitFilter.java:92-95`, `ratelimit/GlobalRateLimitFilter.java:61-68` and the framework's `RequestRateLimiterGatewayFilterFactory`.

**Actual behaviour.** A well-behaved client (or a CDN) cannot know when to retry; standard clients do not implement backoff for a 429 without `Retry-After`.

**Violated invariant.** "A 429 tells the caller when to come back."

**Proposed fix.** Add `Retry-After: <ceil(1/rate)>` (1 s for the search scope, 6 s for the backoffice scope, 1 s for L1/L2) on every 429.

**Reproduction test.** Assert the header's presence and value on each rejection path.

**Acceptance criterion.** Every 429 carries a correct `Retry-After`.

---

### GW-19 — LOW — The root POM comment "gateway migrated to Apache APISIX" is false and misleads every future reader

**Evidence.**
- `pom.xml:49` — `<!-- removed: spring-cloud (gateway migrated to Apache APISIX) -->`.
- But `pom.xml:42` sets `<spring-cloud.version>2025.1.0</spring-cloud.version>` and `pom.xml:80-81` imports `spring-cloud-dependencies`; `gateway-service/pom.xml:37-40` depends on `spring-cloud-gateway-server-webflux`.
- `grep -ril "apisix" .` → only `pom.xml`, `docs/ROADMAP.md`, `plan.md`, `.gitnexus/lbug` and the audit's own scan artefact — **no APISIX dependency, config, image or compose service**.
- `docker-compose.yml:279-282` — the section is literally titled *"GATEWAY (Spring Cloud Gateway - new)"*.

**Actual behaviour.** A reviewer (or an automated auditor) concludes the edge is APISIX and skips the Java gateway entirely. This is the root cause of the brief's premise.

**Violated invariant.** "The build file describes the system that exists."

**Root cause.** A stale comment left behind after a reverted migration.

**Proposed fix.** Delete the comment (or replace it with the truth: *"the edge is Spring Cloud Gateway (WebFlux); APISIX was evaluated and rejected"*). Align `docs/ROADMAP.md` and `plan.md`.

**Reproduction test.** A doc-lint that greps the POM for "APISIX" and fails the build — or simply remove the string.

**Acceptance criterion.** `grep -ri apisix pom.xml` returns nothing, or the string is accompanied by a statement that APISIX is not deployed.

---

### GW-20 — LOW — The "fail-fast" posture validator never verifies that the peer is a trusted proxy

**Evidence**
- `gateway-service/src/main/java/com/shop/gateway/config/GatewaySecurityPostureValidator.java:114-127`
  ```java
      private void validateClientIpTrust() {
          int hops = rateLimitProperties.trustedProxyHops();
          String strategy = forwardHeadersStrategy == null ? "none" : forwardHeadersStrategy.trim();
          if (hops <= 0 && !"none".equalsIgnoreCase(strategy)) {
              throw new IllegalStateException(...);
          }
          log.info("Gateway client-IP trust: strategy={} trusted-proxy-hops={}", strategy, hops);
      }
  ```
  It only rejects `hops == 0` combined with a non-`none` strategy. It never checks that a trusted-proxy allow-list exists (none does), and it logs `"Gateway client-IP trust: ..."` — an **unconditional INFO** that reads like an assurance.

**Actual behaviour.** With `hops = 1` (the shipped default) and no trusted-proxy allow-list, the validator logs a reassuring line while the identity is forgeable (GW-01).

**Violated invariant.** "A startup guard that claims to validate the client-IP trust model actually validates it."

**Root cause.** The guard was written for the `ForwardedHeaderTransformer` misconfiguration only.

**Proposed fix.** Extend it to require a non-empty trusted-proxy CIDR list whenever `hops > 0`, and to reject `hops > 0` when the gateway's port is published directly.

**Reproduction test.** `GatewaySecurityPostureValidatorTest`: `hops = 1` with an empty trusted-proxy list ⇒ `IllegalStateException`.

**Acceptance criterion.** The validator fails fast on every spoofable client-IP configuration.

---

### GW-21 — LOW — `RateLimitKeyResolver` is unit-tested against a **mocked** exchange, so a framework regression would silently switch every L1 key to an IP key

**Evidence**
- `gateway-service/src/test/java/com/shop/gateway/ratelimit/RateLimitKeyResolverTest.java:80-85`
  ```java
      private ServerWebExchange exchangeWith(ServerHttpRequest request, Mono<Principal> principal) {
          var exchange = mock(ServerWebExchange.class);
          when(exchange.getRequest()).thenReturn(request);
          when(exchange.getPrincipal()).thenReturn(principal);
          return exchange;
      }
  ```
  The mock **overrides** `getPrincipal()`, whereas the production exchange is `SecurityContextServerWebExchange` (`spring-security-web-7.1.1`), whose `getPrincipal()` returns the authentication from the Reactor context.
- There is **no** integration test that asserts the L1 key is the *user* key for an authenticated request. `WebFluxRateLimitTests.java:179-181` disables `gateway.rate-limit.enabled` entirely (`registry.add("gateway.rate-limit.enabled", () -> "false")`), so L1 is never exercised in the full-stack tests.
- `MockServerWebExchange.getPrincipal()` returns a real `principalMono`, while `DefaultServerWebExchange.getPrincipal()` returns `Mono.empty()` — i.e. **mock and production disagree**, and only the mock is tested.

**Actual behaviour.** The user-keying path of L1 has **no** end-to-end coverage. If Spring Security's filter ordering changed, or if `SecurityContextServerWebExchangeWebFilter` were removed, every authenticated request would silently fall back to IP keying and **no test would fail**.

**Violated invariant.** "A security-relevant key derivation is covered by an end-to-end test."

**Root cause.** The unit test mocks the very API whose framework semantics it depends on.

**Proposed fix.** Add an integration test that drives a real HTTP request with a valid JWT through the full chain and asserts the Redis key set contains `request_rate_limiter.{<route>.user:<sub>}` (e.g. via a Redis `KEYS`/`SCAN` assertion against a Testcontainer), i.e. assert the *observable* key, not the resolver's return value.

**Reproduction test.** As above; the test fails if the key becomes `ip:…`.

**Acceptance criterion.** The user-key path is proven by an end-to-end test that inspects the Redis keys.

---

### GW-22 — LOW — Two independent issuer properties can drift; the one the decoder actually uses is not the one the gateway validates

**Evidence.**
- `gateway-service/src/main/java/com/shop/gateway/config/GatewayJwtSecurityConfig.java:49-55`
  ```java
      public GatewayJwtSecurityConfig(
              GatewayJwtProperties properties,
              @org.springframework.beans.factory.annotation.Value(
                      "${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuerUri) {
          this.properties = properties;
          this.issuerUri = issuerUri;
      }
  ```
- `gateway-service/src/main/resources/application.yml:30` → `spring.security.oauth2.resourceserver.jwt.issuer-uri: ${JWT_ISSUER_URI:http://keycloak:8080/realms/ecommerce}`
- `application.yml:48` → `gateway.keycloak-issuer-uri: ${JWT_ISSUER_URI:http://keycloak:8080/realms/ecommerce}` — a **second** property with the same default.
- `config/GatewayProperties.java:14-15` declares `keycloakIssuerUri` as `@NotBlank` — but nothing ever compares it with the value the decoder uses.

**Actual behaviour.** If `JWT_ISSUER_URI` is set to a *different* value in one place (e.g. `gateway.keycloak-issuer-uri` overridden but not `spring.security...`), the gateway validates tokens against the **spring.security** issuer while the operator believes it validates against the other ⇒ silent misconfiguration.

**Violated invariant.** "One source of truth for the trusted issuer."

**Root cause.** The record field was added for symmetry with the other properties but never wired in.

**Proposed fix.** Delete `GatewayProperties.keycloakIssuerUri` (it is unused) or make `GatewayJwtSecurityConfig` take `GatewayProperties.keycloakIssuerUri` and set `spring.security.oauth2.resourceserver.jwt.issuer-uri` from it (or validate that the two are equal at startup).

**Reproduction test.** A context test asserting the two values are identical.

**Acceptance criterion.** Exactly one issuer property exists, or the startup fails when they differ.

---

### GW-23 — LOW — No explicit JWT algorithm pinning

**Evidence.** `config/GatewayJwtSecurityConfig.java:61-64` builds `NimbusReactiveJwtDecoder.withIssuerLocation(...)` with **no** `setJwtValidator` beyond the token validators, and there is no `jwsAlgorithm` restriction anywhere (`grep -rn "jwsAlgorithm\|RS256\|setJwsAlgorithm" gateway-service/src/` → 0 hits). The JWKS in `TestKeys.java:36-40` is RSA-only, and the real realm publishes RSA keys ⇒ `HS256`-with-the-public-key and `alg:none` are rejected by Nimbus. But the gateway will accept **any** RSA/PS algorithm the realm's key advertises.

**Actual behaviour.** The accepted algorithm set is implied by the IdP's JWKS rather than pinned by the gateway. A realm change that publishes an additional key type (e.g. a PS256 key, or a key intended for another purpose) widens the accepted set silently.

**Violated invariant.** "The gateway accepts exactly the algorithms it was configured to accept."

**Root cause.** No `jwsAlgorithm` configuration.

**Proposed fix.** Build the decoder with `NimbusReactiveJwtDecoder.withJwkSetUri(...).jwsAlgorithm(SignatureAlgorithm.RS256)` (or validate `alg` with a `JwtClaimValidator`/custom check) and pin it in configuration.

**Reproduction test.** A test that presents an RS512-signed token and expects a 401.

**Acceptance criterion.** The accepted algorithm list is explicit, configured, and tested.

---

### GW-24 — LOW/INFO — The caller-supplied `X-Correlation-Id` is echoed into the error envelope without validation

**Evidence.** `gateway-service/src/main/java/com/shop/gateway/filter/GatewayErrorResponseWriter.java:39,79-81`
```java
    private static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
    ...
    private String traceId(final ServerWebExchange exchange) {
        return exchange.getRequest().getHeaders().getFirst(CORRELATION_ID_HEADER);
    }
```
The value is JSON-escaped (`GatewayErrorResponseWriter.java:90-115`), so there is no injection, but it is unvalidated, unbounded in length, and written into the response body.

**Actual behaviour.** A caller can place arbitrary content into the `traceId` field of every error response and can collide with a legitimate server-side trace id.

**Violated invariant.** "Server-generated correlation ids are not caller-controlled."

**Root cause.** The passthrough was chosen for parity with the servlet-side envelope.

**Proposed fix.** Validate the header (length ≤ 64, `[A-Za-z0-9._-]+`) and, if it does not conform, **generate** a trace id instead of echoing it.

**Reproduction test.** Send `X-Correlation-Id: <script>` / a 4 KB value and assert the response `traceId` is either sanitised or replaced.

**Acceptance criterion.** The echoed `traceId` is bounded and charset-restricted, or replaced by a server-generated id.

---

### GW-25 — LOW (latent) — `AbstractRateLimiter`'s configuration map is a plain `HashMap` written from the event thread and read from request threads

**Evidence.** Framework `spring-cloud-gateway-server-webflux-5.0.0-sources.jar`:
- `support/AbstractStatefulConfigurable.java` → `private Map<String, C> config = new HashMap<>();`
- `filter/ratelimit/AbstractRateLimiter.java:50-62`
  ```java
      @Override
      public void onApplicationEvent(FilterArgsEvent event) {
          ...
          getConfig().put(routeId, routeConfig);
      }
  ```
  while `loadConfiguration(routeId)` reads the same map on the **request** thread (`RedisRateLimiter.loadConfiguration` → `getConfig().getOrDefault(routeId, defaultConfig)`).

**Actual behaviour.** Not currently reachable — the repo defines all 29 routes programmatically (`config/RoutesConfig.java:34`) and publishes no `FilterArgsEvent`, so the map stays empty. It becomes a real data race (and a possible infinite loop on a corrupted `HashMap`) if YAML/`/actuator/gateway/refresh` route definitions are introduced.

**Violated invariant.** "Shared configuration maps are safe for concurrent read/write."

**Root cause.** Framework limitation, currently masked.

**Proposed fix.** If dynamic route definitions are ever enabled, replace the limiter with a `ConcurrentHashMap`-backed implementation (or a custom limiter that reads an immutable snapshot).

**Reproduction test.** A test that publishes `FilterArgsEvent` concurrently with `isAllowed` calls.

**Acceptance criterion.** Either the map is never written (documented) or it is concurrency-safe.

---

### GW-26 — MEDIUM — Duplicate/ambiguous `X-RateLimit-Remaining` header (merged with GW-07 above as the header part)

*(Listed here for completeness of the count; the full defect is GW-07.)*

**Evidence.** `filter/RateLimitFilter.java:93,96` → `exchange.getResponse().getHeaders().set(REMAINING_HEADER, …)`; the framework's `RequestRateLimiterGatewayFilterFactory` later does `exchange.getResponse().getHeaders().add(header.getKey(), header.getValue())` for the same header name ⇒ **two** `X-RateLimit-Remaining` values on a backoffice/search response.

**Reproduction test.** `WebFluxRateLimitTests`: assert `response.getHeaders().get("X-RateLimit-Remaining")` has exactly one element.

**Acceptance criterion.** Exactly one value.

---

### GW-27 — MEDIUM — No circuit breaker and no bulkhead anywhere in the gateway

**Evidence.**
- `grep -rn "resilience4j\|CircuitBreaker\|Bulkhead" gateway-service/` → 0 hits. The framework's `GatewayResilience4JCircuitBreakerAutoConfiguration` is present in the SCG jar but not activated (no `spring.cloud.gateway.filter.circuit-breaker.enabled`, no `resilience4j` dependency in `gateway-service/pom.xml`).
- `config/RoutesConfig.java:31-48` — each route has exactly **one** filter (`RequestRateLimiter`); `GatewayRateLimitContextTest.java:70-72` asserts `routes).allSatisfy(route -> assertThat(route.getFilters()).hasSize(1))` ⇒ **no per-route timeout, no retry, no CB**.
- No `spring.cloud.gateway.httpclient.*` timeout configuration in `application.yml`.
- The only "protection" against a slow dependency is the **global bucket** (L2), which throttles *rate*, not *concurrency*.

**Actual behaviour.** A hung backend (or a hung Redis, per GW-03/GW-09) holds an unbounded number of in-flight gateway requests and event-loop resources until the connector/Netty timeouts fire. One slow downstream service can exhaust the gateway's connection pool and stall unrelated routes.

**Violated invariant.** "A degraded dependency cannot consume the whole edge."

**Root cause.** The gateway was built as a pure router with limiters; the resilience layer was never added.

**Proposed fix.**
- Enable SCG's circuit breaker with Resilience4J (add the dependency + `spring.cloud.gateway.filter.circuit-breaker.enabled=true` + per-route `CircuitBreaker`/`Retry`/`TimeLimiter` filters), or implement a simple in-flight concurrency bulkhead per upstream (`spring.cloud.gateway.httpclient.max-connections` + `pool` settings are **not** per-route, so a per-route semaphore filter is needed).
- Set explicit `spring.cloud.gateway.httpclient.connect-timeout` / `response-timeout`.
- Add a per-upstream in-flight cap so a slow service cannot starve the others.

**Reproduction test.** Point one route at a WireMock that never responds and assert that other routes keep being served.

**Acceptance criterion.** A slow or hung upstream degrades only its own routes; other routes keep meeting their latency SLO.

---

### GW-28 — LOW — `RedisEdgeRateLimiter.requestsPerMinute(String)` is dead code

**Evidence.** `gateway-service/src/main/java/com/shop/gateway/ratelimit/RedisEdgeRateLimiter.java:132-140`
```java
    public int requestsPerMinute(final String scope) {
        return "SEARCH".equals(scope)
                ? properties.searchRequestsPerMinute()
                : properties.backofficeRequestsPerMinute();
    }
```
The only caller of `acquire(...)` is `filter/RateLimitFilter.java:81-84`, which reads the properties **directly** and never calls `requestsPerMinute`. `grep -rn "requestsPerMinute(" gateway-service/src/` → the definition plus `EdgeRateLimitProperties`' own accessors only.

**Actual behaviour.** Two places express the same "scope → budget" mapping; they can drift. (The mapping itself happens to be consistent today.)

**Violated invariant.** "One place decides the budget of a scope."

**Proposed fix.** Delete the method, or make `RateLimitFilter` call it (moving the mapping into the limiter).

**Reproduction test.** n/a (dead code).

**Acceptance criterion.** No duplicate scope→budget mapping.

---

### GW-29 — INFO — Scope/bypass asymmetry between the IP allowlist and the edge rate limiter

**Evidence.**
- `filter/AdminIpAllowlistFilter.java:37-55, 96-104` — the allowlist **bypasses** `/api/v1/webhooks/**`, `/actuator/health*` and the storefront prefixes (`/api/v1/products`, `/categories`, `/brands`, `/search`, `/ratings`, `/medias`, `/auth`).
- `filter/RateLimitFilter.java:109-117` — the edge limiter scopes are **only** the 12 backoffice prefixes and `/api/v1/search`.
- ⇒ `/api/v1/products/**`, `/api/v1/ratings/**`, `/api/v1/medias/**`, `/api/v1/categories/**`, `/api/v1/brands/**` and `/api/v1/auth/**` are **neither** IP-allowlisted **nor** edge-rate-limited. They are covered only by L1 (100 rps per user/IP) and L2.

**Actual behaviour.** The storefront catalogue and the authentication endpoints — the most scrape/abuse-prone public surfaces — have **no dedicated limiter**; they are limited only by the generic per-route bucket, whose key is the forgeable IP.

**Violated invariant.** "Public surfaces that are deliberately open are deliberately rate-limited."

**Proposed fix.** Add explicit edge scopes (e.g. `CATALOGUE` 60 rpm/IP, `AUTH` 5 rpm/IP) so the public surface has a first-class limit.

**Reproduction test.** A test asserting the `AUTH` scope returns 429 after N login attempts.

**Acceptance criterion.** Every `permitAll` path in `application.yml:62-86` is covered by at least one explicitly-configured scope.

---

## 12. THINGS THAT ARE **CORRECT** (proven, not assumed)

| Control | Verdict | Evidence |
| --- | --- | --- |
| The bucket state is **not** per-JVM | **CORRECT** — all three layers are Redis-backed | `RedisEdgeRateLimiter.java:45-109`; SCG `RedisRateLimiter`; no `Map` of buckets anywhere in `gateway-service/src/main/` |
| The Redis mutation is **atomic** | **CORRECT** — one Lua `EVAL` per layer; no GET-then-SET race | `RedisEdgeRateLimiter.java:95-99` + script `:45-73`; SCG `request_rate_limiter.lua` |
| The in-process bucket4j cache the comments describe is **gone** | **CORRECT** — no bucket4j dependency, framework `Bucket4jRateLimiter` unused | `grep -n "bucket4j" pom.xml gateway-service/pom.xml` → 0 |
| The authenticated user id comes from a **verified JWT**, not a header | **CORRECT** | `RateLimitKeyResolver.java:36-44`; §5.3 chain through `SecurityContextServerWebExchange` |
| `"user:"` / `"ip:"` namespaces cannot collide | **CORRECT** | `RateLimitKeyResolver.java:20-21` |
| `X-Real-IP` is not honoured | **CORRECT** | `grep -rn "X-Real-IP" gateway-service/` → 0 |
| `server.forward-headers-strategy: none` prevents Boot's leftmost-XFF rewrite | **CORRECT** | `application.yml:4-15`; `GatewaySecurityPostureValidator.java:114-127` fails fast if it is ever changed to `framework` with `hops = 0` |
| The CORS wildcard + `allowCredentials(true)` foot-gun is closed | **CORRECT** | `SecurityConfig.java:52-63`; `GatewaySecurityPostureValidator.java:60-71` fails startup on `*` |
| The `azp` client check is enabled and rejects foreign-client tokens | **CORRECT** | `GatewayJwtSecurityConfig.java:83-94`; `application.yml:56`; `GatewayJwtSecurityConfigTest.java:24-29` |
| The issuer is always enforced | **CORRECT** | `GatewayJwtSecurityConfig.java:69`; `GatewayJwtSecurityConfigTest.java:31-36` |
| Clock skew is handled | **CORRECT** (60 s default) | `GatewayJwtSecurityConfig.java:70` (`JwtTimestampValidator()`) |
| 401 vs 403 semantics are right (401 unauthenticated, 403 authenticated-but-not-ADMIN) | **CORRECT** | `SecurityConfig.java:44-46`; `AdminRoleGateFilter.java:77-79`; `WebFluxRouteTests.java:84-105` |
| The gateway does **not** inject a spoofable identity header downstream | **CORRECT** — the original JWT is forwarded and re-validated downstream | no `mutate().request(...)` anywhere in `gateway-service/src/main/` |
| Percent-encoding and matrix-variable evasion are handled | **CORRECT** and well covered | `filter/RequestPathGuard.java:24-71`; `filter/RequestPathMatcher.java:25-36`; `RateLimitFilterTest.java:164-223`; `AdminIpAllowlistFilterTest.java:161-227` |
| The "leftmost XFF entry is decisive" spoofing vector is closed | **CORRECT** | `WebFluxIpAllowlistTests.java:101-121` pins the rightmost-entry behaviour; `ClientIpResolverTest.java:12-16` |
| The admin allowlist fails **closed** once configured, and unparseable CIDRs fail startup | **CORRECT** | `AdminIpAllowlistProperties.java:27-29`; `AdminIpAllowlistFilter.java:123-150`; `AdminIpAllowlistFilterTest.java:154-159` |
| The gateway holds **no** mutable shared state; no `@Scheduled`; no `synchronized`; no `.block()` | **CORRECT** | §8.1; `grep -rn "@Scheduled\|\.block()\|synchronized" gateway-service/src/main/` → 0 |
| `LazyReactiveJwtDecoder` is thread-safe | **CORRECT** (the *blocking* is the problem, not the concurrency) | `LazyReactiveJwtDecoder.java:29,47` (AtomicReference + CAS) |
| The Redis client is non-blocking (Lettuce reactive) | **CORRECT** | `EdgeFiltersConfiguration.java:49-52`; `RedisEdgeRateLimiter.java:79,95` |
| No JDBC/JPA in the gateway | **CORRECT** | `gateway-service/pom.xml:25-36` (explicit exclusion) |

---

## 13. OPEN QUESTIONS FOR THE LEAD

1. **Is the gateway port reachable directly in production?** `docker-compose.prod.yml:46-48` publishes it. If the answer is "no, an external LB fronts it", GW-01 downgrades from CRITICAL to HIGH — but the code still must not *rely* on that. If the answer is "yes", it stays CRITICAL.
2. **Does the nginx ingress controller in the k8s cluster append (not replace) `X-Forwarded-For`?** The default nginx behaviour appends, which makes the rightmost entry correct. A `proxy_set_header X-Forwarded-For $http_x_forwarded_for` (pass-through) override would re-open GW-01.
3. **Is there an external Redis for the k8s namespace** (a Helm chart outside this repo)? If not, GW-11 is confirmed and the k8s rate limiting is non-functional (L1/L2 fail open, L3 500s).
4. **Should the L1 per-route limiter exist at all?** It is 100 rps **per second** per user per route — i.e. it is not a meaningful limit for a single user, and it costs a Redis round trip on every request. Consolidating L1+L2+L3 into one Lua script (one round trip, one policy, one failure mode) would remove GW-03, GW-06, GW-07 and part of GW-04 at once. This is the single highest-leverage change in this report.

---

*End of report. Files read: 52 `gateway-service` `.java` files (30 main + 22 test, 100 %), `gateway-service/src/main/resources/application.yml`, `gateway-service/pom.xml`, root `pom.xml` (targeted), `docker-compose.yml` (gateway + Redis blocks), `docker-compose.prod.yml` (gateway + Redis blocks), `k8s/gateway-service.yaml`, `k8s/ingress.yaml`, `k8s/microservices.yaml` (kind census), `.env.example`, `.env.prod.example`, `utils/common-core/.../ErrorCode.java`, plus 13 framework source/bytecode artefacts from the resolved `.m2` jars. No application source file was modified.*
