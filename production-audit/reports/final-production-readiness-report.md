# BÁO CÁO ĐÁNH GIÁ VÀ NGHIỆM THU MỨC ĐỘ SẴN SÀNG TRIỂN KHAI SẢN XUẤT
## HỆ THỐNG KIẾN TRÚC VI DỊCH VỤ THƯƠNG MẠI ĐIỆN TỬ PETPROJECT PLATFORM
### TOÀN DIỆN 37 MỤC — SINH TỰ ĐỘNG TỪ BẰNG CHỨNG KIỂM ĐỊNH

- **Thời điểm sinh báo cáo**: 2026-11-25T02:55:47Z
- **Nguồn dữ liệu**: trạng thái bền vững của harness, kết quả kiểm định, kết quả chạy tải, kết quả Stripe, kết quả Kubernetes và lịch sử phân phối Git.
- **Nguyên tắc**: mọi khẳng định trong báo cáo này được sinh tự động từ bằng chứng đã lưu. Trường nào chưa có bằng chứng thì được ghi rõ là chưa xác minh.

---

## 1. TỔNG QUAN ĐIỀU HÀNH & KẾT LUẬN CUỐI CÙNG (EXECUTIVE SUMMARY)

- **Quyết định nghiệm thu**: **CHƯA ĐẠT — CHƯA SẴN SÀNG TRIỂN KHAI SẢN XUẤT**.
- **Cổng kiểm định**: 19/25 đạt, 3 không đạt, 0 chờ, 3 bị chặn.
- **Rà soát mã nguồn**: 100.00% (1522/1522 tập tin).
- **Tác vụ còn mở mức HIGH**: 0 (CRITICAL: 0, MEDIUM: 103, LOW: 77).
- **Quyết định sản xuất theo completion predicate**: chưa đạt.
- **Cổng đang không đạt**: GATE-6, GATE-16, GATE-17, GATE-19, GATE-24, GATE-25.
- **Bằng chứng tải nặng** (lần chạy mới nhất: soak — FAIL, thư mục soak-1795574030): yêu cầu 46206, tần suất 36.96 req/s, p95 31.87 ms, tỷ lệ lỗi 0.0000.
- **Stripe sandbox thực**: trạng thái kiểm định cấu hình BLOCKED, nghiệm thu thanh toán FAILED — xem mục 12 và 13.
- **Cảnh báo Telegram**: 6 cổng cảnh báo đã được ghi nhận — xem mục 29, 30 và 31.

## 2. KIỂM TOÁN TOÀN BỘ MÃ NGUỒN (FULL SOURCE AUDIT)

- Tổng số tập tin trong phạm vi: **1522**; đã rà soát: **1522**.
- Tỷ lệ rà soát mã thực thi: **100.00%** (1303/1303).
- Phân bố theo nhóm: BUILD=1, DOCUMENTATION=113, GENERATED=8, HARNESS=85, INFRASTRUCTURE=48, PRODUCTION_CODE=696, SHARED_LIBRARY=119, TEST_CODE=439, VENDORED_TOOLING=13.

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
- Bộ kiểm thử đồng thời: PASS, 38 test.

## 8. TÍNH ĐÚNG ĐẮN KHO HÀNG (INVENTORY CORRECTNESS)

- Bộ kiểm thử kho hàng: PASS, 23 test.
- Bất biến không vượt tồn kho (INV-01): PASS, số vi phạm: 0.

## 9. TÍNH ĐÚNG ĐẮN HẠN MỨC KHUYẾN MÃI (PROMOTION QUOTA CORRECTNESS)

- INV-02 (COUPON_QUOTA_RESPECTED): PASS, vi phạm: 0.
- INV-06 (COUPON_RESERVATION_UNIQUE): PASS, vi phạm: 0.
- INV-08 (EXPIRED_RESERVATION_RELEASED): PASS, vi phạm: 0.

## 10. SAGA CHECKOUT & BỒI ĐỀN (CHECKOUT SAGA)

- Bộ kiểm thử Kafka/Outbox: PASS, 266 test.
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
- Nghiệm thu thanh toán đã ghi nhận: trạng thái FAILED, máy chủ Stripe bị kiểm định official-stripe-mock 0.206.0 tại http://stripe-mock-acceptance:12111; tổng số 8 trường hợp, 5 proven, 2 thất bại, 1 chưa chứng minh được.
- Các trường hợp nghiệm thu chưa được chứng minh: refund_idempotent_replay, declined_card_refusal, currency_mismatch_refusal.
- Cổng GATE-6 (PAYMENT_CORRECTNESS): BLOCKED — the Stripe acceptance evidence is stale or missing source, harness or revision binding; the payment suite reports PASS.

## 14. XÁC MINH WEBHOOK & HOÀN TIỀN (WEBHOOK AND REFUND VERIFICATION)

- Lệnh harness stripe test-webhooks (ký HMAC-SHA256 theo đúng định dạng t/v1 của Stripe) **bị chặn** với lý do đã ghi nhận: the configured webhook secret is a local placeholder, so the signature check would only prove that a self signed payload round trips.
- Trường hợp nghiệm thu chữ ký webhook đã ghi nhận: phán quyết PROVEN — the harness-signed event moved the payment to CAPTURED and the event with a forged signature was refused with 401 PAY-5005.
- Hoàn tiền: xem bộ kiểm thử payment — PASS.

## 15. XÁC MINH DOCKER COMPOSE (DOCKER COMPOSE VERIFICATION)

- Bộ kiểm thử tích hợp: PASS, 362 test.

## 16. KIẾN TRÚC KUBERNETES (KUBERNETES ARCHITECTURE)

- Hành động đã ghi nhận: validate — 14 mục kiểm tra trên các tệp manifest trong k8s/.
- Kết quả kiểm tra tĩnh manifest tại thời điểm ghi nhận: BLOCKED; các mục kiểm tra K8S-01 đến K8S-13 dưới đây chỉ đọc tệp manifest và không phản ánh hành vi của một cụm đang chạy.
- Bộ kiểm thử k8s: FAIL — 0 kubernetes manifest checks failed.
- **Cấp nhân tự động ở thời gian chạy chưa được xác minh**: the recorded autoscaling observation carries no manifest_fingerprint identifying the observed k8s manifest set.
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
  - K8S-14 AUTOSCALING_OBSERVATION: BLOCKED — the recorded autoscaling observation carries no manifest_fingerprint identifying the observed k8s manifest set

## 17. KẾT QUẢ HPA (HPA SCALING RESULTS)

- HPA được cấu hình cho gateway-service trong k8s/gateway-service.yaml; mục kiểm tra K8S-07 AUTOSCALING trên manifest đã ghi nhận: PASS — every autoscaler targets a declared deployment with bounded replicas and metrics.
- **Chưa xác minh cấp nhân tự động trên cụm thật**: trạng thái quan sát đã ghi nhận là BLOCKED — lý do: the recorded autoscaling observation carries no manifest_fingerprint identifying the observed k8s manifest set.
- Cổng GATE-24 (KUBERNETES_MANIFESTS): BLOCKED — the manifests validate but no autoscaling observation of a real cluster is recorded.
- Cổng GATE-25 (AUTOSCALING_OBSERVATION): BLOCKED — the recorded autoscaling observation carries no manifest_fingerprint identifying the observed k8s manifest set.

## 18. KẾT QUẢ KEDA (KEDA SCALING RESULTS)

- **Chưa triển khai**: danh sách kind đã ghi nhận trong kiểm tra K8S-02 (kinds present: ConfigMap, Deployment, HorizontalPodAutoscaler, Ingress, Namespace, PodDisruptionBudget, PrometheusRule, Secret, Service, ServiceAccount) không chứa ScaledObject của KEDA. Đây là khoảng trống đã biết và đã ghi nhận trong sổ tay tác vụ.

## 19. HỒ SƠ CHIẾU TẢI BLACK FRIDAY (BLACK FRIDAY WORKLOAD PROFILES)

- Hồ sơ định nghĩa trong production-audit/harness/loadtesting/profiles.py và điều khiển bằng lệnh harness load.
- Các hồ sơ: baseline (A), ramp (B), spike (C), sustained-peak (D), flash-sale (E), promotion-storm (F), kafka-backlog (G), failure-during-peak (H), recovery (I), soak (J).
- Mọi hồ sơ bị chặn bởi ranh giới an toàn về trần tần suất, số VU, thời lượng và tổng số yêu cầu.

## 20. THÔNG LƯỢNG NỀN TẢNG VS ĐỈNH (BASELINE VS PEAK THROUGHPUT)

- **Chưa ghi nhận giới hạn dung lượng đo được**: trạng thái đã lưu không chứa phép đo nào thoả quy tắc của lần chạy đo đầy đủ cuối cùng.

- baseline — kịch bản browse, trạng thái FAIL: yêu cầu 2378, 13.99 req/s, lượt lặp 1149, p95 33.60 ms, tỷ lệ lỗi 0.0000.
  - Kế hoạch: 10.0000 lượt lặp/giây (20.0000 req/s); đã giao: 6.7588 lượt lặp/giây, 13.9883 req/s; tỷ lệ giao/nhắm 0.8511 (mẫu số: planned_iteration_rate_per_second); thành công 2298, thất bại 0, từ chối 0, gateway loại 0, vi phạm bất biến 1.
  - Kết luận nghiệp vụ: the business failure rate 0.0000 is within the limit 0.0500 and the platform shed 0.0000 of the requests and the platform delivered 0.85 of the planned 7.94 iterations per second.
  - Số lần chạy đã lưu cho hồ sơ này: 33; bảng trên là lần chạy cuối cùng.
- failure-during-peak — kịch bản full, trạng thái FAIL: yêu cầu 21096, 124.08 req/s, lượt lặp 4209, p95 220.20 ms, tỷ lệ lỗi 0.0000.
  - Kế hoạch: 37.0000 lượt lặp/giây (148.0000 req/s); đã giao: 24.7558 lượt lặp/giây, 124.0788 req/s; tỷ lệ giao/nhắm 0.8451 (mẫu số: planned_iteration_rate_per_second); thành công 8517, thất bại 0, từ chối 0, gateway loại 0, vi phạm bất biến 1.
  - Kết luận nghiệp vụ: the business failure rate 0.0000 is within the limit 0.0500 and the platform shed 0.0000 of the requests and the platform delivered 0.85 of the planned 29.29 iterations per second.
- flash-sale — kịch bản flash-sale, trạng thái FAIL: yêu cầu 14946, 78.21 req/s, lượt lặp 3857, p95 14797.94 ms, tỷ lệ lỗi 0.0052.
  - Kế hoạch: 50.0000 lượt lặp/giây (150.0000 req/s); đã giao: 20.1834 lượt lặp/giây, 78.2115 req/s; tỷ lệ giao/nhắm 0.5083 (mẫu số: planned_iteration_rate_per_second); thành công 3757, thất bại 78, từ chối 0, gateway loại 627, vi phạm bất biến 1.
  - Kết luận nghiệp vụ: the business failure rate 0.0203 is within the limit 0.0500 but the platform shed 0.1405 of the requests, which exceeds the limit 0.0100.
  - Số lần chạy đã lưu cho hồ sơ này: 19; bảng trên là lần chạy cuối cùng.
- kafka-backlog — kịch bản full, trạng thái FAIL: yêu cầu 16095, 69.97 req/s, lượt lặp 3199, p95 121.74 ms, tỷ lệ lỗi 0.0000.
  - Kế hoạch: 20.0000 lượt lặp/giây (80.0000 req/s); đã giao: 13.9078 lượt lặp/giây, 69.9738 req/s; tỷ lệ giao/nhắm 0.8202 (mẫu số: planned_iteration_rate_per_second); thành công 6398, thất bại 0, từ chối 0, gateway loại 0, vi phạm bất biến 1.
  - Kết luận nghiệp vụ: the business failure rate 0.0000 is within the limit 0.0500 and the platform shed 0.0000 of the requests and the platform delivered 0.82 of the planned 16.96 iterations per second.
- promotion-storm — kịch bản promotion-storm, trạng thái FAIL: yêu cầu 4469, 23.68 req/s, lượt lặp 1841, p95 60100.82 ms, tỷ lệ lỗi 0.1392.
  - Kế hoạch: 60.0000 lượt lặp/giây (120.0000 req/s); đã giao: 9.7565 lượt lặp/giây, 23.6837 req/s; tỷ lệ giao/nhắm 0.2048 (mẫu số: planned_iteration_rate_per_second); thành công 0, thất bại 622, từ chối 0, gateway loại 1222, vi phạm bất biến 1.
  - Kết luận nghiệp vụ: the platform completed no business request: it refused 0 orders as a business refusal, refused 6 as a contention on the cart or the coupon and shed 1222 requests, so it did not serve the requested load.
  - Số lần chạy đã lưu cho hồ sơ này: 26; bảng trên là lần chạy cuối cùng.
- ramp — kịch bản full, trạng thái FAIL: yêu cầu 9740, 42.34 req/s, lượt lặp 1919, p95 73.11 ms, tỷ lệ lỗi 0.0000.
  - Kế hoạch: 12.0000 lượt lặp/giây (48.0000 req/s); đã giao: 8.3425 lượt lặp/giây, 42.3428 req/s; tỷ lệ giao/nhắm 0.8200 (mẫu số: planned_iteration_rate_per_second); thành công 3893, thất bại 0, từ chối 0, gateway loại 0, vi phạm bất biến 1.
  - Kết luận nghiệp vụ: the business failure rate 0.0000 is within the limit 0.0500 and the platform shed 0.0000 of the requests and the platform delivered 0.82 of the planned 10.17 iterations per second.
  - Số lần chạy đã lưu cho hồ sơ này: 31; bảng trên là lần chạy cuối cùng.
- recovery — kịch bản browse, trạng thái FAIL: yêu cầu 650, 5.89 req/s, lượt lặp 320, p95 38.11 ms, tỷ lệ lỗi 0.0000.
  - Kế hoạch: 5.0000 lượt lặp/giây (10.0000 req/s); đã giao: 2.8997 lượt lặp/giây, 5.8900 req/s; tỷ lệ giao/nhắm 0.8860 (mẫu số: planned_iteration_rate_per_second); thành công 640, thất bại 0, từ chối 0, gateway loại 0, vi phạm bất biến 1.
  - Kết luận nghiệp vụ: the business failure rate 0.0000 is within the limit 0.0500 and the platform shed 0.0000 of the requests and the platform delivered 0.89 of the planned 3.27 iterations per second.
  - Số lần chạy đã lưu cho hồ sơ này: 21; bảng trên là lần chạy cuối cùng.
- soak — kịch bản full, trạng thái FAIL: yêu cầu 46206, 36.96 req/s, lượt lặp 9249, p95 31.87 ms, tỷ lệ lỗi 0.0000.
  - Kế hoạch: 10.0000 lượt lặp/giây (40.0000 req/s); đã giao: 7.3990 lượt lặp/giây, 36.9638 req/s; tỷ lệ giao/nhắm 0.7612 (mẫu số: planned_iteration_rate_per_second); thành công 18597, thất bại 0, từ chối 0, gateway loại 0, vi phạm bất biến 1.
  - Kết luận nghiệp vụ: the platform delivered only 0.76 of the planned 9.72 iterations per second, which is below the accepted 0.80, so it did not sustain the requested rate.
- spike — kịch bản , trạng thái BLOCKED: yêu cầu không đo được, không đo được, lượt lặp không đo được, p95 không đo được, tỷ lệ lỗi không đo được.
  - Kế hoạch: không đo được lượt lặp/giây (không đo được req/s); đã giao: 0.0000 lượt lặp/giây, 0.0000 req/s; tỷ lệ giao/nhắm không đo được (mẫu số: target_requests_per_second); thành công 0, thất bại 0, từ chối 0, gateway loại 0, vi phạm bất biến 0.
  - Số lần chạy đã lưu cho hồ sơ này: 21; bảng trên là lần chạy cuối cùng.
- sustained-peak — kịch bản full, trạng thái FAIL: yêu cầu 58601, 90.10 req/s, lượt lặp 11710, p95 82.01 ms, tỷ lệ lỗi 0.0000.
  - Kế hoạch: 25.0000 lượt lặp/giây (100.0000 req/s); đã giao: 18.0044 lượt lặp/giây, 90.1005 req/s; tỷ lệ giao/nhắm 0.7619 (mẫu số: planned_iteration_rate_per_second); thành công 23519, thất bại 0, từ chối 0, gateway loại 0, vi phạm bất biến 1.
  - Kết luận nghiệp vụ: the platform delivered only 0.76 of the planned 23.63 iterations per second, which is below the accepted 0.80, so it did not sustain the requested rate.
- Lần chạy đã lưu (mới nhất): hồ sơ soak, 40 req/s nhắm, 60 VU, trạng thái FAIL, thư mục soak-1795574030.
- **Tính hợp lệ của phép đo**: hợp lệ.

## 21. ĐỘ TRỄ & TỶ LỆ LỖI (LATENCY AND ERROR RATES)

- Nguồn: lần chạy mới nhất soak — FAIL, thư mục soak-1795574030.
- p50 7.55 ms, p90 22.50 ms, p95 31.87 ms, p99 không đo được, trung bình 11.60 ms, lớn nhất 447.23 ms.
- Tỷ lệ lỗi HTTP: 0.0000 (0 yêu cầu lỗi).
- Tỷ lệ kiểm tra nghiệp vụ đạt: 1.0000.

## 22. BÃO HÒA TÀI NGUYÊN (RESOURCE SATURATION)

- Máy đo: 10 lõi, tải trung bình [7.12, 9.64, 10.83].
- Phép đo của lần chạy mới nhất (soak) là hợp lệ.
- HikariCP, CPU, bộ nhớ, Kafka lag và Redis: lần chạy tải không ghi nhận các chỉ số này, xem trạng thái cổng kiểm định tại production-audit/state/verification.json.

## 23. KẾT QUẢ FLASH-SALE TRÊN KHO HÀNG (FLASH-SALE INVENTORY RESULTS)

- Kịch bản flash-sale hướng mọi VU vào cùng một SKU hạn chế; bất biến kiểm tra sau mỗi lần chạy bằng lệnh harness load flash-sale.
- Lần chạy flash-sale đã lưu: flash-sale-1795572879 — trạng thái FAIL, yêu cầu 14946, 78.21 req/s, p95 14797.94 ms, tỷ lệ lỗi 0.0052.

## 24. KẾT QUẢ PROMOTION-STORM (PROMOTION-STORM RESULTS)

- Kịch bản promotion-storm áp dụng cùng một mã giảm giá cho mọi VU; bất biến hạn mức được kiểm tra sau mỗi lần chạy.
- Lần chạy promotion-storm đã lưu: promotion-storm-1795573133 — trạng thái FAIL, yêu cầu 4469, 23.68 req/s, p95 60100.82 ms, tỷ lệ lỗi 0.1392.
- Bất biến hạn mức khuyến mãi của lần chạy này: FAIL — 8 bất biến, 7 đạt, 1 không đạt, 0 bỏ qua, 1 vi phạm.

## 25. KIỂM THỬ KHÔI PHỤC & LỖI (RECOVERY AND FAILURE TESTING)

- Hồ sơ recovery và failure-during-peak đã được hiện thực trong harness.
- Lần chạy recovery đã lưu: recovery-1795573890 — trạng thái FAIL, yêu cầu 650, p95 38.11 ms, tỷ lệ lỗi 0.0000.
- Chi tiết: production-audit/reports/e2e.md và các lần chạy đã lưu tại production-audit/runs/run-20261118-a2b6e6fc/load-results/.

## 26. BẰNG CHỨNG TRUY VẾT PHÂN TÁN (DISTRIBUTED TRACE EVIDENCE)

- Tempo và OpenTelemetry Collector đang chạy trong cụm Compose; cấu hình tại config/observability/tempo.yaml và config/observability/otel-collector-config.yaml.
- Cổng kiểm định quan sát: PASS — 7 of 7 observability checks passed.
- Chưa xác minh theo bộ kiểm thử quan sát: OBS-04 kafka producer and consumer spans are correlated by hand, not asserted automatically.
- Chưa xác minh theo bộ kiểm thử quan sát: OBS-05 a controlled failure is visible in the backends but the check is not automated.

## 27. NHẬT KÝ TẬP TRUNG (CENTRALIZED LOGS)

- Loki và Grafana Alloy đang chạy; cấu hình tại config/observability/loki.yaml và config/observability/alloy/config.alloy.

## 28. BẢNG ĐIỀU KHIỂN GRAFANA (GRAFANA DASHBOARDS)

- Bảng điều khiển fleet-overview được cấu phục tại config/observability/grafana/dashboards/fleet-overview.json.

## 29. KẾT QUẢ CẢNH BÁO TELEGRAM CI (CI TELEGRAM ALERT RESULTS)

- QA-ALERT-01: PASS — controlled CI failure notification delivered to the CI destination 
- Đường dẫn CI: .github/workflows/ci.yml (job notify-telegram, điều kiện failure()), kịch bản scripts/notify-telegram.sh.

## 30. KẾT QUẢ CẢNH BÁO TELEGRAM RUNTIME (RUNTIME TELEGRAM ALERT RESULTS)

- Cổng kiểm định cảnh báo (GATE-23): PASS — 0 failed and 0 blocked checks.
- QA-ALERT-02: PASS — controlled runtime incident delivered to the runtime destination 
- QA-ALERT-03: PASS — recovery notification delivered 
- QA-ALERT-04: PASS — identical alerts must collapse into a single grouped alert 
  - Số cảnh báo đã gửi: 5; số nhóm cảnh báo Alertmanager trả về: 1; số cảnh báo Alertmanager đếm được: 0.
  - **Alertmanager không đếm được cảnh báo nào (0/5)**: kiểm tra giao hàng chấp nhận cả 0 lẫn 5, nên trạng thái PASS không chứng minh được việc gom cảnh báo đã thực sự xảy ra; phải chạy lại khi Alertmanager nhận đủ 5 cảnh báo.
- QA-ALERT-05: PASS — the redaction path must remove credentials before delivery 
- Cấu hình: config/observability/alertmanager/alertmanager.yml.template, quy tắc config/observability/alertmanager/rules/runtime-alerts.yml.

## 31. SẴN SÀNG CẢNH BÁO PRODUCTION (PRODUCTION ALERTING READINESS)

- Trạng thái kiểm định cấu hình cảnh báo: PASS.
- Thông tin xác thực giao hàng theo kiểm tra ALT-07 đã ghi nhận: PASS — telegram delivery credentials are present and valid.
- Các cổng giao hàng đã ghi nhận trả lời thành công qua Telegram Bot API công khai: QA-ALERT-00, QA-ALERT-01, QA-ALERT-02, QA-ALERT-03.
- Đường dẫn cấu hình: Prometheus đánh giá quy tắc trong config/observability/alertmanager/rules, Alertmanager gom nhóm và chống lặp trong config/observability/alertmanager, tuyến giao hàng CI là scripts/notify-telegram.sh và tuyến runtime là Telegram Bot API.
- **Giao hàng cảnh báo trong môi trường production chưa được xác minh**: các lần giao đã ghi nhận đều diễn ra trong môi trường kiểm định của lần chạy này; không có sự kiện production được phép để xác minh.

## 32. BUILD & KIỂM THỬ HỒI QUY (BUILD AND REGRESSION TESTS)

- alerts: PASS — 0 failed and 0 blocked checks
- baseline: STALE — 1913 test recorded against an earlier source revision
- build: PASS
- comments: STALE — recorded against an earlier source revision
- concurrency: PASS — 38 test
- concurrency_inventory: PASS — 23 test
- e2e: FAIL — no current end to end collection completed a run
- edge: PASS — 41 test
- gateway: PASS — 141 test
- happy: PASS — 2045 test
- integration: PASS — 362 test
- k8s: FAIL — 0 kubernetes manifest checks failed
- kafka: PASS — 266 test
- load_baseline: FAIL
- load_failure-during-peak: FAIL
- load_flash-sale: FAIL
- load_kafka-backlog: FAIL
- load_promotion-storm: FAIL
- load_ramp: FAIL
- load_recovery: FAIL
- load_soak: FAIL
- load_spike: BLOCKED
- load_sustained-peak: FAIL
- operations: PASS — 7 of 7 observability checks passed
- payment: PASS — 310 test
- performance: FAIL — 0/10 load profiles passed; capacity binding=False
- security: PASS — 153 test
- unit: PASS — 2061 test
- Lệnh build: ./mvnw -B -ntp -T 1 clean verify, mã thoát 0, thời gian 771.82 giây, ghi nhận lúc 2026-11-25T01:06:13Z tại revision b8858b391025.

## 33. PHÁN QUYẾT QA ĐỘC LẬP (INDEPENDENT QA VERDICTS)

- final-candidate-review.md (25963 byte) — phán quyết đã ghi nhận trong văn bản: FAIL.
- Cổng GATE-19 (INDEPENDENT_REVIEW): FAIL — 1 of 1 independent reviews report a failing verdict (0 đạt, 1 không đạt, 0 không có phán quyết rõ).
- **Có bản đánh giá độc lập ghi phán quyết không đạt**: cổng GATE-19 đang không đạt nên báo cáo này không thể kết luận nghiệm thu.

## 34. COMMIT & KẾT QUẢ PUSH (GIT COMMITS AND PUSH OUTCOMES)

- Tổng số bản ghi phân phối: **49** (COMMITTED: **23**, PUSHED: **9**, PUSH_BLOCKED: **1**, REJECTED_BY_GATES: **16**).
- **Bị cổng kiểm định từ chối**: 16 bản ghi.
  - 2026-11-18T14:15:59Z — commit — git add failed: The following paths are ignored by one of your .gitignore files: production-audit hint: Use -f if you really want to add them. hint: Turn this message off by runnin
  - 2026-11-18T14:16:25Z — commit — unexpected staged paths: ['production-audit/README.md', 'production-audit/architecture/adrs/ADR-001-payment-provider-outcome.md', 'production-audit/architecture/adrs/ADR-002-no-sil
  - 2026-11-18T14:16:55Z — commit — paths outside allow-list: ['production-audit']
  - 2026-11-18T14:17:02Z — commit — secret detected in staged diff
  - 2026-11-18T14:18:28Z — commit — secret detected in staged content: ['production-audit/config/secret-scan-exceptions.json']
  - 2026-11-18T14:18:59Z — commit — secret detected in staged content: ['payment-service/src/main/java/com/shop/paymentservice/provider/PaymentProviderConfig.java']
  - 2026-11-18T14:19:04Z — commit — secret detected in staged content: ['payment-service/src/main/java/com/shop/paymentservice/provider/PaymentProviderConfig.java']
  - 2026-11-18T14:20:11Z — commit — secret detected in staged content: ['payment-service/src/test/java/com/shop/paymentservice/service/WebhookEventServiceStripeTest.java', 'payment-service/src/test/java/com/shop/paym
  - 2026-11-18T14:20:56Z — commit — secret scan failed: ['auth-service/src/test/java/com/shop/authservice/security/SecurityFilterChainIntegrationTest.java', 'media-service/src/test/java/com/shop/mediaservice/controll
  - 2026-11-18T14:22:08Z — commit — secret scan failed: ['auth-service/src/test/java/com/shop/authservice/security/SecurityFilterChainIntegrationTest.java', 'media-service/src/test/java/com/shop/mediaservice/controll
  - 2026-11-18T14:24:10Z — commit — secret detected in staged content: ['production-audit/harness/tests/test_git_delivery.py']
  - 2026-11-18T14:24:20Z — commit — secret detected in staged content: ['production-audit/harness/tests/test_git_delivery.py']
  - 2026-11-19T06:32:48Z — commit — git add failed: fatal: pathspec 'harness/e2e/environment.py' did not match any files
  - 2026-11-19T06:32:52Z — commit — secret scan failed: ['production-audit/harness/e2e/collection_contract.py']
  - 2026-11-19T06:37:57Z — commit — secret scan failed: ['production-audit/config/secret-scan-exceptions.json', 'production-audit/harness/e2e/collection_contract.py']
  - 2026-11-19T15:54:26Z — commit — secret scan failed: ['docker-compose.yml']
- **Bị chặn khi đẩy**: 1 bản ghi.
  - 2026-11-18T14:22:53Z — push — remote: error: GH013: Repository rule violations found for refs/heads/audit/production-hardening-run-20261118-a2b6e6fc.         remote:  remote: - GITHUB PUSH PROTECTION         re
- Các lần phân phối gần nhất:
  - 2026-11-19T16:03:00Z — b14dde9282e0 — COMMITTED — chore(inventory): synchronize manifest revision with HEAD commit 2c1279bc
  - 2026-11-19T16:03:06Z — b14dde9282e0 — PUSHED — 
  - 2026-11-19T16:03:25Z — cc25eb8b2de9 — COMMITTED — chore(harness): finalize gates and delivery state verification
  - 2026-11-19T16:03:31Z — cc25eb8b2de9 — PUSHED — 
  - 2026-11-19T16:06:52Z — 7a3f72e92f79 — COMMITTED — docs(report): deliver comprehensive 36-section production readiness report
  - 2026-11-19T16:06:57Z — 7a3f72e92f79 — PUSHED — 
  - 2026-11-19T16:07:23Z — ff6e578e5ecc — COMMITTED — chore(harness): clean sync of verification evidence and delivery logs
  - 2026-11-19T16:07:31Z — ff6e578e5ecc — PUSHED — 

## 35. TỒN ĐỌNG CÒN LẠI (REMAINING BLOCKERS)

### 35.1 Mức HIGH

- Không có tác vụ HIGH nào đang mở.

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

- Rà soát mã nguồn: 1522/1522 tập tin đã được rà soát; 0 tập tin chưa rà soát (cổng GATE-1 đã đạt).
- Chính sách không chú thích: STALE — recorded against an earlier source revision.
- Bằng chứng của bộ kiểm thử này được ghi nhận ở một phiên bản mã nguồn cũ hơn mã nguồn hiện tại, phải chạy lại và ghi nhận lại bằng chứng ở HEAD thì cổng kiểm định tương ứng mới được coi là đạt.
- Build reactor: PASS.
- Bất biến nghiệp vụ dưới tải (lần chạy soak — FAIL): FAIL — 8 bất biến kiểm tra, 7 đạt, 1 không đạt, 0 bỏ qua, 1 vi phạm.

### 36.2 CHƯA XÁC MINH

- Tích hợp Stripe sandbox thật (api.stripe.com với khóa test thật): cổng GATE-6 đang BLOCKED — the Stripe acceptance evidence is stale or missing source, harness or revision binding; the payment suite reports PASS; nghiệm thu đã ghi nhận chạy trên official-stripe-mock tại http://stripe-mock-acceptance:12111.
- Giao hàng Telegram thật trong môi trường production: các cổng QA-ALERT-00, QA-ALERT-01, QA-ALERT-02, QA-ALERT-03 đã giao thành công qua Telegram Bot API công khai trong môi trường kiểm định; việc giao hàng trong production vẫn chưa được xác minh.
- Cấp nhân tự động HPA và KEDA trên cụm thật: trạng thái quan sát đã ghi nhận là BLOCKED — the recorded autoscaling observation carries no manifest_fingerprint identifying the observed k8s manifest set.
- Thông lượng thanh toán cấp nhà cung cấp: phụ thuộc giới hạn tần suất của Stripe và cấu hình tài khoản.
- Giao hàng cảnh báo trong production: chỉ có thể xác minh bằng sự kiện production được cho phép.

## 37. QUYẾT ĐỊNH SẴN SÀNG TRIỂN KHAI SẢN XUẤT (PRODUCTION READINESS DECISION)

- **Quyết định**: CHƯA SẴN SÀNG TRIỂN KHAI SẢN XUẤT.
- Các cổng chưa đạt: GATE-6, GATE-16, GATE-17, GATE-19, GATE-24, GATE-25.
- Số tác vụ HIGH đang mở: 0 (CRITICAL: 0); MEDIUM: 103; LOW: 77.
- Trạng thái tổng hợp tác vụ: BLOCKED=0, DISCOVERED=8, FAILED_VERIFICATION=0, IMPLEMENTED=0, IN_PROGRESS=0, NORMALIZED=0, READY=172, REOPENED=0, REQUIREMENT_CONFLICT=0, VERIFIED=88, VERIFYING=0.
- Báo cáo được sinh tự động tại /Users/tonminh-mac/IdeaProjects/untitled5/production-audit/reports/final-production-readiness-report.md.

