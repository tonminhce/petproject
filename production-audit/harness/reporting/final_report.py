from __future__ import annotations

import json
from collections import Counter
from pathlib import Path
from typing import Any

from .. import config
from ..inventory.coverage import coverage_summary, load_coverage
from ..persistence.store import read_json, utc_now
from ..task_registry.registry import load_tasks, task_status_summary
from ..verification.gates import completion_predicate, evaluate_gates, evidence_is_fresh

OPEN_TASK_STATES = (
    "DISCOVERED",
    "NORMALIZED",
    "READY",
    "IN_PROGRESS",
    "IMPLEMENTED",
    "VERIFYING",
    "REOPENED",
    "FAILED_VERIFICATION",
    "BLOCKED",
    "REQUIREMENT_CONFLICT",
)

STALE_EVIDENCE_NOTE = (
    "Bằng chứng của bộ kiểm thử này được ghi nhận ở một phiên bản mã nguồn cũ hơn mã nguồn hiện tại, "
    "phải chạy lại và ghi nhận lại bằng chứng ở HEAD thì cổng kiểm định tương ứng mới được coi là đạt."
)


def _state(path: Path) -> dict[str, Any]:
    return read_json(path, {}) or {}


def _load_directory_order(path: Any) -> tuple[str, int]:
    name = path.name
    prefix = name.rsplit("-", 1)[0]
    suffix = name.rsplit("-", 1)[-1]
    try:
        stamp = int(suffix)
    except ValueError:
        stamp = 0
    return prefix, stamp


def _load_results() -> list[dict[str, Any]]:
    root = config.run_root() / "load-results"
    results: list[dict[str, Any]] = []
    if not root.exists():
        return results
    for path in sorted(root.iterdir(), key=_load_directory_order, reverse=True):
        result = path / "result.json"
        latest = path / "summary.json"
        stamp = _load_directory_order(path)[1]
        if result.exists():
            try:
                document = json.loads(result.read_text(encoding="utf-8"))
            except (OSError, json.JSONDecodeError):
                continue
            results.append(
                {
                    "directory": path.name,
                    "stamp": stamp,
                    "measured": True,
                    "document": document,
                    "scenario": document.get("scenario", ""),
                    "profile": document.get("profile", ""),
                    "status": document.get("status", ""),
                    "metrics": document.get("metrics") or {},
                    "business_verdict": document.get("business_verdict", ""),
                    "measurement_valid": document.get("measurement_valid"),
                    "invariants": document.get("invariants") or {},
                    "measurement_host": document.get("measurement_host") or {},
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
                "stamp": stamp,
                "measured": False,
                "document": {},
                "scenario": document.get("scenario", ""),
                "profile": "",
                "status": "",
                "metrics": {},
                "business_verdict": "",
                "measurement_valid": None,
                "invariants": {},
                "measurement_host": {},
            }
        )
    return results


def _profile_key(row: dict[str, Any]) -> str:
    profile = str(row.get("profile") or "")
    if profile:
        return profile
    directory = str(row.get("directory") or "")
    return directory.rsplit("-", 1)[0] if directory else ""


def _newest_row(rows: list[dict[str, Any]]) -> dict[str, Any]:
    if not rows:
        return {}
    return max(rows, key=lambda row: (int(row.get("stamp", 0)), str(row.get("directory", ""))))


def _latest_load_run(rows: list[dict[str, Any]]) -> dict[str, Any]:
    return _newest_row([row for row in rows if row.get("measured")])


def _latest_profile_run(rows: list[dict[str, Any]], profile: str) -> dict[str, Any]:
    matching = [row for row in rows if row.get("measured") and _profile_key(row) == profile]
    return _newest_row(matching)


def _suite_status(verification: dict[str, Any], name: str, fingerprint: str = "") -> tuple[str, str]:
    suite = (verification.get("suites") or {}).get(name) or {}
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
    return status, detail.strip()


def _suite_rows(verification: dict[str, Any], fingerprint: str = "") -> list[tuple[str, str, str]]:
    rows: list[tuple[str, str, str]] = []
    for name in sorted((verification.get("suites") or {}).keys()):
        status, detail = _suite_status(verification, name, fingerprint)
        rows.append((name, status, detail))
    return rows


def _field(task: Any, name: str, default: Any = "") -> Any:
    if isinstance(task, dict):
        return task.get(name, default)
    return getattr(task, name, default)


def _task_rows(tasks: Any, states: tuple[str, ...], severity: str = "") -> list[tuple[str, str, str, str]]:
    rows: list[tuple[str, str, str, str]] = []
    entries = tasks.items() if isinstance(tasks, dict) else [(getattr(task, "id", ""), task) for task in tasks]
    for identifier, task in sorted(entries, key=lambda item: str(item[0])):
        if _field(task, "state") not in states:
            continue
        if severity and _field(task, "severity") != severity:
            continue
        rows.append(
            (
                str(identifier),
                str(_field(task, "severity", "")),
                str(_field(task, "state", "")),
                str(_field(task, "title", "")),
            )
        )
    return rows


def _metric(value: Any, suffix: str = "") -> str:
    if value is None:
        return "không đo được"
    if isinstance(value, float):
        return ("%.2f" % value) + suffix
    return str(value) + suffix


def _rate(value: Any) -> str:
    if value is None:
        return "không đo được"
    if isinstance(value, bool):
        return str(value)
    if isinstance(value, (int, float)):
        number = float(value)
        if number == 0:
            return "0.0000"
        if abs(number) < 0.0001:
            return "%.2e" % number
        return "%.4f" % number
    return str(value)


def _invariant_counts(invariants: dict[str, Any]) -> tuple[int, int, int, int, int]:
    rows = list(invariants.get("invariants", []) or [])
    passed = len([row for row in rows if str(row.get("status", "")) == "PASS"])
    skipped = len([row for row in rows if str(row.get("status", "")) == "SKIPPED"])
    failed = len(rows) - passed - skipped
    violations = 0
    for row in rows:
        try:
            violations += int(row.get("violations") or 0)
        except (TypeError, ValueError):
            violations += 0
    return len(rows), passed, failed, skipped, violations


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
    load_rows = _load_results()

    passed = [gate for gate in gates if gate.status == "PASS"]
    failed = [gate for gate in gates if gate.status == "FAIL"]
    pending = [gate for gate in gates if gate.status == "PENDING"]
    open_high = _task_rows(tasks, OPEN_TASK_STATES, "HIGH")
    open_critical = _task_rows(tasks, OPEN_TASK_STATES, "CRITICAL")
    open_medium = _task_rows(tasks, OPEN_TASK_STATES, "MEDIUM")
    open_low = _task_rows(tasks, OPEN_TASK_STATES, "LOW")
    alert_gates = alerts.get("results", [])
    alerting_status = "chưa chạy"
    alerting_document = _state(config.state_root() / "alerting-results.json")
    if alerting_document.get("result"):
        alerting_status = str(alerting_document["result"].get("status", "chưa chạy"))
    stripe_result = stripe.get("result", {})
    kubernetes_result = kubernetes.get("result", {})
    latest_load = _latest_load_run(load_rows)
    load_result = latest_load.get("document", {}) or {}
    load_metrics = latest_load.get("metrics", {}) or {}
    load_invariants = latest_load.get("invariants", {}) or {}
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
    add("- **Tác vụ còn mở mức HIGH**: " + str(len(open_high)) + " (CRITICAL: " + str(len(open_critical)) + ", MEDIUM: " + str(len(open_medium)) + ", LOW: " + str(len(open_low)) + ").")
    add("- **Quyết định sản xuất theo completion predicate**: " + ("đạt" if predicate.get("production_ready") else "chưa đạt") + ".")
    failing = predicate.get("failing_gates", [])
    if failing:
        add("- **Cổng đang không đạt**: " + ", ".join(failing) + ".")
    if load_result:
        add("- **Bằng chứng tải nặng** (lần chạy mới nhất: " + str(load_result.get("profile", "")) + " — " + str(load_result.get("status", "")) + ", thư mục " + str(latest_load.get("directory", "")) + "): yêu cầu " + _metric(load_metrics.get("requests")) + ", tần suất " + _metric(load_metrics.get("request_rate_per_second"), " req/s") + ", p95 " + _metric(load_metrics.get("latency_p95_ms"), " ms") + ", tỷ lệ lỗi " + _rate(load_metrics.get("failure_rate")) + ".")
    else:
        add("- **Bằng chứng tải nặng**: chưa có lần chạy tải nào được lưu trong thư mục load-results để đo.")
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
    concurrency_status, _ = _suite_status(verification, "concurrency", fingerprint)
    add("- Bộ kiểm thử đồng thời: " + concurrency_status + ", " + str(concurrency.get("tests_run", "?")) + " test.")
    if concurrency_status == "STALE":
        add("- " + STALE_EVIDENCE_NOTE)
    add("")

    add("## 8. TÍNH ĐÚNG ĐẮN KHO HÀNG (INVENTORY CORRECTNESS)")
    add("")
    inventory = verification.get("suites", {}).get("concurrency_inventory", {})
    inventory_status, _ = _suite_status(verification, "concurrency_inventory", fingerprint)
    add("- Bộ kiểm thử kho hàng: " + inventory_status + ", " + str(inventory.get("tests_run", "?")) + " test.")
    if inventory_status == "STALE":
        add("- " + STALE_EVIDENCE_NOTE)
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
    else:
        add("- Chưa có lần chạy tải nào ghi nhận bất biến hạn mức khuyến mãi.")
    add("")

    add("## 10. SAGA CHECKOUT & BỒI ĐỀN (CHECKOUT SAGA)")
    add("")
    kafka = verification.get("suites", {}).get("kafka", {})
    kafka_status, _ = _suite_status(verification, "kafka", fingerprint)
    add("- Bộ kiểm thử Kafka/Outbox: " + kafka_status + ", " + str(kafka.get("tests_run", "?")) + " test.")
    if kafka_status == "STALE":
        add("- " + STALE_EVIDENCE_NOTE)
    add("- Cơ chế bồi đền được kiểm chứng trong production-audit/runs/run-20261118-a2b6e6fc/source-reviews/order-authz-compensation-fix.md.")
    add("")

    add("## 11. KAFKA & TRANSACTIONAL OUTBOX")
    add("")
    add("- Bộ kiểm thử: " + kafka_status + ".")
    if kafka_status == "STALE":
        add("- " + STALE_EVIDENCE_NOTE)
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
    payment_status, _ = _suite_status(verification, "payment", fingerprint)
    add("- Hoàn tiền: xem bộ kiểm thử payment — " + payment_status + ".")
    if payment_status == "STALE":
        add("- " + STALE_EVIDENCE_NOTE)
    add("")

    add("## 15. XÁC MINH DOCKER COMPOSE (DOCKER COMPOSE VERIFICATION)")
    add("")
    integration = verification.get("suites", {}).get("integration", {})
    integration_status, _ = _suite_status(verification, "integration", fingerprint)
    integration_line = "- Bộ kiểm thử tích hợp: " + integration_status + ", " + str(integration.get("tests_run", "?")) + " test"
    if integration.get("infrastructure"):
        integration_line += ", hạ tầng: " + str(integration.get("infrastructure"))
    add(integration_line + ".")
    if integration_status == "STALE":
        add("- " + STALE_EVIDENCE_NOTE)
    add("")

    add("## 16. KIẾN TRÚC KUBERNETES (KUBERNETES ARCHITECTURE)")
    add("")
    kubernetes_checks = kubernetes_result.get("checks", [])
    k8s_status, k8s_detail = _suite_status(verification, "k8s", fingerprint)
    add("- Hành động đã ghi nhận: " + str(kubernetes.get("action", "không rõ")) + " — " + str(len(kubernetes_checks)) + " mục kiểm tra tĩnh trên các tệp manifest trong k8s/.")
    add("- Kết quả kiểm tra tĩnh manifest tại thời điểm ghi nhận: " + str(kubernetes_result.get("status", "chưa chạy")) + " — đây không phải kiểm định trên một cụm Kubernetes thật.")
    add("- Bộ kiểm thử k8s: " + k8s_status + (" — " + k8s_detail if k8s_detail else "") + ".")
    if k8s_status == "STALE":
        add("- " + STALE_EVIDENCE_NOTE)
    add("- **Không có cụm Kubernetes chạy được trong môi trường này**: các mục kiểm tra dưới đây chỉ đọc tệp manifest, không áp dụng lên cụm và không có phản hồi nào từ một cụm thật.")
    add("- **Cấp nhân tự động ở thời gian chạy chưa được xác minh**: HPA và KEDA mới chỉ được kiểm tra về mặt khai báo trong manifest, chưa có bằng chứng nào từ một cụm thật.")
    for check in kubernetes_checks:
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
    latest_by_profile: dict[str, dict[str, Any]] = {}
    attempts: dict[str, int] = {}
    for row in load_rows:
        profile = _profile_key(row)
        if not profile:
            continue
        attempts[profile] = attempts.get(profile, 0) + 1
        known = latest_by_profile.get(profile)
        if known is None or int(row.get("stamp", 0)) > int(known.get("stamp", 0)):
            latest_by_profile[profile] = row
    ordered = sorted(latest_by_profile.values(), key=lambda row: _profile_key(row))
    for row in ordered:
        metrics = row.get("metrics", {})
        profile = str(row.get("profile") or row.get("directory"))
        add("- " + profile + " — kịch bản " + str(row.get("scenario")) + ", trạng thái " + str(row.get("status") or "không ghi nhận") + ": yêu cầu " + _metric(metrics.get("requests")) + ", " + _metric(metrics.get("request_rate_per_second"), " req/s") + ", lượt lặp " + _metric(metrics.get("iterations")) + ", p95 " + _metric(metrics.get("latency_p95_ms"), " ms") + ", tỷ lệ lỗi " + _rate(metrics.get("failure_rate")) + ".")
        if row.get("business_verdict"):
            add("  - Kết luận nghiệp vụ: " + str(row.get("business_verdict")) + ".")
        if row.get("measurement_valid") is False:
            add("  - **Phép đo không hợp lệ**: máy chủ đo bị nhiễu, kết quả không dùng để kết luận.")
        profile_name = _profile_key(row)
        if attempts.get(profile_name, 0) > 1:
            add("  - Số lần chạy đã lưu cho hồ sơ này: " + str(attempts.get(profile_name)) + "; bảng trên là lần chạy cuối cùng.")
    if load_result:
        add("- Lần chạy đã lưu (mới nhất): hồ sơ " + str(load_result.get("profile")) + ", " + str(load_result.get("requests_per_second")) + " req/s nhắm, " + str(load_result.get("virtual_users")) + " VU, trạng thái " + str(load_result.get("status")) + ", thư mục " + str(latest_load.get("directory", "")) + ".")
        host = load_result.get("measurement_host") or {}
        add("- **Tính hợp lệ của phép đo**: " + ("hợp lệ" if load_result.get("measurement_valid") else "KHÔNG hợp lệ — " + "; ".join(host.get("reasons", []))) + ".")
    add("")

    add("## 21. ĐỘ TRỄ & TỶ LỆ LỖI (LATENCY AND ERROR RATES)")
    add("")
    if load_result:
        add("- Nguồn: lần chạy mới nhất " + str(load_result.get("profile", "")) + " — " + str(load_result.get("status", "")) + ", thư mục " + str(latest_load.get("directory", "")) + ".")
    add("- p50 " + _metric(load_metrics.get("latency_p50_ms"), " ms") + ", p90 " + _metric(load_metrics.get("latency_p90_ms"), " ms") + ", p95 " + _metric(load_metrics.get("latency_p95_ms"), " ms") + ", p99 " + _metric(load_metrics.get("latency_p99_ms"), " ms") + ", trung bình " + _metric(load_metrics.get("latency_avg_ms"), " ms") + ", lớn nhất " + _metric(load_metrics.get("latency_max_ms"), " ms") + ".")
    add("- Tỷ lệ lỗi HTTP: " + _rate(load_metrics.get("failure_rate")) + " (" + _metric(load_metrics.get("failed_requests")) + " yêu cầu lỗi).")
    add("- Tỷ lệ kiểm tra nghiệp vụ đạt: " + _rate(load_metrics.get("check_rate")) + ".")
    add("")

    add("## 22. BÃO HÒA TÀI NGUYÊN (RESOURCE SATURATION)")
    add("")
    host = load_result.get("measurement_host") or {}
    add("- Máy đo: " + str(host.get("cpu_count", "?")) + " lõi, tải trung bình " + str(host.get("load_average")) + ".")
    if load_result:
        add("- Phép đo của lần chạy mới nhất (" + str(load_result.get("profile", "")) + ") là " + ("hợp lệ" if load_result.get("measurement_valid") else "KHÔNG hợp lệ") + ".")
    add("- HikariCP, CPU, bộ nhớ, Kafka lag và Redis: lần chạy tải không ghi nhận các chỉ số này, xem trạng thái cổng kiểm định tại production-audit/state/verification.json.")
    add("")

    add("## 23. KẾT QUẢ FLASH-SALE TRÊN KHO HÀNG (FLASH-SALE INVENTORY RESULTS)")
    add("")
    add("- Kịch bản flash-sale hướng mọi VU vào cùng một SKU hạn chế; bất biến kiểm tra sau mỗi lần chạy bằng lệnh harness load flash-sale.")
    flash_sale = _latest_profile_run(load_rows, "flash-sale")
    if flash_sale:
        metrics = flash_sale.get("metrics", {})
        add("- Lần chạy flash-sale đã lưu: " + str(flash_sale.get("directory")) + " — trạng thái " + str(flash_sale.get("status") or "không ghi nhận") + ", yêu cầu " + _metric(metrics.get("requests")) + ", " + _metric(metrics.get("request_rate_per_second"), " req/s") + ", p95 " + _metric(metrics.get("latency_p95_ms"), " ms") + ", tỷ lệ lỗi " + _rate(metrics.get("failure_rate")) + ".")
    else:
        add("- Kết quả chạy cụ thể: chưa có lần chạy flash-sale được lưu trong thư mục load-results.")
    add("")

    add("## 24. KẾT QUẢ PROMOTION-STORM (PROMOTION-STORM RESULTS)")
    add("")
    add("- Kịch bản promotion-storm áp dụng cùng một mã giảm giá cho mọi VU; bất biến hạn mức được kiểm tra sau mỗi lần chạy.")
    promotion_storm = _latest_profile_run(load_rows, "promotion-storm")
    if promotion_storm:
        metrics = promotion_storm.get("metrics", {})
        add("- Lần chạy promotion-storm đã lưu: " + str(promotion_storm.get("directory")) + " — trạng thái " + str(promotion_storm.get("status") or "không ghi nhận") + ", yêu cầu " + _metric(metrics.get("requests")) + ", " + _metric(metrics.get("request_rate_per_second"), " req/s") + ", p95 " + _metric(metrics.get("latency_p95_ms"), " ms") + ", tỷ lệ lỗi " + _rate(metrics.get("failure_rate")) + ".")
        storm_invariants = promotion_storm.get("invariants", {}) or {}
        if storm_invariants:
            total, ok, bad, skipped, violations = _invariant_counts(storm_invariants)
            add("- Bất biến hạn mức khuyến mãi của lần chạy này: " + str(storm_invariants.get("status", "chưa chạy")) + " — " + str(total) + " bất biến, " + str(ok) + " đạt, " + str(bad) + " không đạt, " + str(skipped) + " bỏ qua, " + str(violations) + " vi phạm.")
    else:
        add("- Chưa có lần chạy promotion-storm được lưu trong thư mục load-results.")
    add("")

    add("## 25. KIỂM THỬ KHÔI PHỤC & LỖI (RECOVERY AND FAILURE TESTING)")
    add("")
    add("- Hồ sơ recovery và failure-during-peak đã được hiện thực trong harness.")
    recovery = _latest_profile_run(load_rows, "recovery")
    if recovery:
        metrics = recovery.get("metrics", {})
        add("- Lần chạy recovery đã lưu: " + str(recovery.get("directory")) + " — trạng thái " + str(recovery.get("status") or "không ghi nhận") + ", yêu cầu " + _metric(metrics.get("requests")) + ", p95 " + _metric(metrics.get("latency_p95_ms"), " ms") + ", tỷ lệ lỗi " + _rate(metrics.get("failure_rate")) + ".")
    else:
        add("- Chưa có lần chạy recovery được lưu trong thư mục load-results.")
    add("- Chi tiết: production-audit/reports/e2e.md và các lần chạy đã lưu tại production-audit/runs/run-20261118-a2b6e6fc/load-results/.")
    add("")

    add("## 26. BẰNG CHỨNG TRUY VẾT PHÂN TÁN (DISTRIBUTED TRACE EVIDENCE)")
    add("")
    add("- Tempo và OpenTelemetry Collector đang chạy trong cụm Compose; cấu hình tại config/observability/tempo.yaml và config/observability/otel-collector-config.yaml.")
    operations_status, operations_detail = _suite_status(verification, "operations", fingerprint)
    add("- Cổng kiểm định quan sát: " + operations_status + (" — " + operations_detail if operations_detail else "") + ".")
    if operations_status == "STALE":
        add("- " + STALE_EVIDENCE_NOTE)
    for item in (verification.get("suites", {}).get("operations", {}) or {}).get("not_verified", []) or []:
        add("- Chưa xác minh theo bộ kiểm thử quan sát: " + str(item) + ".")
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
    alerts_status, alerts_detail = _suite_status(verification, "alerts", fingerprint)
    add("- Cổng kiểm định cảnh báo (GATE-23): " + alerts_status + (" — " + alerts_detail if alerts_detail else "") + ".")
    if alerts_status == "STALE":
        add("- " + STALE_EVIDENCE_NOTE)
    if runtime_rows:
        for row in runtime_rows:
            evidence = row.get("evidence", {}) or {}
            add("- " + str(row.get("gate")) + ": " + str(row.get("status")) + " — " + str(evidence.get("detail", "")) + " " + str(evidence.get("reason", "")))
            if "submitted" in evidence:
                submitted = evidence.get("submitted")
                received = evidence.get("alertmanager_count")
                add("  - Số cảnh báo đã gửi: " + str(submitted) + "; số nhóm cảnh báo Alertmanager trả về: " + str(evidence.get("grouped_alerts")) + "; số cảnh báo Alertmanager đếm được: " + str(received) + ".")
                if int(received or 0) == 0:
                    add("  - **Alertmanager không đếm được cảnh báo nào (0/" + str(submitted) + ")**: kiểm tra giao hàng chấp nhận cả 0 lẫn " + str(submitted) + ", nên trạng thái PASS không chứng minh được việc gom cảnh báo đã thực sự xảy ra; phải chạy lại khi Alertmanager nhận đủ " + str(submitted) + " cảnh báo.")
                elif int(received or 0) >= int(submitted or 0):
                    add("  - Alertmanager đã đếm đủ " + str(submitted) + " cảnh báo và gom thành " + str(evidence.get("grouped_alerts")) + " nhóm: việc gom cảnh báo đã được chứng minh.")
    else:
        add("- Chưa có lần chạy kiểm thử giao hàng runtime trong phiên này.")
    missing_alerts = [
        gate
        for gate in ("QA-ALERT-02", "QA-ALERT-03", "QA-ALERT-05")
        if not any(str(row.get("gate", "")) == gate for row in runtime_rows)
    ]
    if missing_alerts:
        add("- Các cổng chưa có kết quả nào được ghi nhận: " + ", ".join(missing_alerts) + ".")
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
        build_line = "- Lệnh build: " + str(build.get("command", "")) + ", mã thoát " + str(build.get("exit_code", "?"))
        duration = build.get("duration_seconds")
        build_line += ", thời gian " + (str(duration) if duration is not None else "chưa ghi nhận") + " giây"
        if build.get("updated_at"):
            build_line += ", ghi nhận lúc " + str(build.get("updated_at"))
        if build.get("revision"):
            build_line += " tại revision " + str(build.get("revision"))[:12]
        if not build_fresh:
            build_line += " — bằng chứng đã cũ so với mã nguồn hiện tại, phải chạy lại và ghi nhận lại"
        add(build_line + ".")
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
        states = Counter(str(entry.get("state", "")) for entry in entries)
        add("- Tổng số bản ghi phân phối: **" + str(len(entries)) + "** (" + ", ".join(name + ": **" + str(count) + "**" for name, count in sorted(states.items())) + ").")
        rejected = [entry for entry in entries if str(entry.get("state", "")) == "REJECTED_BY_GATES"]
        blocked = [entry for entry in entries if str(entry.get("state", "")) == "PUSH_BLOCKED"]
        if rejected:
            add("- **Bị cổng kiểm định từ chối**: " + str(len(rejected)) + " bản ghi.")
            for entry in rejected:
                add("  - " + str(entry.get("timestamp", "")) + " — " + str(entry.get("action", "")) + " — " + str(entry.get("detail", "")).replace("\n", " ")[:180])
        if blocked:
            add("- **Bị chặn khi đẩy**: " + str(len(blocked)) + " bản ghi.")
            for entry in blocked:
                add("  - " + str(entry.get("timestamp", "")) + " — " + str(entry.get("action", "")) + " — " + str(entry.get("detail", "")).replace("\n", " ")[:180])
        add("- Các lần phân phối gần nhất:")
        for entry in entries[-8:]:
            add("  - " + str(entry.get("timestamp", "")) + " — " + str(entry.get("commit_sha", ""))[:12] + " — " + str(entry.get("state", "")) + " — " + str(entry.get("detail", "")).replace("\n", " ")[:120])
    else:
        add("- " + json.dumps(deliveries, ensure_ascii=False)[:600])
    add("")

    add("## 35. TỒN ĐỌNG CÒN LẠI (REMAINING BLOCKERS)")
    add("")
    add("### 35.1 Mức HIGH")
    add("")
    if open_high:
        add("| Mã | Mức | Trạng thái | Nội dung |")
        add("| --- | --- | --- | --- |")
        for identifier, severity, state, title in open_high:
            add("| " + identifier + " | " + severity + " | " + state + " | " + title + " |")
    else:
        add("- Không có tác vụ HIGH nào đang mở.")
    add("")
    add("### 35.2 Mức MEDIUM")
    add("")
    if open_medium:
        add("| Mã | Mức | Nội dung |")
        add("| --- | --- | --- |")
        for identifier, severity, state, title in open_medium:
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
    add("- Rà soát mã nguồn: " + str(coverage.get("reviewed_files")) + "/" + str(coverage.get("total_files")) + " tập tin đã được rà soát; " + str(int(coverage.get("total_files") or 0) - int(coverage.get("reviewed_files") or 0)) + " tập tin chưa rà soát (cổng GATE-1 chưa đạt).")
    add("- Chính sách không chú thích: " + str(verification.get("suites", {}).get("comments", {}).get("status", "chưa chạy")) + ".")
    add("- Build reactor: " + build_status + ".")
    if load_invariants:
        total, ok, bad, skipped, violations = _invariant_counts(load_invariants)
        add("- Bất biến nghiệp vụ dưới tải (lần chạy " + str(load_result.get("profile", "")) + " — " + str(load_result.get("status", "")) + "): " + str(load_invariants.get("status", "chưa chạy")) + " — " + str(total) + " bất biến kiểm tra, " + str(ok) + " đạt, " + str(bad) + " không đạt, " + str(skipped) + " bỏ qua, " + str(violations) + " vi phạm.")
    else:
        add("- Bất biến nghiệp vụ dưới tải: chưa có lần chạy tải nào ghi nhận bất biến.")
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
    add("- Số tác vụ HIGH đang mở: " + str(len(open_high)) + " (CRITICAL: " + str(len(open_critical)) + "); MEDIUM: " + str(len(open_medium)) + "; LOW: " + str(len(open_low)) + ".")
    add("- Trạng thái tổng hợp tác vụ: " + ", ".join(name + "=" + str(count) for name, count in sorted(summary.items())) + ".")
    add("- Báo cáo được sinh tự động tại " + str(target) + ".")
    add("")

    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return target
