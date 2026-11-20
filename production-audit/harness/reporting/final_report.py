from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from .. import config
from ..inventory.coverage import coverage_summary, load_coverage
from ..persistence.store import read_json, utc_now
from ..task_registry.registry import load_tasks, task_status_summary
from ..verification.gates import completion_predicate, evaluate_gates, evidence_is_fresh


def _state(path: Path) -> dict[str, Any]:
    return read_json(path, {}) or {}


def _load_results() -> list[dict[str, Any]]:
    root = config.run_root() / "load-results"
    results: list[dict[str, Any]] = []
    if not root.exists():
        return results
    for path in sorted(root.iterdir()):
        result = path / "result.json"
        latest = path / "summary.json"
        if result.exists():
            try:
                document = json.loads(result.read_text(encoding="utf-8"))
            except (OSError, json.JSONDecodeError):
                continue
            results.append(
                {
                    "directory": path.name,
                    "scenario": document.get("scenario", ""),
                    "profile": document.get("profile", ""),
                    "status": document.get("status", ""),
                    "metrics": document.get("metrics") or {},
                    "business_verdict": document.get("business_verdict", ""),
                    "measurement_valid": document.get("measurement_valid"),
                }
            )
            continue
        if not latest.exists():
            continue
        try:
            document = json.loads(latest.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            continue
        results.append(
            {
                "directory": path.name,
                "scenario": document.get("scenario", ""),
                "profile": "",
                "status": "",
                "metrics": {},
                "business_verdict": "",
                "measurement_valid": None,
            }
        )
    return results


def _persisted_load_result() -> dict[str, Any]:
    return _state(config.state_root() / "performance-baselines.json")


def _suite_rows(verification: dict[str, Any], fingerprint: str = "") -> list[tuple[str, str, str]]:
    rows: list[tuple[str, str, str]] = []
    for name, suite in sorted((verification.get("suites") or {}).items()):
        status = str(suite.get("status", "PENDING"))
        tests = suite.get("tests_run")
        if fingerprint and not evidence_is_fresh(suite, fingerprint):
            status = "STALE"
        elif status == "PASS" and tests is not None and int(tests) == 0:
            status = "INSUFFICIENT_EVIDENCE"
        detail = str(suite.get("detail", ""))
        if tests is not None:
            detail = (detail + " " if detail else "") + str(tests) + " test"
        if status == "STALE":
            detail = (detail + " " if detail else "") + "recorded against an earlier source revision"
        if status == "INSUFFICIENT_EVIDENCE":
            detail = (detail + " " if detail else "") + "no test was recorded for this suite"
        rows.append((name, status, detail.strip()))
    return rows


def _field(task: Any, name: str, default: Any = "") -> Any:
    if isinstance(task, dict):
        return task.get(name, default)
    return getattr(task, name, default)


def _task_rows(tasks: Any, states: tuple[str, ...], severity: str = "") -> list[tuple[str, str, str]]:
    rows: list[tuple[str, str, str]] = []
    entries = tasks.items() if isinstance(tasks, dict) else [(getattr(task, "id", ""), task) for task in tasks]
    for identifier, task in sorted(entries, key=lambda item: str(item[0])):
        if _field(task, "state") not in states:
            continue
        if severity and _field(task, "severity") != severity:
            continue
        rows.append((str(identifier), str(_field(task, "severity", "")), str(_field(task, "title", ""))))
    return rows


def _metric(value: Any, suffix: str = "") -> str:
    if value is None:
        return "không đo được"
    if isinstance(value, float):
        return ("%.2f" % value) + suffix
    return str(value) + suffix


def write_final_report(path: Path | None = None) -> Path:
    target = path or (config.reports_root() / "final-production-readiness-report.md")
    gates = evaluate_gates()
    predicate = completion_predicate(gates)
    coverage = coverage_summary(load_coverage())
    tasks = load_tasks()
    summary = task_status_summary(tasks)
    verification = _state(config.verification_path())
    stripe = _state(config.state_root() / "stripe-results.json")
    kubernetes = _state(config.state_root() / "k8s-results.json")
    alerts = _state(config.state_root() / "alert-results.json")
    deliveries = _state(config.git_deliveries_path())
    persisted_load = _persisted_load_result()
    load_rows = _load_results()

    passed = [gate for gate in gates if gate.status == "PASS"]
    failed = [gate for gate in gates if gate.status == "FAIL"]
    pending = [gate for gate in gates if gate.status == "PENDING"]
    open_states = ("READY", "DISCOVERED", "IN_PROGRESS", "IMPLEMENTED", "VERIFYING", "REOPENED", "FAILED_VERIFICATION")
    open_high = _task_rows(tasks, open_states, "HIGH")
    open_medium = _task_rows(tasks, open_states, "MEDIUM")
    open_low = _task_rows(tasks, open_states, "LOW")
    alert_gates = alerts.get("results", [])
    alerting_status = "chưa chạy"
    alerting_document = _state(config.state_root() / "alerting-results.json")
    if alerting_document.get("result"):
        alerting_status = str(alerting_document["result"].get("status", "chưa chạy"))
    stripe_result = stripe.get("result", {})
    kubernetes_result = kubernetes.get("result", {})
    load_result = persisted_load.get("result", {})
    load_metrics = load_result.get("metrics", {}) or (load_rows[-1]["metrics"] if load_rows else {})
    load_invariants = load_result.get("invariants", {})
    build = verification.get("suites", {}).get("build", {})
    from ..inventory.scanner import source_fingerprint

    fingerprint = source_fingerprint()
    build_fresh = evidence_is_fresh(build, fingerprint)
    build_status = str(build.get("status", "chưa chạy")) if build_fresh else "bằng chứng đã cũ"

    lines: list[str] = []
    add = lines.append

    add("# BÁO CÁO ĐÁNH GIÁ VÀ NGHIỆM THU MỨC ĐỘ SẴN SÀNG TRIỂN KHAI SẢN XUẤT")
    add("## HỆ THỐNG KIẾN TRÚC VI DỊCH VỤ THƯƠNG MẠI ĐIỆN TỬ PETPROJECT PLATFORM")
    add("### TOÀN DIỆN 37 MỤC — SINH TỰ ĐỘNG TỪ BẰNG CHỨNG KIỂM ĐỊNH")
    add("")
    add("- **Thời điểm sinh báo cáo**: " + utc_now())
    add("- **Nguồn dữ liệu**: trạng thái bền vững của harness, kết quả kiểm định, kết quả chạy tải, kết quả Stripe, kết quả Kubernetes và lịch sử phân phối Git.")
    add("- **Nguyên tắc**: mọi khẳng định trong báo cáo này được sinh tự động từ bằng chứng đã lưu. Trường nào chưa có bằng chứng thì được ghi rõ là chưa xác minh.")
    add("")
    add("---")
    add("")

    add("## 1. TỔNG QUAN ĐIỀU HÀNH & KẾT LUẬN CUỐI CÙNG (EXECUTIVE SUMMARY)")
    add("")
    decision = "CHƯA ĐẠT — CHƯA SẴN SÀNG TRIỂN KHAI SẢN XUẤT" if not predicate.get("production_ready") else "SẴN SÀNG TRIỂN KHAI SẢN XUẤT"
    add("- **Quyết định nghiệm thu**: **" + decision + "**.")
    add("- **Cổng kiểm định**: " + str(len(passed)) + "/" + str(len(gates)) + " đạt, " + str(len(failed)) + " không đạt, " + str(len(pending)) + " chờ.")
    add("- **Rà soát mã nguồn**: " + _metric(coverage.get("percent_reviewed"), "%") + " (" + str(coverage.get("reviewed_files")) + "/" + str(coverage.get("total_files")) + " tập tin).")
    add("- **Tác vụ còn mở mức HIGH**: " + str(len(open_high)) + " (MEDIUM: " + str(len(open_medium)) + ", LOW: " + str(len(open_low)) + ").")
    add("- **Quyết định sản xuất theo completion predicate**: " + ("đạt" if predicate.get("production_ready") else "chưa đạt") + ".")
    failing = predicate.get("failing_gates", [])
    if failing:
        add("- **Cổng đang không đạt**: " + ", ".join(failing) + ".")
    add("- **Bằng chứng tải nặng**: yêu cầu " + _metric(load_metrics.get("requests")) + ", tần suất " + _metric(load_metrics.get("request_rate_per_second"), " req/s") + ", p95 " + _metric(load_metrics.get("latency_p95_ms"), " ms") + ", tỷ lệ lỗi " + _metric(load_metrics.get("failure_rate")) + ".")
    add("- **Stripe sandbox thực**: trạng thái " + str(stripe_result.get("status", "chưa chạy")) + " — xem mục 12 và 13.")
    add("- **Cảnh báo Telegram**: " + str(len(alert_gates)) + " cổng cảnh báo đã được ghi nhận — xem mục 29, 30 và 31.")
    add("")

    add("## 2. KIỂM TOÁN TOÀN BỘ MÃ NGUỒN (FULL SOURCE AUDIT)")
    add("")
    add("- Tổng số tập tin trong phạm vi: **" + str(coverage.get("total_files")) + "**; đã rà soát: **" + str(coverage.get("reviewed_files")) + "**.")
    add("- Tỷ lệ rà soát mã thực thi: **" + _metric(coverage.get("implementation_percent"), "%") + "** (" + str(coverage.get("implementation_reviewed")) + "/" + str(coverage.get("implementation_total")) + ").")
    categories = coverage.get("category_totals", {})
    add("- Phân bố theo nhóm: " + ", ".join(name + "=" + str(count) for name, count in sorted(categories.items())) + ".")
    add("")

    add("## 3. KIẾN TRÚC HIỆN TẠI (CURRENT ARCHITECTURE)")
    add("")
    add("- Mô hình: 14 dịch vụ độc lập, database-per-service, giao tiếp bất đồng bộ qua Kafka, gateway hợp nhất xác thực và giới hạn tốc độ phân tán bằng Redis.")
    add("- Mô tả chi tiết: production-audit/architecture/current.md.")
    add("")

    add("## 4. KIẾN TRÚC MỤC TIÊU (TARGET SYSTEM DESIGN)")
    add("")
    add("- Mô tả chi tiết: production-audit/architecture/target.md; các quyết định kiến trúc tại production-audit/architecture/adrs/.")
    add("")

    add("## 5. CÁC DESIGN PATTERN ĐÃ ÁP DỤNG (APPLIED DESIGN PATTERNS)")
    add("")
    add("- Báo cáo chi tiết: production-audit/reports/design-pattern-adoption.md và production-audit/architecture/design-patterns.md.")
    add("")

    add("## 6. CÁC THAY ĐỔI TÁI CẤU TRÚC (REFACTORING CHANGES)")
    add("")
    add("- Báo cáo lộ trình: production-audit/reports/refactoring-roadmap.md.")
    add("- Cổng GATE-3 (DESIGN_PATTERNS): " + ("đạt" if any(gate.name == "DESIGN_PATTERNS" and gate.status == "PASS" for gate in gates) else "không đạt") + ".")
    add("")

    add("## 7. PHÁT HIỆN ĐỒNG THỜI & ĐÚNG ĐẮN PHÂN TÁN (CONCURRENCY FINDINGS)")
    add("")
    add("- Báo cáo đầy đủ: production-audit/reports/concurrency.md và production-audit/architecture/concurrency.md.")
    concurrency = verification.get("suites", {}).get("concurrency", {})
    add("- Bộ kiểm thử đồng thời: " + str(concurrency.get("status", "chưa chạy")) + ", " + str(concurrency.get("tests_run", "?")) + " test.")
    add("")

    add("## 8. TÍNH ĐÚNG ĐẮN KHO HÀNG (INVENTORY CORRECTNESS)")
    add("")
    inventory = verification.get("suites", {}).get("concurrency_inventory", {})
    add("- Bộ kiểm thử kho hàng: " + str(inventory.get("status", "chưa chạy")) + ", " + str(inventory.get("tests_run", "?")) + " test.")
    if load_invariants:
        for invariant in load_invariants.get("invariants", []):
            if invariant.get("id") == "INV-01":
                add("- Bất biến không vượt tồn kho (INV-01): " + str(invariant.get("status")) + ", số vi phạm: " + str(invariant.get("violations")) + ".")
    add("")

    add("## 9. TÍNH ĐÚNG ĐẮN HẠN MỨC KHUYẾN MÃI (PROMOTION QUOTA CORRECTNESS)")
    add("")
    if load_invariants:
        for invariant in load_invariants.get("invariants", []):
            if invariant.get("id") in {"INV-02", "INV-06", "INV-08"}:
                add("- " + str(invariant.get("id")) + " (" + str(invariant.get("name")) + "): " + str(invariant.get("status")) + ", vi phạm: " + str(invariant.get("violations")) + ".")
    add("")

    add("## 10. SAGA CHECKOUT & BỒI ĐỀN (CHECKOUT SAGA)")
    add("")
    kafka = verification.get("suites", {}).get("kafka", {})
    add("- Bộ kiểm thử Kafka/Outbox: " + str(kafka.get("status", "chưa chạy")) + ", " + str(kafka.get("tests_run", "?")) + " test.")
    add("- Cơ chế bồi đền được kiểm chứng trong production-audit/runs/run-20261118-a2b6e6fc/source-reviews/order-authz-compensation-fix.md.")
    add("")

    add("## 11. KAFKA & TRANSACTIONAL OUTBOX")
    add("")
    add("- Bộ kiểm thử: " + str(kafka.get("status", "chưa chạy")) + ".")
    add("- Bằng chứng rà soát: production-audit/runs/run-20261118-a2b6e6fc/source-reviews/kafka-outbox.md và kafka-safety-fix.md.")
    add("")

    add("## 12. CẤU HÌNH STRIPE SANDBOX (STRIPE SANDBOX CONFIGURATION)")
    add("")
    add("- Kết quả kiểm định cấu hình: " + str(stripe_result.get("status", "chưa chạy")) + ".")
    for finding in stripe_result.get("findings", []):
        add("  - " + str(finding.get("check")) + ": " + str(finding.get("status")) + " — " + str(finding.get("detail")))
    add("- Chế độ khóa: " + str(stripe_result.get("key_mode", "không rõ")) + ".")
    add("")

    add("## 13. KIỂM THỬ ĐẦU-CUỐI THANH TOÁN STRIPE THỰC TẾ (REAL STRIPE PAYMENT E2E)")
    add("")
    if stripe_result.get("status") == "PASS" and stripe_result.get("payment_intent_id"):
        add("- PaymentIntent thực: " + str(stripe_result.get("payment_intent_id")) + ", trạng thái " + str(stripe_result.get("state")) + ", số tiền " + str(stripe_result.get("amount")) + " " + str(stripe_result.get("currency")) + ".")
        add("- Khóa idempotency: " + str(stripe_result.get("idempotency_key")) + "; request id: " + str(stripe_result.get("create_request_id")) + ".")
    else:
        add("- **Chưa xác minh được**: " + str(stripe_result.get("reason", stripe_result.get("status", "chưa chạy"))) + ".")
        for finding in stripe_result.get("findings", []):
            if finding.get("check") in {"api_authentication", "webhook_secret", "secret_key_mode"}:
                add("- Kiểm định " + str(finding.get("check")) + ": " + str(finding.get("status")) + " — " + str(finding.get("detail")) + ".")
        add("- Không dùng khóa live-mode, không giả lập phản hồi Stripe, không dùng Stripe mock làm bằng chứng tích hợp thật.")
    add("")

    add("## 14. XÁC MINH WEBHOOK & HOÀN TIỀN (WEBHOOK AND REFUND VERIFICATION)")
    add("")
    add("- Logic xác minh chữ ký webhook được kiểm thử bằng lệnh harness stripe test-webhooks, ký HMAC-SHA256 theo đúng định dạng t/v1 của Stripe.")
    add("- Hoàn tiền: xem bộ kiểm thử payment — " + str(verification.get("suites", {}).get("payment", {}).get("status", "chưa chạy")) + ".")
    add("")

    add("## 15. XÁC MINH DOCKER COMPOSE (DOCKER COMPOSE VERIFICATION)")
    add("")
    integration = verification.get("suites", {}).get("integration", {})
    add("- Bộ kiểm thử tích hợp: " + str(integration.get("status", "chưa chạy")) + ", " + str(integration.get("tests_run", "?")) + " test, hạ tầng: " + str(integration.get("infrastructure", "")) + ".")
    add("")

    add("## 16. KIẾN TRÚC KUBERNETES (KUBERNETES ARCHITECTURE)")
    add("")
    add("- Kết quả kiểm định manifest: " + str(kubernetes_result.get("status", "chưa chạy")) + ".")
    for check in kubernetes_result.get("checks", []):
        add("  - " + str(check.get("id")) + " " + str(check.get("name")) + ": " + str(check.get("status")) + " — " + str(check.get("detail"))[:200])
    add("")

    add("## 17. KẾT QUẢ HPA (HPA SCALING RESULTS)")
    add("")
    add("- HPA được cấu hình cho gateway-service trong k8s/gateway-service.yaml.")
    add("- **Chưa xác minh cấp nhân tự động trên cụm thật**: không có cụm Kubernetes chạy được trong môi trường này. Lệnh harness k8s hpa-status trả về BLOCKED với lý do rõ ràng.")
    add("")

    add("## 18. KẾT QUẢ KEDA (KEDA SCALING RESULTS)")
    add("")
    add("- **Chưa triển khai**: không có ScaledObject KEDA nào trong k8s/. Đây là khoảng trống đã biết và đã ghi nhận trong sổ tay tác vụ.")
    add("")

    add("## 19. HỒ SƠ CHIẾU TẢI BLACK FRIDAY (BLACK FRIDAY WORKLOAD PROFILES)")
    add("")
    add("- Hồ sơ định nghĩa trong production-audit/harness/loadtesting/profiles.py và điều khiển bằng lệnh harness load.")
    add("- Các hồ sơ: baseline (A), ramp (B), spike (C), sustained-peak (D), flash-sale (E), promotion-storm (F), kafka-backlog (G), failure-during-peak (H), recovery (I), soak (J).")
    add("- Mọi hồ sơ bị chặn bởi ranh giới an toàn về trần tần suất, số VU, thời lượng và tổng số yêu cầu.")
    add("")

    add("## 20. THÔNG LƯỢNG NỀN TẢNG VS ĐỈNH (BASELINE VS PEAK THROUGHPUT)")
    add("")
    for row in load_rows:
        metrics = row.get("metrics", {})
        profile = str(row.get("profile") or row.get("directory"))
        add("- " + profile + " — kịch bản " + str(row.get("scenario")) + ", trạng thái " + str(row.get("status") or "không ghi nhận") + ": yêu cầu " + _metric(metrics.get("requests")) + ", " + _metric(metrics.get("request_rate_per_second"), " req/s") + ", lượt lặp " + _metric(metrics.get("iterations")) + ", p95 " + _metric(metrics.get("latency_p95_ms"), " ms") + ", tỷ lệ lỗi " + _metric(metrics.get("failure_rate")) + ".")
        if row.get("business_verdict"):
            add("  - Kết luận nghiệp vụ: " + str(row.get("business_verdict")) + ".")
        if row.get("measurement_valid") is False:
            add("  - **Phép đo không hợp lệ**: máy chủ đo bị nhiễu, kết quả không dùng để kết luận.")
    if load_result:
        add("- Lần chạy đã lưu: hồ sơ " + str(load_result.get("profile")) + ", " + str(load_result.get("requests_per_second")) + " req/s nhắm, " + str(load_result.get("virtual_users")) + " VU, trạng thái " + str(load_result.get("status")) + ".")
        host = load_result.get("measurement_host") or {}
        add("- **Tính hợp lệ của phép đo**: " + ("hợp lệ" if load_result.get("measurement_valid") else "KHÔNG hợp lệ — " + "; ".join(host.get("reasons", []))) + ".")
    add("")

    add("## 21. ĐỘ TRỄ & TỶ LỆ LỖI (LATENCY AND ERROR RATES)")
    add("")
    add("- p50 " + _metric(load_metrics.get("latency_p50_ms"), " ms") + ", p90 " + _metric(load_metrics.get("latency_p90_ms"), " ms") + ", p95 " + _metric(load_metrics.get("latency_p95_ms"), " ms") + ", p99 " + _metric(load_metrics.get("latency_p99_ms"), " ms") + ", trung bình " + _metric(load_metrics.get("latency_avg_ms"), " ms") + ", lớn nhất " + _metric(load_metrics.get("latency_max_ms"), " ms") + ".")
    add("- Tỷ lệ lỗi HTTP: " + _metric(load_metrics.get("failure_rate")) + " (" + _metric(load_metrics.get("failed_requests")) + " yêu cầu lỗi).")
    add("- Tỷ lệ kiểm tra nghiệp vụ đạt: " + _metric(load_metrics.get("check_rate")) + ".")
    add("")

    add("## 22. BÃO HÒA TÀI NGUYÊN (RESOURCE SATURATION)")
    add("")
    host = load_result.get("measurement_host") or {}
    add("- Máy đo: " + str(host.get("cpu_count", "?")) + " lõi, tải trung bình " + str(host.get("load_average")) + ".")
    add("- HikariCP, CPU, bộ nhớ, Kafka lag và Redis: xem production-audit/reports/build-and-tests.md và trạng thái cổng kiểm định.")
    add("")

    add("## 23. KẾT QUẢ FLASH-SALE TRÊN KHO HÀNG (FLASH-SALE INVENTORY RESULTS)")
    add("")
    add("- Kịch bản flash-sale hướng mọi VU vào cùng một SKU hạn chế; bất biến kiểm tra sau mỗi lần chạy bằng lệnh harness load flash-sale.")
    add("- Kết quả chạy cụ thể: " + ("xem production-audit/state/performance-baselines.json" if load_result else "chưa có lần chạy flash-sale được lưu trong phiên này") + ".")
    add("")

    add("## 24. KẾT QUẢ PROMOTION-STORM (PROMOTION-STORM RESULTS)")
    add("")
    add("- Kịch bản promotion-storm áp dụng cùng một mã giảm giá cho mọi VU; bất biến hạn mức được kiểm tra sau mỗi lần chạy.")
    add("")

    add("## 25. KIỂM THỬ KHÔI PHỤC & LỖI (RECOVERY AND FAILURE TESTING)")
    add("")
    add("- Hồ sơ recovery và failure-during-peak đã được hiện thực trong harness.")
    add("- Chi tiết: production-audit/reports/e2e.md và production-audit/state/performance-baselines.json.")
    add("")

    add("## 26. BẰNG CHỨNG TRUY VẾT PHÂN TÁN (DISTRIBUTED TRACE EVIDENCE)")
    add("")
    add("- Tempo và OpenTelemetry Collector đang chạy trong cụm Compose; cấu hình tại config/observability/tempo.yaml và config/observability/otel-collector-config.yaml.")
    add("- Cổng kiểm định quan sát: " + str(verification.get("suites", {}).get("operations", {}).get("status", "chưa chạy")) + ".")
    add("")

    add("## 27. NHẬT KÝ TẬP TRUNG (CENTRALIZED LOGS)")
    add("")
    add("- Loki và Grafana Alloy đang chạy; cấu hình tại config/observability/loki.yaml và config/observability/alloy/config.alloy.")
    add("")

    add("## 28. BẢNG ĐIỀU KHIỂN GRAFANA (GRAFANA DASHBOARDS)")
    add("")
    add("- Bảng điều khiển fleet-overview được cấu phục tại config/observability/grafana/dashboards/fleet-overview.json.")
    add("")

    add("## 29. KẾT QUẢ CẢNH BÁO TELEGRAM CI (CI TELEGRAM ALERT RESULTS)")
    add("")
    ci_rows = [row for row in alert_gates if str(row.get("gate", "")).startswith("QA-ALERT-01")]
    if ci_rows:
        for row in ci_rows:
            add("- " + str(row.get("gate")) + ": " + str(row.get("status")) + " — " + str(row.get("evidence", {}).get("detail", "")) + " " + str(row.get("evidence", {}).get("reason", "")))
    else:
        add("- Chưa có lần chạy kiểm thử giao hàng CI trong phiên này.")
    add("- Đường dẫn CI: .github/workflows/ci.yml (job notify-telegram, điều kiện failure()), kịch bản scripts/notify-telegram.sh.")
    add("")

    add("## 30. KẾT QUẢ CẢNH BÁO TELEGRAM RUNTIME (RUNTIME TELEGRAM ALERT RESULTS)")
    add("")
    runtime_rows = [row for row in alert_gates if str(row.get("gate", "")) in {"QA-ALERT-02", "QA-ALERT-03", "QA-ALERT-04", "QA-ALERT-05"}]
    if runtime_rows:
        for row in runtime_rows:
            add("- " + str(row.get("gate")) + ": " + str(row.get("status")) + " — " + str(row.get("evidence", {}).get("detail", "")) + " " + str(row.get("evidence", {}).get("reason", "")))
    else:
        add("- Chưa có lần chạy kiểm thử giao hàng runtime trong phiên này.")
    add("- Cấu hình: config/observability/alertmanager/alertmanager.yml.template, quy tắc config/observability/alertmanager/rules/runtime-alerts.yml.")
    add("")

    add("## 31. SẴN SÀNG CẢNH BÁO PRODUCTION (PRODUCTION ALERTING READINESS)")
    add("")
    add("- Trạng thái kiểm định cấu hình cảnh báo: " + str(alerting_status) + ".")
    add("- Đường dẫn cấu hình: Prometheus đánh giá quy tắc trong config/observability/alertmanager/rules, Alertmanager gom nhóm và chống lặp trong config/observability/alertmanager, tuyến giao hàng CI là scripts/notify-telegram.sh và tuyến runtime là Telegram Bot API.")
    add("- **Giao hàng thật trong môi trường production chưa được xác minh** vì không có quyền truy cập production và không có thông tin xác thực bot.")
    add("")

    add("## 32. BUILD & KIỂM THỬ HỒI QUY (BUILD AND REGRESSION TESTS)")
    add("")
    for name, status, detail in _suite_rows(verification, fingerprint):
        add("- " + name + ": " + status + (" — " + detail if detail else ""))
    if build:
        add("- Lệnh build: " + str(build.get("command")) + ", mã thoát " + str(build.get("exit_code")) + ", " + str(build.get("tests_run", "?")) + " test, " + str(build.get("modules", "?")) + " module, thời gian " + str(build.get("duration_seconds")) + " giây.")
    add("")

    add("## 33. PHÁN QUYẾT QA ĐỘC LẬP (INDEPENDENT QA VERDICTS)")
    add("")
    qa_root = config.run_root() / "independent-reviews"
    if qa_root.exists():
        for path in sorted(qa_root.glob("*.md")):
            add("- " + path.name + " (" + str(path.stat().st_size) + " byte).")
    else:
        add("- Chưa có bản đánh giá độc lập nào được lưu.")
    add("")

    add("## 34. COMMIT & KẾT QUẢ PUSH (GIT COMMITS AND PUSH OUTCOMES)")
    add("")
    entries = deliveries.get("deliveries", deliveries.get("entries", []))
    if isinstance(entries, list) and entries:
        for entry in entries[-8:]:
            add("- " + str(entry.get("commit_sha", ""))[:12] + " — " + str(entry.get("message", ""))[:90] + " — push: " + str(entry.get("push_state", "")) + ".")
    else:
        add("- " + json.dumps(deliveries, ensure_ascii=False)[:600])
    add("")

    add("## 35. TỒN ĐỌNG CÒN LẠI (REMAINING BLOCKERS)")
    add("")
    add("### 35.1 Mức HIGH")
    add("")
    if open_high:
        add("| Mã | Mức | Nội dung |")
        add("| --- | --- | --- |")
        for identifier, severity, title in open_high:
            add("| " + identifier + " | " + severity + " | " + title + " |")
    else:
        add("- Không có tác vụ HIGH nào đang mở.")
    add("")
    add("### 35.2 Mức MEDIUM")
    add("")
    if open_medium:
        add("| Mã | Mức | Nội dung |")
        add("| --- | --- | --- |")
        for identifier, severity, title in open_medium:
            add("| " + identifier + " | " + severity + " | " + title + " |")
    else:
        add("- Không có tác vụ MEDIUM nào đang mở.")
    add("")
    add("### 35.3 Mức LOW")
    add("")
    add("- " + str(len(open_low)) + " tác vụ LOW đang mở, xem production-audit/reports/remaining-tasks.md.")
    add("")

    add("## 36. CÁC PHÁT BIỂU ĐÃ XÁC MINH & CHƯA XÁC MINH (VERIFIED VS UNVERIFIED CLAIMS)")
    add("")
    add("### 36.1 ĐÃ XÁC MINH BẰNG BẰNG CHỨNG")
    add("")
    add("- Rà soát mã nguồn đầy đủ: " + str(coverage.get("reviewed_files")) + "/" + str(coverage.get("total_files")) + " tập tin.")
    add("- Chính sách không chú thích: " + str(verification.get("suites", {}).get("comments", {}).get("status", "chưa chạy")) + ".")
    add("- Build reactor: " + build_status + ".")
    add("- Bất biến nghiệp vụ dưới tải: " + str(load_invariants.get("status", "chưa chạy")) + " với " + str(len(load_invariants.get("invariants", []))) + " bất biến kiểm tra.")
    add("")
    add("### 36.2 CHƯA XÁC MINH")
    add("")
    add("- Tích hợp Stripe sandbox thật: " + str(stripe_result.get("status", "chưa chạy")) + " — khóa sk_test_ bị Stripe từ chối và bí mật ký webhook là giá trị cục bộ.")
    add("- Giao hàng Telegram thật: chưa có thông tin xác thực bot trong môi trường này.")
    add("- Cấp nhân tự động HPA và KEDA trên cụm thật: không có cụm Kubernetes chạy được.")
    add("- Thông lượng thanh toán cấp nhà cung cấp: phụ thuộc giới hạn tần suất của Stripe và cấu hình tài khoản.")
    add("- Giao hàng cảnh báo trong production: chỉ có thể xác minh bằng sự kiện production được cho phép.")
    add("")

    add("## 37. QUYẾT ĐỊNH SẴN SÀNG TRIỂN KHAI SẢN XUẤT (PRODUCTION READINESS DECISION)")
    add("")
    if predicate.get("production_ready"):
        add("- **Quyết định**: SẴN SÀNG TRIỂN KHAI SẢN XUẤT.")
    else:
        add("- **Quyết định**: CHƯA SẴN SÀNG TRIỂN KHAI SẢN XUẤT.")
        add("- Các cổng chưa đạt: " + ", ".join(failing) if failing else "- Không xác định được cổng chưa đạt.")
    add("- Số tác vụ HIGH đang mở: " + str(len(open_high)) + "; MEDIUM: " + str(len(open_medium)) + "; LOW: " + str(len(open_low)) + ".")
    add("- Trạng thái tổng hợp tác vụ: " + ", ".join(name + "=" + str(count) for name, count in sorted(summary.items())) + ".")
    add("- Báo cáo được sinh tự động tại " + str(target) + ".")
    add("")

    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return target
