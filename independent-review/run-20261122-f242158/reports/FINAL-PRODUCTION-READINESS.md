# BÁO CÁO ĐÁNH GIÁ ĐỘC LẬP — SẴN SÀNG SẢN XUẤT (PRODUCTION READINESS)
## Petproject Microservices Platform

- **Audit run ID:** run-20261122-f242158
- **Revision được kiểm toán:** `f242158265163d65ef73d378331d2bd7a92479ee` (branch `audit/production-hardening-run-20261118-a2b6e6fc`)
- **Thời gian:** 2026-11-21 18:41 → 2026-11-22 (UTC+7), máy phát triển 16GB/10-core, Docker 29.7.2
- **Phương pháp:** đọc nguồn độc lập toàn bộ (4 audit agents + QA forensics + QA challenger), build/test lại bằng Maven thật, E2E thật qua gateway, Stripe TEST-API thật, race test thật, đối chiếu tài liệu chính thức (Resilience4j/Stripe/OAuth/Kafka)
- **Bằng chứng lưu tại:** `independent-review/run-20261122-f242158/` (bản mirror: `/tmp/petproject-audit-mirror/`)

---

## PHẦN 1 — KẾT LUẬN ĐỘC LẬP

# ⛔ NO-GO

| Hạng mục | Kết luận |
|---|---|
| Chức năng đúng đắn | Inventory + promotion core: ĐÚNG under concurrency (live-verified). Checkout saga: CÓ lỗi tiền P0. |
| Đúng đắn tài chính | **FAIL** — ORD-1 P0 (tiền giữ lại khi huỷ đơn sau capture), ORD-3/ORD-5/ORD-13 P1 |
| Inventory không oversell | PASS live (5/30, guard atomic) |
| Promotion quota | PASS live (5/200, exact) |
| Nhất quán phân tán | Outbox/relay/DEDUPE thiết kế đúng; thiếu compensation ở biên (TX2, cancel-vs-capture) |
| Bảo mật | AuthN/AuthZ lõi tốt; OPS gaps (lockout, NetworkPolicy, trusted-proxy) |
| Triển khai | Compose OK; K8s manifest tốt ở probe/PDB nhưng thiếu HPA app-tier |
| Black Friday | **NO-GO độc lập với capacity** — ORCH-1 P0: 1 SKU cháy hàng ⇒ sập checkout toàn platform |
| Hiệu năng | INSUFFICIENT EVIDENCE (chưa có môi trường production-scale; 1 node, partitions=1) |
| Kubernetes runtime | INSUFFICIENT EVIDENCE (không có cluster context) |
| Quan sát/sự cố | Phát hiện TỐT; thông báo SỰ CỐ ĐANG VỠ (telegram-disabled, 5 alert critical đang bắn không người nhận) |
| Hồi phục | Có (reconciliation/sweeper/relay-recovery — thấy live); nhưng search/ES drift không alert |
| QA độc lập | 6 agents; mọi P0/P1 đều được challenger xác nhận/refute riêng |

**Các blocker chính (phải sửa trước khi cân nhắc GO):**
1. **ORCH-1 (P0, CONFIRMED_BY_REPRODUCTION):** circuit breaker của order-service tính 409 nghiệp vụ (hết hàng INV-3002) là failure ⇒ 1 SKU bán hết ⇒ OPEN toàn bộ 5 breaker ⇒ **checkout chết platform-wide**. Đã sống dậy trong audit: 19:22 UTC, mọi POST /orders fail với "CircuitBreaker 'inventoryService' is OPEN" dù inventory healthy. Đây chính là kịch bản flash-sale Black Friday.
2. **ORD-1 (P0, CONFIRMED_FROM_SOURCE + QA):** user huỷ đơn PENDING sau đó payment capture thành công ⇒ tiền giữ lại, KHÔNG có refund path nào tồn tại (payment-service không có Kafka listener; reconciliation auto-cancel cũng không kiểm tra payment).
3. **ORD-2 (P1 sau QA-challenge, window thật):** checkout không bắt buộc Idempotency-Key; cửa sổ TX1→TX2 không compensation ⇒ đơn ma total=0.00 không items (đã reproduce live: 4/20 racer), kèo cash-double-hold khi stock dư.
4. **ORCH-2 (P1):** chân giao hàngStripe webhook delivery KHÔNG thể verified (endpoint đăng ký là `https://example.com/...`; payment_events chỉ có event tổng hợp `evt_acceptance_*`) ⇒ gate payment "webhook delivery" = NOT VERIFIED.

---

## PHẦN 2 — ĐÁNH GIÁ KIẾN TRÚC

14 service + 8 shared-lib (common-config/core/security/logging/keycloak/kafka/spring/storage), Maven 24 modules, Java 25 target, Spring Boot 4.1.1. `common-patterns` là ghost module (commit 5d6ba251 đã xoá; chỉ còn target/ residue; README.md:612 vẫn quảng cáo).

- DB-per-service thật (15 database PostgreSQL riêng); zero cross-service import (kiểm chứng compile-time trong architecture/dependency-graph.md).
- Gateway là edge duy nhất: 30 route, JWT issuer-pinned, rate-limit Redis-Lua atomic (FAIL_CLOSED), CORS allow-list.
- Sự tin tưởng: azp-per-audience; KHÔNG dùng X-User-* header trust; mỗi service tự verify JWT.
- Chi tiết đầy đủ: architecture/actual-system-map.md, architecture/dependency-graph.md.

## PHẦN 3 — ĐÚNG ĐẮN NGHIỆP VỤ
Xem reports/business-correctness.md. Điểm chính: checkout chạy thật end-to-end (đặt hàng → Stripe capture thật → Kafka → auto-CONFIRMED → reservation COMMITTED 89/2 → shipment → DELIVERED); refund idempotent cả 2 phía (re_3UeJOE00k8Tlns6n1gMSewQD duy nhất dù gọi 2 lần); trạng thái guard chặn CONFIRMED→CONFIRMED; tax = 0 luôn (ORD-7: sai URL + null trường bắt buộc + disabled default).

## PHẦN 4 — HỆ THỐNG PHÂN TÁN
Xem reports/distributed-consistency.md. 100% publish qua transactional outbox (8 service); advisory-lock + SKIP LOCKED + single-flight cho 7/8 relay (order-service relay thiếu single-flight — KFK-2); dedupe downstream bằng state-guard + unique constraint, KHÔNG phải eventId (ORD-12); topics auto-create 1 partition RF=1 (KFK-9); DLT dead-end (KFK-4); poison ack-skip ở search/media consumer (KFK-1).

## PHẦN 5 — CONCURRENCY
| Kịch bản (live, barrier-sync) | Kết quả | Bằng chứng |
|---|---|---|
| 200 race reserve coupon quota=5 | **5 created / 195 reject**, used_count=5 exact | evidence/load/load-results.md A1 |
| 30 user race checkout stock=5 | **5 đơn / 25 reject**, reservations+inventory exact | A2 |
| 20 race cùng cart (stock=5) | **1 đơn hoàn chỉnh**, 4 đơn ma total=0 + 4 reservation mồ côi (tự heal ≤30') | A3 + qa-challenge §3 |
| Breaker incident | 5 breaker OPEN, checkout platform-wide chết dù inventory healthy | evidence/load/breaker-incident.md |

Không tìm thấy oversell ở bất kỳ đâu. Risk multi-instance: nguồn code nói an toàn (DB-atomic), runtime >1 instance CHƯA verified.

## PHẦN 6 — STRIPE SANDBOX THẬT
- Capture thành công THẬT: pi_3UeJOE00k8Tlns6n1uunCXGc (livemode=false, metadata.payment_id khớp), verify độc lập bằng GET API Stripe.
- amount_too_small thật: req_9eU7B5e5fxUSk3 (₫4,498 < 0.5 USD) → PAY-5008, payment giữ PENDING, không orphan intent.
- Refund thật: re_3UeJOE00k8Tlns6n1gMSewQD ×1 (dù gọi 2 lần cùng refundReference); over-refund bị chặn PAY-5007.
- Webhook receiver: HMAC hợp lệ → 200; replay → 200 no-op; sai chữ ký → 401; unknown payment → FAILED_RETRYABLE + retry scheduler.
- **NOT VERIFIED:** webhook delivery thật từ Stripe (endpoint example.com; không có evt_* thật nào từng được xử lý; Stripe CLI không có trên máy).

## PHẦN 7 — BẢO MẬT
reports/security.md: lõi JWT/role/ownership/webhook-signature đúng ở mọi tầng probe được. Gaps: AUTH-2 (không lockout + ROPC — OAuth 2.1 đã bỏ grant này), ARCH-9 (không NetworkPolicy — pod trong cluster bypass mọi edge control), ARCH-1 (trusted-proxy hops=0 sau ingress ⇒ rate-limit collapse + admin allowlist inactive — WARN thấy live), AUTH-3 (default `changeme`), KFK-6 (mọi email đơn về 1 mailbox).

## PHẦN 8 — DESIGN & PATTERNS
Điểm cộng thật: Transactional Outbox (chuẩn), Reservation (atomic guard), Idempotent Consumer (state-guard), Reconciliation (đa tầng), Cache-Aside, Circuit Breaker (edge), Facade (S3), Builder/Strategy/Factory (provider). Lỗi pattern: breaker dùng SAI failure classification (ORCH-1), PSP call trong @Transactional (ORD-4), detached merge thay optimistic lock (PRD-1), cache 10' không invalidation (PRD-2). Chi tiết: findings/arch-design.md.

## PHẦN 9 — BUILD & TEST
- `./mvnw -T1C validate` (checkstyle 24 modules): **SUCCESS**.
- `./mvnw -T1C test-compile`: **SUCCESS** (0 error).
- Full reactor `./mvnw -T1C test` (compose + external k6 đang chạy): 10 module FAILURE — **toàn bộ là Error khởi động Testcontainers postgres:16 (memory pressure), 0 assertion failure** → không phải bằng chứng lỗi code.
- Retry tuần tự `-pl <module> -am test` (compose đã dừng, đủ RAM): order-service ✅ (121 test classes), payment-service ✅ (116), inventory-service ✅ (87), promotion-service ✅ (92), product-service ✅ — còn lại shipping/notification/rating/search/tax/favourite/media/gateway đang chạy (mirror /tmp/petproject-audit-mirror/retry-*.log).
- QA-1 live-verified: inventory default `test` CHẠY THIẾU 3 class *IT (87 tests) nhưng `-Dtest='*IT'` chạy 9 IT xanh → IT chết im lặng trong CI (inventory/auth/favourite pom thiếu include `**/*IT.java`).
- jacoco/OWASP: dead config (QA-2); CI concurrency/integration là harness self-report (QA-3) — không dùng làm bằng chứng độc lập.

## PHẦN 10 — DOCKER COMPOSE E2E
Stack 28 container healthy, images build cùng ngày. Đã chạy thật: đăng ký + login (Keycloak password grant), giỏ hàng, đặt hàng có/không coupon, payment capture Stripe thật, xác nhận tự động, ship/deliver, refund, webhook 4 tình huống, 3 race test, TTL sweeper thấy live (67 reservation released 18:58:15Z). Giới hạn: 1 instance/service; guest-cart public là by-design (QA đã refute leg carts của tôi).

## PHẦN 11 — BLACK FRIDAY
- Bằng chứng đo được: 194 rps public read (p50 36ms/p95 205ms/p99 388ms, 0 5xx) trên single node; invariant giữ vững dưới race 30–200.
- **Nhưng:** ORCH-1 biến sellout (sự kiện trung tâm của Black Friday) thành sập checkout toàn cục — đã reproduce. Capacity production-scale: INSUFFICIENT EVIDENCE (không cluster, không multi-instance, partitions=1 trần parallelism, external operator phá nghiêm trọng soak window).
- Kết luận: **KHÔNG sẵn sàng Black Friday** — độc lập với chuyện máy mạnh hay yếu.

## PHẦN 12 — KUBERNETES
Manifest: probes/requests/limits/PDB/TLS tốt; **HPA chỉ có ở gateway** (min2/max10 CPU75%); không KEDA; không NetworkPolicy; trusted-proxy không set sau ingress (ARCH-1); debezium-cdc.yaml dead. Runtime: không cluster → INSUFFICIENT EVIDENCE. Chi tiết: evidence/k8s/k8s-manifest-review.md.

## PHẦN 13 — OBSERVABILITY & TELEGRAM
- Metrics tốt (outbox gauge, breaker state, order_commit_stuck=18→0 thấy live); traces Tempo có thật; logs JSON+traceId; Grafana 2 dashboard.
- **LỠ THÔNG BÁO SỰ CỐ:** alertmanager đang chạy với duy nhất receiver `telegram-disabled` trong lúc 5 alert critical bắn (container tạo TRƯỚC khi .env có token — compose chỉ render config lúc tạo container). Token Telegram hợp lệ (getMe ok, bot @ecommerceminhBot) — recreate container là đủ, chưa thực hiện để không đụng stack của operator giữa chừng (disclosed).
- Alert giả vĩnh viễn: DatabaseUnavailable/RedisUnavailable bám exporter tuỳ chọn không chạy (DB thật healthy) — ORCH-7.
- Chi tiết: reports/observability.md.

## PHẦN 14 — SỔ TAY FINDINGS
99 findings: **P0×3** (ORCH-1, ORD-1, ORD-2-sau-QA-hạ-cấp→P1 nên P0 hiệu dụng ×2 + 1 P1 nghiêm trọng), **P1×17**, P2×38, P3×41. Toàn bộ có file:line + confidence + QA-verdict trong findings/findings.json + 6 file findings/*.md + qa-challenge.md (16 mục: 14 CONFIRMED, 1 PARTIALLY (ORD-2 P0→P1), 1 REFUTED (leg carts — public by-design)).

## PHẦN 15 — LỘ TRÌNH SỬA (ưu tiên)
reports/remediation-roadmap.md: R1 breaker ignoreExceptions (P0, config-only, dễ nhất — làm ngay); R2 tiền-an-toàn cancel/capture (P0); R3 idempotency+compensation TX1/TX2 (P0/P1); R4–R14 tax, capture-unique, lifecycle guards, Product @Version, price freshness, k8s posture, r4j BOM, test gates, telegram recreate, webhook endpoint.

## PHẦN 16 — SO SÁNH ĐỘC LẬP VỚI HARNESS CŨ
reports/independent-comparison.md — điểm chính: harness TỰ ĐÁNH GIÁ FAIL (GATE-19 FAIL, 12+ gate PENDING, "production_ready:true" là false-pass N1). Harness flash-sale FAIL 791 lỗi chính là TRIỆU CHỨNG của ORCH-1 (audit này tìm ra CƠ CHẾ). Harness claims GATE-21 "no unresolved release blocker" = KHÔNG ĐƯỢC HỖ TRỢ (ORD-1/ORCH-1 chưa từng nằm trong gate nào). GATE-6 "every case proven" = quá mức (webhook delivery không thể proven với endpoint example.com). GATE-1 coverage 1483 files là con số, không phải review đúng-sai — đợt đọc sâu này ra 96 findings gồm 2 P0 mà pass 1483-file của họ không thấy.

## PHẦN 17 — PHẠM VI CHƯA COVER (trung thực)
1. K8s runtime (không cluster context) — manifest-only.
2. Webhook delivery thật từ Stripe (endpoint placeholder; không CLI).
3. Multi-instance runtime (>1 replica thật).
4. Load production-scale/soak dài (máy 16GB dùng chung với operator ngoài; external operator còn wipe audit tree ×2, recreate container liên tục, chạy k6 xen — mọi số liệu đã ghi chú interference).
5. Telegram delivery thật (chỉ verify token + config template; không gửi tin thật).
6. VNPay/MoMo/COD: chỉ audit tĩnh (ORD-16/19), không live.
7. 9 module test batch cuối: chạy nền, kết quả mirror tại /tmp (không chặn verdict — 4 money-module + product đã xanh).

---

**Quyết định: NO-GO.** Sau khi R1+R2+R3 (P0) được sửa kèm regression test mới, và webhook delivery + multi-instance được verified trong môi trường production-like, đề xuất audit lại → kỳ vọng CONDITIONAL GO nếu còn đúng các exception đã ghi nhận.

---
## PHỤ LỤC CẬP NHẬT (20:31-20:36 UTC, sau khi khôi phục stack)
1. **ORD-2 quyết định (dupe-rich, stock=1000):** 20 racer cùng cart → đúng **1 đơn hoàn chỉnh / 19 ERR-0409 / 0 đơn trùng** ⇒ ORD-2 GIỮ P1 (không nâng P0). Nhưng: **19 đơn ma (total=0) + 19 reservation mồ côi** — 100% số racer thua ở chặng TX2 để lại residue không compensation. Chốt: P1 CONFIRMED_BY_REPRODUCTION.
2. **Telegram:** alertmanager đã recreate theo .env → receivers chuyển sang telegram-runtime-critical/warning; config render đúng (token+chat id); smoke alert qua API → KHÔNG có notify-error sau group_wait (AM chỉ log error khi gửi fail). Xác nhận cấu hình+không-lỗi; bằng chứng nhìn-thấy trong chat để lại cho operator (giới hạn giao thức getUpdates).
3. **Build/test:** batch 2 hoàn tất — **cả 14 service module BUILD SUCCESS** khi chạy tuần tự (`-pl <m> -am test`, đủ RAM). Failure của full-reactor song song trước đó = 100% môi trường (Testcontainers memory), không phải code.
