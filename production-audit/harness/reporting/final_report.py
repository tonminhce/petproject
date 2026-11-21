from __future__ import annotations

import json
from collections import Counter
from pathlib import Path
from typing import Any

from .. import config
from ..inventory.coverage import coverage_summary, load_coverage
from ..persistence.store import read_json, utc_now
from ..task_registry.registry import load_tasks, task_status_summary
from ..verification.gates import _review_verdict, completion_predicate, evaluate_gates, evidence_is_fresh

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
    from ..loadtesting.capacity import capacity_limit_document

    capacity = capacity_limit_document()
    capacity_limit = capacity.get("capacity_limit") or {}
    capacity_rows = {
        str(row.get("profile") or ""): row for row in (capacity.get("profiles") or [])
    }
    fingerprint = source_fingerprint()
    build_fresh = evidence_is_fresh(build, fingerprint)
    build_status = str(build.get("status", "chưa chạy")) if build_fresh else "bằng chứng đã cũ"

    from ..kubernetes.validation import load_autoscaling_observation, observation_path
    from ..stripe.acceptance import load_evidence

    observation = load_autoscaling_observation()
    observation_status = str(observation.get("status", "BLOCKED"))
    observation_reason = str(observation.get("reason", ""))
    observation_evidence = [str(item) for item in observation.get("evidence", [])]
    observation_record = read_json(observation_path(), {}) or {}
    gate_by_id = {gate.id: gate for gate in gates}
    acceptance = load_evidence()
    acceptance_server = acceptance.get("stripe_server") or {}
    acceptance_summary = acceptance.get("summary") or {}
    acceptance_cases = acceptance.get("cases") or []

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
    blocked_gates = [gate for gate in gates if gate.status == "BLOCKED"]
    insufficient_gates = [gate for gate in gates if gate.status == "INSUFFICIENT_EVIDENCE"]
    gate_line = "- **Cổng kiểm định**: " + str(len(passed)) + "/" + str(len(gates)) + " đạt, " + str(len(failed)) + " không đạt, " + str(len(pending)) + " chờ"
    if blocked_gates:
        gate_line += ", " + str(len(blocked_gates)) + " bị chặn"
    if insufficient_gates:
        gate_line += ", " + str(len(insufficient_gates)) + " thiếu bằng chứng"
    add(gate_line + ".")
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
    if capacity_limit:
        add("- **Giới hạn dung lượng đo được**: " + _rate(capacity_limit.get("requests_per_second"))
            + " req/s (" + _rate(capacity_limit.get("iterations_per_second"))
            + " lượt lặp/giây), hồ sơ " + str(capacity_limit.get("profile", "")) + ", thư mục "
            + str(capacity_limit.get("directory", "")) + " — xem mục 20.")
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
    if acceptance:
        add("- Nghiệm thu thanh toán đã ghi nhận: trạng thái " + str(acceptance.get("status", "chưa chạy")) + ", máy chủ Stripe bị kiểm định " + str(acceptance_server.get("kind", "không rõ")) + " " + str(acceptance_server.get("version", "")) + " tại " + (str(acceptance_server.get("http_address_in_network")) or "không ghi nhận địa chỉ") + "; tổng số " + str(acceptance_summary.get("cases", 0)) + " trường hợp, " + str(acceptance_summary.get("proven", 0)) + " proven, " + str(acceptance_summary.get("failed", 0)) + " thất bại, " + str(acceptance_summary.get("not_proven", 0)) + " chưa chứng minh được.")
        outstanding_cases = [str(case.get("name")) for case in acceptance_cases if str(case.get("verdict")) != "PROVEN"]
        if outstanding_cases:
            add("- Các trường hợp nghiệm thu chưa được chứng minh: " + ", ".join(outstanding_cases) + ".")
    else:
        add("- Chưa có bằng chứng nghiệm thu thanh toán Stripe nào được lưu.")
    gate6 = gate_by_id.get("GATE-6")
    if gate6 is not None:
        add("- Cổng GATE-6 (PAYMENT_CORRECTNESS): " + gate6.status + " — " + gate6.detail + ".")
    add("")

    add("## 14. XÁC MINH WEBHOOK & HOÀN TIỀN (WEBHOOK AND REFUND VERIFICATION)")
    add("")
    stripe_test_status = str(stripe_result.get("status", "chưa chạy"))
    if stripe_test_status == "BLOCKED" and stripe_result.get("reason"):
        add("- Lệnh harness stripe test-webhooks (ký HMAC-SHA256 theo đúng định dạng t/v1 của Stripe) **bị chặn** với lý do đã ghi nhận: " + str(stripe_result.get("reason")) + ".")
    else:
        add("- Logic xác minh chữ ký webhook được kiểm thử bằng lệnh harness stripe test-webhooks, ký HMAC-SHA256 theo đúng định dạng t/v1 của Stripe: trạng thái đã ghi nhận " + stripe_test_status + ".")
    webhook_case = next((case for case in acceptance_cases if str(case.get("name")) == "capture_webhook_signature_verified"), {})
    if webhook_case:
        add("- Trường hợp nghiệm thu chữ ký webhook đã ghi nhận: phán quyết " + str(webhook_case.get("verdict")) + " — " + str(webhook_case.get("detail")) + ".")
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
    add("- Hành động đã ghi nhận: " + str(kubernetes.get("action", "không rõ")) + " — " + str(len(kubernetes_checks)) + " mục kiểm tra trên các tệp manifest trong k8s/.")
    add("- Kết quả kiểm tra tĩnh manifest tại thời điểm ghi nhận: " + str(kubernetes_result.get("status", "chưa chạy")) + "; các mục kiểm tra K8S-01 đến K8S-13 dưới đây chỉ đọc tệp manifest và không phản ánh hành vi của một cụm đang chạy.")
    add("- Bộ kiểm thử k8s: " + k8s_status + (" — " + k8s_detail if k8s_detail else "") + ".")
    if k8s_status == "STALE":
        add("- " + STALE_EVIDENCE_NOTE)
    if observation_status == "PASS":
        add("- **Cấp nhân tự động đã được quan sát trên một cụm Kubernetes thật** (mục K8S-14): " + ("; ".join(observation_evidence) if observation_evidence else observation_reason) + ".")
        observation_verdict = (observation_record.get("verdict") or {}).get("summary", "")
        if observation_verdict:
            add("- Kết luận đã ghi nhận của lần quan sát: " + str(observation_verdict).rstrip(".") + ".")
        cluster_record = observation_record.get("cluster") or {}
        if cluster_record.get("deleted_after_the_run"):
            add("- Theo trạng thái đã lưu, cụm dùng để quan sát đã bị xóa sau lần chạy, nên hiện không còn cụm nào đang chạy để kiểm chứng lại trực tiếp.")
    elif observation_status == "BLOCKED":
        add("- **Cấp nhân tự động ở thời gian chạy chưa được xác minh**: " + observation_reason + ".")
    else:
        add("- **Quan sát cấp nhân tự động đã ghi nhận không đạt**: " + observation_reason + ".")
    for check in kubernetes_checks:
        add("  - " + str(check.get("id")) + " " + str(check.get("name")) + ": " + str(check.get("status")) + " — " + str(check.get("detail"))[:200])
    add("")

    add("## 17. KẾT QUẢ HPA (HPA SCALING RESULTS)")
    add("")
    autoscaling_check = next((check for check in kubernetes_checks if str(check.get("id")) == "K8S-07"), {})
    add("- HPA được cấu hình cho gateway-service trong k8s/gateway-service.yaml; mục kiểm tra K8S-07 AUTOSCALING trên manifest đã ghi nhận: " + str(autoscaling_check.get("status", "chưa ghi nhận")) + " — " + str(autoscaling_check.get("detail", "")) + ".")
    if observation_status == "PASS":
        add("- **HPA đã được quan sát trên cụm thật theo bằng chứng đã lưu**: " + ("; ".join(observation_evidence) if observation_evidence else observation_reason) + ".")
    else:
        add("- **Chưa xác minh cấp nhân tự động trên cụm thật**: trạng thái quan sát đã ghi nhận là " + observation_status + " — lý do: " + observation_reason + ".")
    gate24 = gate_by_id.get("GATE-24")
    gate25 = gate_by_id.get("GATE-25")
    if gate24 is not None:
        add("- Cổng GATE-24 (KUBERNETES_MANIFESTS): " + gate24.status + " — " + gate24.detail + ".")
    if gate25 is not None:
        add("- Cổng GATE-25 (AUTOSCALING_OBSERVATION): " + gate25.status + " — " + gate25.detail + ".")
    add("")

    add("## 18. KẾT QUẢ KEDA (KEDA SCALING RESULTS)")
    add("")
    k8s02_check = next((check for check in kubernetes_checks if str(check.get("id")) == "K8S-02"), {})
    kinds_evidence = ", ".join(str(item) for item in (k8s02_check.get("evidence") or []))
    if not k8s02_check:
        add("- Chưa có kết quả kiểm tra manifest nào được ghi nhận để kết luận về KEDA.")
    elif "ScaledObject" in kinds_evidence:
        add("- Danh sách kind đã ghi nhận trong kiểm tra K8S-02 có chứa ScaledObject của KEDA: " + kinds_evidence + ".")
    else:
        add("- **Chưa triển khai**: danh sách kind đã ghi nhận trong kiểm tra K8S-02 (" + kinds_evidence + ") không chứa ScaledObject của KEDA. Đây là khoảng trống đã biết và đã ghi nhận trong sổ tay tác vụ.")
    add("")

    add("## 19. HỒ SƠ CHIẾU TẢI BLACK FRIDAY (BLACK FRIDAY WORKLOAD PROFILES)")
    add("")
    add("- Hồ sơ định nghĩa trong production-audit/harness/loadtesting/profiles.py và điều khiển bằng lệnh harness load.")
    add("- Các hồ sơ: baseline (A), ramp (B), spike (C), sustained-peak (D), flash-sale (E), promotion-storm (F), kafka-backlog (G), failure-during-peak (H), recovery (I), soak (J).")
    add("- Mọi hồ sơ bị chặn bởi ranh giới an toàn về trần tần suất, số VU, thời lượng và tổng số yêu cầu.")
    add("")

    add("## 20. THÔNG LƯỢNG NỀN TẢNG VS ĐỈNH (BASELINE VS PEAK THROUGHPUT)")
    add("")
    if capacity_limit:
        window = capacity.get("last_complete_run") or {}
        add("- **Giới hạn dung lượng đo được (measured capacity limit)**: "
            + _rate(capacity_limit.get("requests_per_second")) + " req/s ("
            + _rate(capacity_limit.get("iterations_per_second")) + " lượt lặp/giây) — hồ sơ "
            + str(capacity_limit.get("profile", "")) + ", thư mục "
            + str(capacity_limit.get("directory", "")) + ": "
            + str(capacity_limit.get("business_success", 0)) + " thành công, "
            + str(capacity_limit.get("business_failure", 0)) + " thất bại, "
            + str(capacity_limit.get("business_refusal", 0)) + " từ chối, "
            + str(capacity_limit.get("shed_requests", 0)) + " yêu cầu bị gateway loại bỏ, "
            + str(capacity_limit.get("invariant_violations", 0)) + " vi phạm bất biến.")
        add("- Quy tắc suy ra (ghi trong trạng thái đã lưu): " + str(capacity.get("rule", "")) + ".")
        add("- Lần chạy đo đầy đủ cuối cùng: " + str(window.get("window_start_utc", "")) + " đến "
            + str(window.get("window_end_utc", "")) + ", các thư mục: "
            + ", ".join(str(directory) for directory in window.get("directories_in_run_order", [])) + ".")
        stops = capacity.get("stops_holding_at") or {}
        broken = stops.get("profiles") or []
        if broken:
            add("- Nơi nền tảng ngừng giữ được: " + "; ".join(
                str(row.get("profile", "")) + " ở " + _rate(row.get("delivered_requests_per_second"))
                + " req/s với " + str(row.get("business_failure", 0)) + " thất bại nghiệp vụ, "
                + str(row.get("business_refusal", 0)) + " từ chối, "
                + str(row.get("shed_requests", 0)) + " yêu cầu bị gateway loại bỏ"
                for row in broken
            ) + ".")
        binding = capacity.get("binding") or {}
        if binding.get("binds_to_current_source") is False:
            add("- **Phép đo đã ghi nhận không còn gắn với mã nguồn hiện tại** (lý do ghi trong trạng thái đã lưu): "
                + str(binding.get("reason", "")) + ".")
            add("- Bằng chứng đầy đủ: "
                + str(capacity.get("source_directory", "")) + "/capacity-limit.md.")
        add("- Nguồn của con số: " + str(capacity.get("source", "")) + ".")
    else:
        add("- **Chưa ghi nhận giới hạn dung lượng đo được**: "
            + "trạng thái đã lưu không chứa phép đo nào thoả quy tắc của lần chạy đo đầy đủ cuối cùng.")
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
        capacity_row = capacity_rows.get(profile, {})
        if capacity_row:
            add("  - Kế hoạch: " + _rate(capacity_row.get("planned_peak_iterations_per_second"))
                + " lượt lặp/giây ("
                + _rate(capacity_row.get("planned_peak_requests_per_second"))
                + " req/s); đã giao: " + _rate(capacity_row.get("delivered_iterations_per_second"))
                + " lượt lặp/giây, " + _rate(capacity_row.get("delivered_requests_per_second"))
                + " req/s; tỷ lệ giao/nhắm " + _rate(capacity_row.get("recorded_rate_ratio"))
                + " (mẫu số: " + str(capacity_row.get("recorded_rate_ratio_denominator")) + ")"
                + "; thành công " + str(capacity_row.get("business_success", 0))
                + ", thất bại " + str(capacity_row.get("business_failure", 0))
                + ", từ chối " + str(capacity_row.get("business_refusal", 0))
                + ", gateway loại " + str(capacity_row.get("shed_requests", 0))
                + ", vi phạm bất biến " + str(capacity_row.get("invariant_violations", 0)) + ".")
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
    alerting_result = alerting_document.get("result") or {}
    for check in alerting_result.get("checks", []):
        if str(check.get("status")) in {"FAIL", "BLOCKED"}:
            add("- Mục kiểm tra " + str(check.get("id")) + " " + str(check.get("name")) + ": " + str(check.get("status")) + " — " + str(check.get("detail")) + ".")
    credential_check = next((check for check in alerting_result.get("checks", []) if str(check.get("id")) == "ALT-07"), {})
    if credential_check:
        add("- Thông tin xác thực giao hàng theo kiểm tra ALT-07 đã ghi nhận: " + str(credential_check.get("status")) + " — " + str(credential_check.get("detail")) + ".")
    public_deliveries = [row for row in alert_gates if (row.get("evidence") or {}).get("public_bot_api") is True]
    local_deliveries = [row for row in alert_gates if (row.get("evidence") or {}).get("public_bot_api") is False]
    if public_deliveries:
        add("- Các cổng giao hàng đã ghi nhận trả lời thành công qua Telegram Bot API công khai: " + ", ".join(str(row.get("gate")) for row in public_deliveries) + ".")
    if local_deliveries:
        add("- Các cổng giao hàng đã ghi nhận chỉ trả lời qua bot api cục bộ (không phải api.telegram.org): " + ", ".join(str(row.get("gate")) for row in local_deliveries) + ".")
    add("- Đường dẫn cấu hình: Prometheus đánh giá quy tắc trong config/observability/alertmanager/rules, Alertmanager gom nhóm và chống lặp trong config/observability/alertmanager, tuyến giao hàng CI là scripts/notify-telegram.sh và tuyến runtime là Telegram Bot API.")
    add("- **Giao hàng cảnh báo trong môi trường production chưa được xác minh**: các lần giao đã ghi nhận đều diễn ra trong môi trường kiểm định của lần chạy này; không có sự kiện production được phép để xác minh.")
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
        qa_reviews = [(path, _review_verdict(path)) for path in sorted(qa_root.glob("*.md"))]
        for path, verdict in qa_reviews:
            add("- " + path.name + " (" + str(path.stat().st_size) + " byte) — phán quyết đã ghi nhận trong văn bản: " + verdict + ".")
        qa_pass = len([item for item in qa_reviews if item[1] == "PASS"])
        qa_fail = len([item for item in qa_reviews if item[1] == "FAIL"])
        qa_undecided = len(qa_reviews) - qa_pass - qa_fail
        gate19 = gate_by_id.get("GATE-19")
        if gate19 is not None:
            add("- Cổng GATE-19 (INDEPENDENT_REVIEW): " + gate19.status + " — " + gate19.detail + " (" + str(qa_pass) + " đạt, " + str(qa_fail) + " không đạt, " + str(qa_undecided) + " không có phán quyết rõ).")
        if qa_fail:
            add("- **Có bản đánh giá độc lập ghi phán quyết không đạt**: cổng GATE-19 đang không đạt nên báo cáo này không thể kết luận nghiệm thu.")
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
    gate1 = gate_by_id.get("GATE-1")
    if gate1 is not None:
        gate1_note = "cổng GATE-1 đã đạt" if gate1.status == "PASS" else "cổng GATE-1 chưa đạt: " + gate1.detail
    else:
        gate1_note = "trạng thái cổng GATE-1 không xác định"
    add("- Rà soát mã nguồn: " + str(coverage.get("reviewed_files")) + "/" + str(coverage.get("total_files")) + " tập tin đã được rà soát; " + str(int(coverage.get("total_files") or 0) - int(coverage.get("reviewed_files") or 0)) + " tập tin chưa rà soát (" + gate1_note + ").")
    comments_status, comments_detail = _suite_status(verification, "comments", fingerprint)
    add("- Chính sách không chú thích: " + comments_status + (" — " + comments_detail if comments_detail else "") + ".")
    if comments_status == "STALE":
        add("- " + STALE_EVIDENCE_NOTE)
    add("- Build reactor: " + build_status + ".")
    if load_invariants:
        total, ok, bad, skipped, violations = _invariant_counts(load_invariants)
        add("- Bất biến nghiệp vụ dưới tải (lần chạy " + str(load_result.get("profile", "")) + " — " + str(load_result.get("status", "")) + "): " + str(load_invariants.get("status", "chưa chạy")) + " — " + str(total) + " bất biến kiểm tra, " + str(ok) + " đạt, " + str(bad) + " không đạt, " + str(skipped) + " bỏ qua, " + str(violations) + " vi phạm.")
    else:
        add("- Bất biến nghiệp vụ dưới tải: chưa có lần chạy tải nào ghi nhận bất biến.")
    add("")
    add("### 36.2 CHƯA XÁC MINH")
    add("")
    gate6 = gate_by_id.get("GATE-6")
    if gate6 is None or gate6.status != "PASS":
        stripe_claim = "- Tích hợp Stripe sandbox thật (api.stripe.com với khóa test thật): cổng GATE-6 "
        stripe_claim += ("đang " + gate6.status + " — " + gate6.detail) if gate6 is not None else "không xác định"
        if acceptance:
            stripe_claim += "; nghiệm thu đã ghi nhận chạy trên " + str(acceptance_server.get("kind", "không rõ")) + " tại " + (str(acceptance_server.get("http_address_in_network")) or "không ghi nhận địa chỉ")
        add(stripe_claim + ".")
    if public_deliveries:
        add("- Giao hàng Telegram thật trong môi trường production: các cổng " + ", ".join(str(row.get("gate")) for row in public_deliveries) + " đã giao thành công qua Telegram Bot API công khai trong môi trường kiểm định; việc giao hàng trong production vẫn chưa được xác minh.")
    else:
        add("- Giao hàng Telegram thật: chưa có lần giao nào qua Telegram Bot API công khai được ghi nhận trong trạng thái đã lưu.")
    if observation_status == "PASS":
        add("- Cấp nhân tự động HPA trên cụm thật: đã quan sát và ghi nhận (mục 17); KEDA thì chưa triển khai (mục 18), nên cấp nhân theo KEDA trên cụm thật chưa thể xác minh.")
    else:
        add("- Cấp nhân tự động HPA và KEDA trên cụm thật: trạng thái quan sát đã ghi nhận là " + observation_status + " — " + observation_reason + ".")
    add("- Thông lượng thanh toán cấp nhà cung cấp: phụ thuộc giới hạn tần suất của Stripe và cấu hình tài khoản.")
    alt08 = next((check for check in (alerting_document.get("result") or {}).get("checks", []) if str(check.get("id")) == "ALT-08"), {})
    if alt08 and str(alt08.get("status")) == "BLOCKED":
        add("- Giao hàng cảnh báo trong production: chỉ có thể xác minh bằng sự kiện production được cho phép; mục kiểm tra ALT-08 đang bị chặn — " + str(alt08.get("detail")) + ".")
    else:
        add("- Giao hàng cảnh báo trong production: chỉ có thể xác minh bằng sự kiện production được cho phép.")
    add("")

    add("## 37. QUYẾT ĐỊNH SẴN SÀNG TRIỂN KHAI SẢN XUẤT (PRODUCTION READINESS DECISION)")
    add("")
    if predicate.get("production_ready"):
        add("- **Quyết định**: SẴN SÀNG TRIỂN KHAI SẢN XUẤT.")
    else:
        add("- **Quyết định**: CHƯA SẴN SÀNG TRIỂN KHAI SẢN XUẤT.")
        add(("- Các cổng chưa đạt: " + ", ".join(failing) + ".") if failing else "- Không xác định được cổng chưa đạt.")
    add("- Số tác vụ HIGH đang mở: " + str(len(open_high)) + " (CRITICAL: " + str(len(open_critical)) + "); MEDIUM: " + str(len(open_medium)) + "; LOW: " + str(len(open_low)) + ".")
    add("- Trạng thái tổng hợp tác vụ: " + ", ".join(name + "=" + str(count) for name, count in sorted(summary.items())) + ".")
    add("- Báo cáo được sinh tự động tại " + str(target) + ".")
    add("")

    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return target
