# BÁO CÁO ĐÁNH GIÁ VÀ NGHIỆM THU MỨC ĐỘ SẴN SÀNG TRIỂN KHAI SẢN XUẤT
## HỆ THỐNG KIẾN TRÚC VI DỊCH VỤ THƯƠNG MẠI ĐIỆN TỬ PETPROJECT PLATFORM
### TOÀN DIỆN 37 MỤC — SINH TỰ ĐỘNG TỪ BẰNG CHỨNG KIỂM ĐỊNH

- **Thời điểm sinh báo cáo**: 2026-11-20T08:07:10Z
- **Nguồn dữ liệu**: trạng thái bền vững của harness, kết quả kiểm định, kết quả chạy tải, kết quả Stripe, kết quả Kubernetes và lịch sử phân phối Git.
- **Nguyên tắc**: mọi khẳng định trong báo cáo này được sinh tự động từ bằng chứng đã lưu. Trường nào chưa có bằng chứng thì được ghi rõ là chưa xác minh.

---

## 1. TỔNG QUAN ĐIỀU HÀNH & KẾT LUẬN CUỐI CÙNG (EXECUTIVE SUMMARY)

- **Quyết định nghiệm thu**: **CHƯA ĐẠT — CHƯA SẴN SÀNG TRIỂN KHAI SẢN XUẤT**.
- **Cổng kiểm định**: 5/24 đạt, 2 không đạt, 17 chờ.
- **Rà soát mã nguồn**: 97.09% (1366/1407 tập tin).
- **Tác vụ còn mở mức HIGH**: 1 (MEDIUM: 103, LOW: 77).
- **Quyết định sản xuất theo completion predicate**: chưa đạt.
- **Cổng đang không đạt**: GATE-1, GATE-4, GATE-5, GATE-6, GATE-7, GATE-8, GATE-9, GATE-10, GATE-12, GATE-13, GATE-14, GATE-15, GATE-16, GATE-17, GATE-18, GATE-21, GATE-22, GATE-23, GATE-24.
- **Bằng chứng tải nặng**: yêu cầu không đo được, tần suất không đo được, p95 không đo được, tỷ lệ lỗi không đo được.
- **Stripe sandbox thực**: trạng thái BLOCKED — xem mục 12 và 13.
- **Cảnh báo Telegram**: 1 cổng cảnh báo đã được ghi nhận — xem mục 29, 30 và 31.

## 2. KIỂM TOÁN TOÀN BỘ MÃ NGUỒN (FULL SOURCE AUDIT)

- Tổng số tập tin trong phạm vi: **1407**; đã rà soát: **1366**.
- Tỷ lệ rà soát mã thực thi: **96.66%** (1185/1226).
- Phân bố theo nhóm: BUILD=1, DOCUMENTATION=89, GENERATED=8, HARNESS=71, INFRASTRUCTURE=40, PRODUCTION_CODE=668, SHARED_LIBRARY=115, TEST_CODE=402, VENDORED_TOOLING=13.

## 3. KIẾN TRÚC HIỆN TẠI (CURRENT ARCHITECTURE)

- Mô hình: 14 dịch vụ độc lập, database-per-service, giao tiếp bất đồng bộ qua Kafka, gateway hợp nhất xác thực và giới hạn tốc độ phân tán bằng Redis.
- Mô tả chi tiết: production-audit/architecture/current.md.

## 4. KIẾN TRÚC MỤC TIÊU (TARGET SYSTEM DESIGN)

- Mô tả chi tiết: production-audit/architecture/target.md; các quyết định kiến trúc tại production-audit/architecture/adrs/.

## 5. CÁC DESIGN PATTERN ĐÃ ÁP DỤNG (APPLIED DESIGN PATTERNS)

- Báo cáo chi tiết: production-audit/reports/design-pattern-adoption.md và production-audit/architecture/design-patterns.md.

## 6. CÁC THAY ĐỔI TÁI CẤU TRÚC (REFACTORING CHANGES)

- Báo cáo lộ trình: production-audit/reports/refactoring-roadmap.md.
- Cổng GATE-3 (DESIGN_PATTERNS): đạt.

## 7. PHÁT HIỆN ĐỒNG THỜI & ĐÚNG ĐẮN PHÂN TÁN (CONCURRENCY FINDINGS)

- Báo cáo đầy đủ: production-audit/reports/concurrency.md và production-audit/architecture/concurrency.md.
- Bộ kiểm thử đồng thời: PASS, 33 test.

## 8. TÍNH ĐÚNG ĐẮN KHO HÀNG (INVENTORY CORRECTNESS)

- Bộ kiểm thử kho hàng: PASS, 23 test.

## 9. TÍNH ĐÚNG ĐẮN HẠN MỨC KHUYẾN MÃI (PROMOTION QUOTA CORRECTNESS)


## 10. SAGA CHECKOUT & BỒI ĐỀN (CHECKOUT SAGA)

- Bộ kiểm thử Kafka/Outbox: PASS, 223 test.
- Cơ chế bồi đền được kiểm chứng trong production-audit/runs/run-20261118-a2b6e6fc/source-reviews/order-authz-compensation-fix.md.

## 11. KAFKA & TRANSACTIONAL OUTBOX

- Bộ kiểm thử: PASS.
- Bằng chứng rà soát: production-audit/runs/run-20261118-a2b6e6fc/source-reviews/kafka-outbox.md và kafka-safety-fix.md.

## 12. CẤU HÌNH STRIPE SANDBOX (STRIPE SANDBOX CONFIGURATION)

- Kết quả kiểm định cấu hình: BLOCKED.
- Chế độ khóa: không rõ.

## 13. KIỂM THỬ ĐẦU-CUỐI THANH TOÁN STRIPE THỰC TẾ (REAL STRIPE PAYMENT E2E)

- **Chưa xác minh được**: the configured webhook secret is a local placeholder, so the signature check would only prove that a self signed payload round trips.
- Không dùng khóa live-mode, không giả lập phản hồi Stripe, không dùng Stripe mock làm bằng chứng tích hợp thật.

## 14. XÁC MINH WEBHOOK & HOÀN TIỀN (WEBHOOK AND REFUND VERIFICATION)

- Logic xác minh chữ ký webhook được kiểm thử bằng lệnh harness stripe test-webhooks, ký HMAC-SHA256 theo đúng định dạng t/v1 của Stripe.
- Hoàn tiền: xem bộ kiểm thử payment — PASS.

## 15. XÁC MINH DOCKER COMPOSE (DOCKER COMPOSE VERIFICATION)

- Bộ kiểm thử tích hợp: PASS, 347 test, hạ tầng: .

## 16. KIẾN TRÚC KUBERNETES (KUBERNETES ARCHITECTURE)

- Kết quả kiểm định manifest: PASS.
  - K8S-01 MANIFEST_PARSING: PASS — every manifest parses
  - K8S-02 RESOURCE_COVERAGE: PASS — the required resource kinds are present
  - K8S-03 HEALTH_PROBES: PASS — every container declares startup, readiness and liveness probes
  - K8S-04 RESOURCE_BUDGET: PASS — every container declares cpu and memory requests and limits
  - K8S-05 SECURITY_CONTEXT: PASS — every workload declares a security context
  - K8S-06 SERVICE_SELECTORS: PASS — every service selects a declared deployment
  - K8S-07 AUTOSCALING: PASS — every autoscaler targets a declared deployment with bounded replicas and metrics
  - K8S-08 DISRUPTION_BUDGET: PASS — every multi replica deployment is protected by a pod disruption budget
  - K8S-09 SECRET_HYGIENE: PASS — no manifest embeds a credential literal
  - K8S-10 HOSTNAME_HYGIENE: PASS — no manifest points at a developer machine address
  - K8S-11 NAMESPACE_CONSISTENCY: PASS — every namespaced manifest uses shop-system
  - K8S-12 INGRESS_TLS: PASS — every ingress declares tls
  - K8S-13 SCHEMA_VALIDATION: PASS — kubeconform validated the manifests that have a published kubernetes schema

## 17. KẾT QUẢ HPA (HPA SCALING RESULTS)

- HPA được cấu hình cho gateway-service trong k8s/gateway-service.yaml.
- **Chưa xác minh cấp nhân tự động trên cụm thật**: không có cụm Kubernetes chạy được trong môi trường này. Lệnh harness k8s hpa-status trả về BLOCKED với lý do rõ ràng.

## 18. KẾT QUẢ KEDA (KEDA SCALING RESULTS)

- **Chưa triển khai**: không có ScaledObject KEDA nào trong k8s/. Đây là khoảng trống đã biết và đã ghi nhận trong sổ tay tác vụ.

## 19. HỒ SƠ CHIẾU TẢI BLACK FRIDAY (BLACK FRIDAY WORKLOAD PROFILES)

- Hồ sơ định nghĩa trong production-audit/harness/loadtesting/profiles.py và điều khiển bằng lệnh harness load.
- Các hồ sơ: baseline (A), ramp (B), spike (C), sustained-peak (D), flash-sale (E), promotion-storm (F), kafka-backlog (G), failure-during-peak (H), recovery (I), soak (J).
- Mọi hồ sơ bị chặn bởi ranh giới an toàn về trần tần suất, số VU, thời lượng và tổng số yêu cầu.

## 20. THÔNG LƯỢNG NỀN TẢNG VS ĐỈNH (BASELINE VS PEAK THROUGHPUT)

- baseline — kịch bản browse, trạng thái PASS: yêu cầu 18380, 108.01 req/s, lượt lặp 9180, p95 41.06 ms, tỷ lệ lỗi 0.00.
  - Kết luận nghiệp vụ: the business failure rate 0.0047 is within the limit 0.0500.
  - Số lần chạy đã lưu cho hồ sơ này: 7; bảng trên là lần chạy cuối cùng.
- flash-sale — kịch bản flash-sale, trạng thái FAIL: yêu cầu 123052, 723.79 req/s, lượt lặp 61526, p95 603.40 ms, tỷ lệ lỗi 0.67.
- promotion-storm — kịch bản promotion-storm, trạng thái FAIL: yêu cầu 76965, 452.61 req/s, lượt lặp 76965, p95 816.66 ms, tỷ lệ lỗi 0.69.
  - **Phép đo không hợp lệ**: máy chủ đo bị nhiễu, kết quả không dùng để kết luận.
- ramp — kịch bản full, trạng thái PASS: yêu cầu 52313, 227.30 req/s, lượt lặp 17421, p95 252.95 ms, tỷ lệ lỗi 0.00.
  - Kết luận nghiệp vụ: the business failure rate 0.0032 is within the limit 0.0500.
  - Số lần chạy đã lưu cho hồ sơ này: 2; bảng trên là lần chạy cuối cùng.
- recovery — kịch bản browse, trạng thái PASS: yêu cầu 5376, 48.79 req/s, lượt lặp 2683, p95 29.63 ms, tỷ lệ lỗi 0.00.
  - Kết luận nghiệp vụ: the business failure rate 0.0047 is within the limit 0.0500.
  - Số lần chạy đã lưu cho hồ sơ này: 2; bảng trên là lần chạy cuối cùng.
- spike — kịch bản full, trạng thái PASS: yêu cầu 43604, 256.33 req/s, lượt lặp 14468, p95 961.98 ms, tỷ lệ lỗi 0.01.
  - Kết luận nghiệp vụ: the business failure rate 0.0106 is within the limit 0.0500.
  - Số lần chạy đã lưu cho hồ sơ này: 2; bảng trên là lần chạy cuối cùng.

## 21. ĐỘ TRỄ & TỶ LỆ LỖI (LATENCY AND ERROR RATES)

- p50 không đo được, p90 không đo được, p95 không đo được, p99 không đo được, trung bình không đo được, lớn nhất không đo được.
- Tỷ lệ lỗi HTTP: không đo được (không đo được yêu cầu lỗi).
- Tỷ lệ kiểm tra nghiệp vụ đạt: không đo được.

## 22. BÃO HÒA TÀI NGUYÊN (RESOURCE SATURATION)

- Máy đo: ? lõi, tải trung bình None.
- HikariCP, CPU, bộ nhớ, Kafka lag và Redis: xem production-audit/reports/build-and-tests.md và trạng thái cổng kiểm định.

## 23. KẾT QUẢ FLASH-SALE TRÊN KHO HÀNG (FLASH-SALE INVENTORY RESULTS)

- Kịch bản flash-sale hướng mọi VU vào cùng một SKU hạn chế; bất biến kiểm tra sau mỗi lần chạy bằng lệnh harness load flash-sale.
- Kết quả chạy cụ thể: chưa có lần chạy flash-sale được lưu trong phiên này.

## 24. KẾT QUẢ PROMOTION-STORM (PROMOTION-STORM RESULTS)

- Kịch bản promotion-storm áp dụng cùng một mã giảm giá cho mọi VU; bất biến hạn mức được kiểm tra sau mỗi lần chạy.

## 25. KIỂM THỬ KHÔI PHỤC & LỖI (RECOVERY AND FAILURE TESTING)

- Hồ sơ recovery và failure-during-peak đã được hiện thực trong harness.
- Chi tiết: production-audit/reports/e2e.md và production-audit/state/performance-baselines.json.

## 26. BẰNG CHỨNG TRUY VẾT PHÂN TÁN (DISTRIBUTED TRACE EVIDENCE)

- Tempo và OpenTelemetry Collector đang chạy trong cụm Compose; cấu hình tại config/observability/tempo.yaml và config/observability/otel-collector-config.yaml.
- Cổng kiểm định quan sát: PASS.

## 27. NHẬT KÝ TẬP TRUNG (CENTRALIZED LOGS)

- Loki và Grafana Alloy đang chạy; cấu hình tại config/observability/loki.yaml và config/observability/alloy/config.alloy.

## 28. BẢNG ĐIỀU KHIỂN GRAFANA (GRAFANA DASHBOARDS)

- Bảng điều khiển fleet-overview được cấu phục tại config/observability/grafana/dashboards/fleet-overview.json.

## 29. KẾT QUẢ CẢNH BÁO TELEGRAM CI (CI TELEGRAM ALERT RESULTS)

- Chưa có lần chạy kiểm thử giao hàng CI trong phiên này.
- Đường dẫn CI: .github/workflows/ci.yml (job notify-telegram, điều kiện failure()), kịch bản scripts/notify-telegram.sh.

## 30. KẾT QUẢ CẢNH BÁO TELEGRAM RUNTIME (RUNTIME TELEGRAM ALERT RESULTS)

- QA-ALERT-04: PASS — identical alerts must collapse into a single grouped alert 
- Cấu hình: config/observability/alertmanager/alertmanager.yml.template, quy tắc config/observability/alertmanager/rules/runtime-alerts.yml.

## 31. SẴN SÀNG CẢNH BÁO PRODUCTION (PRODUCTION ALERTING READINESS)

- Trạng thái kiểm định cấu hình cảnh báo: BLOCKED.
- Đường dẫn cấu hình: Prometheus đánh giá quy tắc trong config/observability/alertmanager/rules, Alertmanager gom nhóm và chống lặp trong config/observability/alertmanager, tuyến giao hàng CI là scripts/notify-telegram.sh và tuyến runtime là Telegram Bot API.
- **Giao hàng thật trong môi trường production chưa được xác minh** vì không có quyền truy cập production và không có thông tin xác thực bot.

## 32. BUILD & KIỂM THỬ HỒI QUY (BUILD AND REGRESSION TESTS)

- alerts: STALE — 0 failed and 1 blocked checks recorded against an earlier source revision
- baseline: STALE — 1913 test recorded against an earlier source revision
- build: STALE — recorded against an earlier source revision
- comments: STALE — recorded against an earlier source revision
- concurrency: STALE — 33 test recorded against an earlier source revision
- concurrency_inventory: STALE — 23 test recorded against an earlier source revision
- e2e: STALE — end to end run state FAILED recorded against an earlier source revision
- edge: STALE — 37 test recorded against an earlier source revision
- gateway: STALE — 136 test recorded against an earlier source revision
- happy: STALE — 1878 test recorded against an earlier source revision
- integration: STALE — 347 test recorded against an earlier source revision
- k8s: STALE — 0 kubernetes manifest checks failed recorded against an earlier source revision
- kafka: STALE — 223 test recorded against an earlier source revision
- operations: STALE — 7 of 7 observability checks passed recorded against an earlier source revision
- payment: STALE — 261 test recorded against an earlier source revision
- performance: STALE — 1656 test recorded against an earlier source revision
- security: STALE — 153 test recorded against an earlier source revision
- unit: STALE — 1878 test recorded against an earlier source revision
- Lệnh build: ./mvnw -B -ntp -T 1 clean verify, mã thoát 0, ? test, ? module, thời gian 876.197 giây.

## 33. PHÁN QUYẾT QA ĐỘC LẬP (INDEPENDENT QA VERDICTS)

- PAY-15-30-psp-integration-review-2.md (41084 byte).
- PAY-15-30-psp-integration-review-3.md (13571 byte).
- PAY-15-psp-integration-review.md (33342 byte).
- final-change-review.md (43648 byte).
- high-batch-acceptance-criteria.md (88525 byte).
- orchestrator-change-review.md (62068 byte).

## 34. COMMIT & KẾT QUẢ PUSH (GIT COMMITS AND PUSH OUTCOMES)

- b14dde9282e0 —  — push: .
- b14dde9282e0 —  — push: .
- cc25eb8b2de9 —  — push: .
- cc25eb8b2de9 —  — push: .
- 7a3f72e92f79 —  — push: .
- 7a3f72e92f79 —  — push: .
- ff6e578e5ecc —  — push: .
- ff6e578e5ecc —  — push: .

## 35. TỒN ĐỌNG CÒN LẠI (REMAINING BLOCKERS)

### 35.1 Mức HIGH

| Mã | Mức | Nội dung |
| --- | --- | --- |
| CAT-05 | HIGH | No currency on any money field in the fleet |

### 35.2 Mức MEDIUM

| Mã | Mức | Nội dung |
| --- | --- | --- |
| CAT-03 | MEDIUM | Mutable, non-volatile pagination state on a singleton scheduled bean |
| CAT-04 | MEDIUM | Money stored as an IEEE-754 double in the search index |
| CAT-08 | MEDIUM | Reindex lock is in-process only |
| CAT-09 | MEDIUM | Product outbox claim lease piggy-backs on the `updated_at` audit column |
| CAT-10 | MEDIUM | `FAILED` outbox rows are terminal in product, rating and promotion (no replay, no DEAD aging) |
| CAT-11 | MEDIUM | No concurrency admission control on the media decode path |
| CAT-13 | MEDIUM | No Elasticsearch mapping-version gate |
| CAT-16 | MEDIUM | Reservation gates are an if/else chain |
| CAT-17 | MEDIUM | `FAILED` media outbox rows are re-published every 2 seconds for 7 days |
| CAT-18 | MEDIUM | Inconsistent event-naming convention across producers |
| CAT-19 | MEDIUM | The rating aggregate snapshot can be stale under concurrent reviews |
| CAT-23 | MEDIUM | No retention purge for the `notifications` table |
| GW-06 | MEDIUM | Two to three serialised Redis round trips per request, and rejected traffic still burns the fleet-wide budget |
| GW-08 | MEDIUM | `GlobalRateLimitFilter` and `AdminIpAllowlistFilter` share the same order value; the documented "IP allowlist first" contract is not enforced |
| GW-09 | MEDIUM | The lazy JWT decoder performs a blocking OIDC discovery + JWKS fetch **on the event loop** at first decode |
| GW-10 | MEDIUM | The "literal IP" guard in the admin allowlist accepts non-IP strings, so `InetAddress.getByName` performs a DNS lookup on the event loop |
| GW-11 | MEDIUM | The Kubernetes deployment points at a Redis that no manifest in this repo defines, sets no Redis password, and leaves the admin allowlist inactive |
| GW-12 | MEDIUM | The edge limiter's two Redis keys have no hash tag ⇒ `CROSSSLOT` failure in Redis Cluster |
| GW-13 | MEDIUM | Unauthenticated requests are not metered by any gateway limiter (401 is returned before the limiters run) |
| GW-26 | MEDIUM | Duplicate/ambiguous `X-RateLimit-Remaining` header (merged with GW-07 above as the header part) |
| GW-27 | MEDIUM | No circuit breaker and no bulkhead anywhere in the gateway |
| GW-29 | MEDIUM | INFO — Scope/bypass asymmetry between the IP allowlist and the edge rate limiter |
| K8S-24 | MEDIUM | No service account is declared for the platform workloads |
| K8S-25 | MEDIUM | The Kubernetes deployment set covers only a subset of the fourteen platform services |
| KAF-03 | MEDIUM | `BaseKafkaConsumer` / `KafkaProperties` javadoc contradicts the code on poison handling |
| KAF-04 | MEDIUM | `shop.kafka.retry.*` is bindable but has zero effect |
| KAF-05 | MEDIUM | product-service's media listener has no error handler, hence no DLT |
| KAF-06 | MEDIUM | No topic provisioning anywhere in the repo |
| KAF-07 | MEDIUM | product-service and payment-service use the generic `updated_at` column as the claim lease |
| KAF-08 | MEDIUM | search-service's DLT depends on an optional bean and fails open |
| KAF-09 | MEDIUM | notification-service's dedup key silently depends on the event id being a parseable UUID |
| KAF-20 | MEDIUM | The DLT replay endpoint is disabled in every service: the DLT is a write-only bucket |
| OBS-04 | MEDIUM | Expose the compose, load and stripe harness command families required by the operating contract |
| OI-03 | MEDIUM | A failure of the saga's final transaction leaks every stock reservation |
| OI-04 | MEDIUM | `idempotencyService.complete()` failing after the order is committed frees the key and lets a retry create a second order |
| OI-05 | MEDIUM | The duplicate-checkout protection is opt-in: a checkout without an `Idempotency-Key` header gets none |
| OI-06 | MEDIUM | The expiry sweep publishes a spurious `inventory.released.v1` for reservations it did not claim |
| OI-07 | MEDIUM | The reconciliation scheduler bypasses the order state machine |
| OI-08 | MEDIUM | The reconciliation auto-confirm bypasses the captured-payment gate |
| OI-09 | MEDIUM | `Cart` has no `@Version`: the cart subtotal is a lost update |
| OI-10 | MEDIUM | `CompensationFailureService.retryPending` performs up to 50 sequential HTTP calls inside a single transaction |
| OI-11 | MEDIUM | `InventoryServiceImpl.delete()`'s active-reservation guard is a check-then-act |
| OI-12 | MEDIUM | The refund gateway is called before the state is persisted: a DB failure makes the return re-reviewable and the refund repeatable |
| OI-13 | MEDIUM | The `productPrice` cache is never invalidated: up to 10 minutes of stale checkout pricing |
| OI-14 | MEDIUM | The return flow's cumulative-refund and pending-return checks are unlocked read-modify-write |
| OI-28 | MEDIUM | INFO — YAML/code drift on three scheduler knobs |
| OI-29 | MEDIUM | INFO — `ReturnStatus.APPROVED` is a dead state and the return flow has no transition table |
| RES-01 | MEDIUM | `spring-boot-starter-aop` is pinned to a 4.0.0-M2 milestone while the project runs Boot 4.1.1, and the artifact was renamed in Boot 4. |
| RES-02 | MEDIUM | `spring-boot-starter-web` is used in three modules although Boot 4 deprecated it in favour of `spring-boot-starter-webmvc`. |
| RES-03 | MEDIUM | Jackson 2 and Jackson 3 are both on the compile classpath while Boot 4 makes Jackson 3 the preferred JSON library; 93 source files still use `com.fasterxml.jackson`, and the hand-b |
| RES-04 | MEDIUM | The Kafka client (4.2.1) is two minor lines ahead of the broker (3.9.0) managed by Boot 4.1.1. |
| RES-05 | MEDIUM | No Kafka transaction is configured, so the system cannot use Kafka's exactly-once delivery; the exactly-once effect for the database write is obtained with the outbox instead. |
| RES-06 | MEDIUM | Two listener container factories have no dead-letter recoverer, so a poisoned record is logged and dropped. |
| RES-07 | MEDIUM | `retries = 3` on the producer is far below the Kafka 3.9 default of `2147483647`. |
| RES-08 | MEDIUM | The outbox claim resolves a single `MIN(id)` row, so `SKIP LOCKED` serialises claim throughput across relay instances. |
| RES-09 | MEDIUM | `jakarta.persistence.lock.timeout` is not declared on one pessimistic-lock query, so the provider default applies. |
| RES-10 | MEDIUM | `@Transactional(noRollbackFor = BusinessException.class)` suppresses the documented default rollback, and `BusinessException` is a `RuntimeException`. |
| RES-11 | MEDIUM | Five `REQUIRES_NEW` sites against a 20-connection Hikari pool while virtual threads are switchable on. |
| RES-12 | MEDIUM | A singleton bean holds mutable state (`currentPage`) mutated from a scheduled method. |
| RES-13 | MEDIUM | Monitor-based synchronization in main code would pin a virtual-thread carrier if virtual threads were enabled. |
| RES-14 | MEDIUM | Configuration is injected into singletons through non-final `@Value` fields. |
| RES-15 | MEDIUM | `shop.security.expected-audiences` is empty by default, so no audience validation runs; only the `azp` client check is active. |
| RES-16 | MEDIUM | Client roles (`resource_access`) are not mapped — only `realm_access.roles` becomes an authority. |
| RES-17 | MEDIUM | The webhook verifier accepts a second, legacy signature scheme (bare 64-hex HMAC over the body) when the header has no `t=` component. |
| RES-18 | MEDIUM | Shipped configuration files carry usable secret defaults (`changeme`, `admin`). |
| RES-19 | MEDIUM | The Stripe webhook is processed synchronously before the 2xx. |
| RES-20 | MEDIUM | The idempotency key is client-supplied (not a server-generated UUID) and is reused across the local and the Stripe layer. |
| RES-21 | MEDIUM | Deprecated Testcontainers 1.x packages are used in 21 test files. |
| RES-22 | MEDIUM | The integration-test Kafka image differs from the deployed broker. |
| RES-23 | MEDIUM | The Elasticsearch client declared at the root (9.4.5) is not the one used, and neither matches the 8.15.0 server. The 9.x line also renames the transport package, so honouring the  |
| SEC-04 | MEDIUM | No `aud` validation fleet-wide; a single shared `azp` allow-list for every service |
| SEC-05 | MEDIUM | The platform's own login is the deprecated ROPC grant, on a public client, with no MFA binding |
| SEC-06 | MEDIUM | Token and session lifetimes are not pinned in the realm and are not asserted anywhere |
| SEC-07 | MEDIUM | Password-reset accepts a weaker password than registration |
| SEC-08 | MEDIUM | The Keycloak error body is relayed to the client on non-401 auth failures |
| SEC-09 | MEDIUM | The shipping webhook verifier has no replay window |
| SEC-10 | MEDIUM | CI runs no SCA, no SAST and no secret scan; the OWASP plugin is bound to a phase CI never reaches |
| SEC-11 | MEDIUM | The `verified-purchase` client discriminator is the fleet-wide `azp` list, and `azp` is absent for user tokens |
| SEC-12 | MEDIUM | Gateway edge permits `/api/v1/carts/**`, `/api/v1/ratings/**` and `/api/v1/medias/**` anonymously |
| SEC-27 | MEDIUM | `auth-service` public-path rules are method-agnostic |
| SEC-28 | MEDIUM | Seeded credential pairs are documented in tracked markdown |
| SL-03 | MEDIUM | `LazyJwtDecoder` builds **N** decoders under concurrent first-request load (javadox guarantee is false) |
| SL-04 | MEDIUM | `LazyJwtDecoder` performs a synchronous OIDC discovery on the **request** thread with no negative cache |
| SL-06 | MEDIUM | `DeadLetterReplayService.replay()` commits the offset of a record it **failed** to republish |
| SL-07 | MEDIUM | `DeadLetterReplayService` blocks forever on a hung broker and loses trace context |
| SL-09 | MEDIUM | `BaseKafkaConsumer` javadoc contradicts its code about DLT routing |
| SL-10 | MEDIUM | `JsonKafkaDeserializer` throws `RuntimeException`, which defeats the `ErrorHandlingDeserializer` DLT contract |
| SL-11 | MEDIUM | `JsonKafkaSerializer` silently degrades a failed serialization to a **non-JSON** payload |
| SL-12 | MEDIUM | `KafkaProperties.buildConsumerProperties()` leaves critical consumer settings to framework defaults |
| SL-13 | MEDIUM | `KafkaProperties.ConsumerConfig.groupId` default is a **fleet-wide** shared identity |
| SL-17 | MEDIUM | `ApiExceptionHandler` echoes the raw exception message for two request-shape errors |
| SL-18 | MEDIUM | `KeycloakTokenClient.verifyCredentials()` cannot distinguish "wrong password" from "Keycloak is down" |
| SL-19 | MEDIUM | `KeycloakAdminClient.updateUserEnabled()` can leave live sessions after a successful disable |
| SL-20 | MEDIUM | `KeycloakAdminClient.buildUserPayload()` hard-codes `emailVerified=true` |
| SL-22 | MEDIUM | `BoundedAsyncAuditEventWriter` silently discards audit events under back-pressure |
| SL-25 | MEDIUM | `ServiceCallerGuard` lets any holder of a realm ADMIN/MANAGER role bypass the client check entirely |
| SL-26 | MEDIUM | `AuthenticatedUser.from(Jwt)` takes its authorities from the ambient context, not from the argument |
| SL-29 | MEDIUM | `S3ObjectStorageService.download()` buffers up to 50 MB per call |
| SL-30 | MEDIUM | Five `CommonProperties` toggles are dead configuration |
| SL-38 | MEDIUM | the CORS source bean cannot be overridden |
| SL-39 | MEDIUM | the CORS code default is a wildcard origin |
| SL-40 | MEDIUM | the body masker only understands JSON |
| SL-41 | MEDIUM | the shared defaults activate the `dev` profile |

### 35.3 Mức LOW

- 77 tác vụ LOW đang mở, xem production-audit/reports/remaining-tasks.md.

## 36. CÁC PHÁT BIỂU ĐÃ XÁC MINH & CHƯA XÁC MINH (VERIFIED VS UNVERIFIED CLAIMS)

### 36.1 ĐÃ XÁC MINH BẰNG BẰNG CHỨNG

- Rà soát mã nguồn đầy đủ: 1366/1407 tập tin.
- Chính sách không chú thích: PASS.
- Build reactor: bằng chứng đã cũ.
- Bất biến nghiệp vụ dưới tải: chưa chạy với 0 bất biến kiểm tra.

### 36.2 CHƯA XÁC MINH

- Tích hợp Stripe sandbox thật: BLOCKED — khóa sk_test_ bị Stripe từ chối và bí mật ký webhook là giá trị cục bộ.
- Giao hàng Telegram thật: chưa có thông tin xác thực bot trong môi trường này.
- Cấp nhân tự động HPA và KEDA trên cụm thật: không có cụm Kubernetes chạy được.
- Thông lượng thanh toán cấp nhà cung cấp: phụ thuộc giới hạn tần suất của Stripe và cấu hình tài khoản.
- Giao hàng cảnh báo trong production: chỉ có thể xác minh bằng sự kiện production được cho phép.

## 37. QUYẾT ĐỊNH SẴN SÀNG TRIỂN KHAI SẢN XUẤT (PRODUCTION READINESS DECISION)

- **Quyết định**: CHƯA SẴN SÀNG TRIỂN KHAI SẢN XUẤT.
- Các cổng chưa đạt: GATE-1, GATE-4, GATE-5, GATE-6, GATE-7, GATE-8, GATE-9, GATE-10, GATE-12, GATE-13, GATE-14, GATE-15, GATE-16, GATE-17, GATE-18, GATE-21, GATE-22, GATE-23, GATE-24
- Số tác vụ HIGH đang mở: 1; MEDIUM: 103; LOW: 77.
- Trạng thái tổng hợp tác vụ: BLOCKED=3, DISCOVERED=8, FAILED_VERIFICATION=0, IMPLEMENTED=0, IN_PROGRESS=0, NORMALIZED=0, READY=173, REOPENED=0, REQUIREMENT_CONFLICT=0, VERIFIED=84, VERIFYING=0.
- Báo cáo được sinh tự động tại /Users/tonminh-mac/IdeaProjects/untitled5/production-audit/reports/final-production-readiness-report.md.

