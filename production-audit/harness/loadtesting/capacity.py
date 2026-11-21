from __future__ import annotations

import subprocess
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from .. import config
from ..persistence.store import read_json, utc_now, write_json
from .profiles import requests_per_iteration
from .runner import results_root

CAPACITY_STATE_KEY = "capacity_limit"
CAPACITY_REPORT_NAME = "capacity-limit.md"
CAPACITY_RULE = (
    "the highest rate the platform delivered in the last complete measured run with zero business failures "
    "and zero invariant violations"
)
CURRENT_HARNESS_FIELDS = (
    "rate_unit",
    "requests_per_iteration",
    "target_iterations_per_second",
    "planned_iteration_rate_per_second",
    "planned_request_rate_per_second",
    "arrival_plan",
)
PEAK_RATE_MARKER = "iterations/s"
HARNESS_PATHS = (
    "production-audit/harness/loadtesting",
    "load-tests/k6-scenario.js",
)
SERVICE_PATHS = (
    "auth-service",
    "favourite-service",
    "gateway-service",
    "inventory-service",
    "media-service",
    "notification-service",
    "order-service",
    "payment-service",
    "product-service",
    "promotion-service",
    "rating-service",
    "search-service",
    "shipping-service",
    "tax-service",
    "utils",
)


def capacity_state_path() -> Path:
    return config.state_root() / "performance-baselines.json"


def _relative(path: Path | str) -> str:
    candidate = Path(path)
    try:
        return str(candidate.relative_to(config.repository_root()))
    except ValueError:
        return str(candidate)


def _as_int(value: Any) -> int:
    try:
        return int(value or 0)
    except (TypeError, ValueError):
        return 0


def _as_float(value: Any) -> float:
    try:
        return float(value)
    except (TypeError, ValueError):
        return 0.0


def _stamp(directory: Path) -> int:
    suffix = directory.name.rsplit("-", 1)[-1]
    try:
        return int(suffix)
    except ValueError:
        return 0


def _stamp_to_utc(stamp: int) -> str:
    if stamp <= 0:
        return ""
    return datetime.fromtimestamp(stamp, tz=timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def _iso_to_epoch(text: str) -> int:
    try:
        return int(datetime.strptime(text.strip(), "%Y-%m-%d %H:%M:%S %z").timestamp())
    except ValueError:
        return 0


def _planned_peak_iteration_rate(directory: Path) -> float | None:
    log = directory / "k6-stdout.log"
    if not log.exists():
        return None
    try:
        text = log.read_text(encoding="utf-8", errors="ignore")
    except OSError:
        return None
    for line in text.split("\n"):
        if PEAK_RATE_MARKER not in line or "Up to" not in line:
            continue
        head = line.split(PEAK_RATE_MARKER, 1)[0]
        value = head.rsplit("Up to", 1)[-1].strip()
        try:
            return float(value)
        except ValueError:
            return None
    return None


def _dropped_iterations(directory: Path) -> int:
    summary = read_json(directory / "k6-summary.json", {}) or {}
    metrics = summary.get("metrics") or {}
    dropped = metrics.get("dropped_iterations")
    if isinstance(dropped, dict):
        return _as_int(dropped.get("count"))
    return 0


def _invariant_violations(invariants: dict[str, Any]) -> int:
    total = 0
    for row in invariants.get("invariants") or []:
        total += _as_int(row.get("violations"))
    return total


def _profile_row(directory: Path, document: dict[str, Any]) -> dict[str, Any]:
    metrics = document.get("metrics") or {}
    invariants = document.get("invariants") or {}
    iterations = _as_float(metrics.get("iterations"))
    requests = _as_float(metrics.get("requests"))
    delivered_iterations = metrics.get("iteration_rate_per_second")
    if delivered_iterations is None:
        delivered_iterations = document.get("achieved_iteration_rate_per_second")
    delivered_iterations = _as_float(delivered_iterations)
    delivered_requests = _as_float(metrics.get("request_rate_per_second"))
    observed_per_iteration = round(requests / iterations, 4) if iterations > 0 else 0.0
    planned_peak = _planned_peak_iteration_rate(directory)
    declared_per_iteration = requests_per_iteration(str(document.get("scenario") or ""))
    missing = [name for name in CURRENT_HARNESS_FIELDS if name not in document]
    return {
        "profile": str(document.get("profile") or directory.name.rsplit("-", 1)[0]),
        "scenario": str(document.get("scenario") or ""),
        "status": str(document.get("status") or ""),
        "directory": directory.name,
        "result_file": _relative(directory / "result.json"),
        "planned_peak_iterations_per_second": planned_peak,
        "planned_peak_requests_per_second": round(planned_peak * declared_per_iteration, 4)
        if planned_peak is not None
        else None,
        "requests_per_iteration_declared": declared_per_iteration,
        "requests_per_iteration_observed": observed_per_iteration,
        "delivered_iterations_per_second": round(delivered_iterations, 4),
        "delivered_requests_per_second": round(delivered_requests, 4),
        "recorded_rate_ratio": document.get("achieved_rate_ratio"),
        "recorded_rate_ratio_denominator": "target_requests_per_second",
        "recorded_rate_unit": document.get("rate_unit") or "iterations per second against the request rate target",
        "iterations": _as_int(metrics.get("iterations")),
        "requests": _as_int(metrics.get("requests")),
        "dropped_iterations": _dropped_iterations(directory),
        "k6_exit_code": document.get("k6_exit_code"),
        "business_success": _as_int(metrics.get("business_success")),
        "business_failure": _as_int(metrics.get("business_failure")),
        "business_refusal": _as_int(metrics.get("business_refusal")),
        "shed_requests": _as_int(metrics.get("shed_requests")),
        "invariant_violations": _invariant_violations(invariants),
        "invariant_status": str(invariants.get("status") or ""),
        "invariant_failed": list(invariants.get("failed") or []),
        "business_failure_rate": document.get("business_failure_rate"),
        "shed_rate": document.get("shed_rate"),
        "measurement_valid": bool(document.get("measurement_valid")),
        "business_verdict": str(document.get("business_verdict") or ""),
        "current_harness_fields_missing": missing,
    }


def _measured_directories(root: Path) -> list[Path]:
    if not root.exists():
        return []
    directories = []
    for candidate in root.iterdir():
        if candidate.is_dir() and (candidate / "result.json").exists():
            directories.append(candidate)
    return sorted(directories, key=_stamp)


def sweep_rows(root: Path | None = None) -> list[dict[str, Any]]:
    base = root if root is not None else results_root()
    newest: dict[str, tuple[int, Path, dict[str, Any]]] = {}
    for directory in _measured_directories(base):
        document = read_json(directory / "result.json", {}) or {}
        profile = str(document.get("profile") or directory.name.rsplit("-", 1)[0])
        stamp = _stamp(directory)
        known = newest.get(profile)
        if known is None or stamp > known[0]:
            newest[profile] = (stamp, directory, document)
    rows = [_profile_row(directory, document) for _, directory, document in newest.values()]
    rows.sort(key=lambda row: str(row["profile"]))
    return rows


def _sweep_window(root: Path | None = None) -> dict[str, Any]:
    base = root if root is not None else results_root()
    rows = sweep_rows(base)
    stamps = [_stamp(base / str(row["directory"])) for row in rows]
    start = min(stamps) if stamps else 0
    end = max(stamps) if stamps else 0
    inside = [directory.name for directory in _measured_directories(base) if start <= _stamp(directory) <= end]
    selected = sorted(str(row["directory"]) for row in rows)
    return {
        "complete": sorted(inside) == selected,
        "window_start_stamp": start,
        "window_end_stamp": end,
        "window_start_utc": _stamp_to_utc(start),
        "window_end_utc": _stamp_to_utc(end),
        "selection": "the newest recorded result of every profile that has a recorded result",
        "directories": selected,
        "directories_in_run_order": sorted(
            (str(row["directory"]) for row in rows),
            key=lambda name: _stamp(base / name),
        ),
        "profiles": sorted(str(row["profile"]) for row in rows),
        "recorded_results_in_window": sorted(inside),
    }


def _limit_row(rows: list[dict[str, Any]]) -> dict[str, Any] | None:
    clean = [
        row
        for row in rows
        if row["business_failure"] == 0
        and row["invariant_violations"] == 0
        and row["business_success"] > 0
    ]
    if not clean:
        return None
    return max(
        clean,
        key=lambda row: (row["delivered_requests_per_second"], row["delivered_iterations_per_second"]),
    )


def _breaking_rows(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    return [row for row in rows if row["business_failure"] > 0 or row["invariant_violations"] > 0]


def _commits_after(repository: Path, paths: tuple[str, ...], after_epoch: int) -> list[dict[str, Any]]:
    command = [
        "git",
        "-C",
        str(repository),
        "log",
        "--format=%H%x09%ad%x09%s",
        "--date=iso",
        "--",
    ] + list(paths)
    try:
        result = subprocess.run(command, capture_output=True, text=True, check=False, timeout=60)
    except (OSError, subprocess.TimeoutExpired):
        return []
    commits = []
    for line in result.stdout.split("\n"):
        if not line.strip():
            continue
        parts = line.split("\t")
        if len(parts) < 3:
            continue
        if _iso_to_epoch(parts[1]) > after_epoch:
            commits.append({"commit": parts[0], "date": parts[1], "subject": parts[2]})
    return commits


def _binding_reason(
    missing: list[str],
    harness_commits: list[dict[str, Any]],
    service_commits: list[dict[str, Any]],
) -> str:
    parts = []
    if missing:
        parts.append(
            "the recorded results do not hold the fields the current harness records ("
            + ", ".join(missing)
            + "), so they were produced by a harness that did not plan the arrival rate in iterations per second"
        )
    if harness_commits:
        parts.append(
            "the load harness and the load generator changed after the measurement in "
            + ", ".join(str(commit["commit"])[:12] for commit in harness_commits)
        )
    if service_commits:
        parts.append(
            "the services changed after the measurement in "
            + ", ".join(str(commit["commit"])[:12] for commit in service_commits)
        )
    return "; ".join(parts)


def _binding(root: Path | None = None) -> dict[str, Any]:
    base = root if root is not None else results_root()
    window = _sweep_window(base)
    repository = config.repository_root()
    after = int(window.get("window_end_stamp") or 0)
    harness_commits = _commits_after(repository, HARNESS_PATHS, after)
    service_commits = _commits_after(repository, SERVICE_PATHS, after)
    rows = sweep_rows(base)
    missing = sorted({name for row in rows for name in row["current_harness_fields_missing"]})
    return {
        "binds_to_current_source": not harness_commits and not service_commits and not missing,
        "measurement_window_utc": str(window.get("window_end_utc")),
        "harness_commits_after_the_measurement": harness_commits,
        "service_commits_after_the_measurement": service_commits,
        "current_harness_fields_missing_from_the_recorded_results": missing,
        "reason": _binding_reason(missing, harness_commits, service_commits),
    }


def derive_capacity_limit(root: Path | None = None) -> dict[str, Any]:
    base = root if root is not None else results_root()
    rows = sweep_rows(base)
    window = _sweep_window(base)
    limit = _limit_row(rows)
    breaking = _breaking_rows(rows)
    highest_broken = None
    if breaking:
        highest_broken = max(breaking, key=lambda row: row["delivered_requests_per_second"])
    document: dict[str, Any] = {
        "recorded_at": utc_now(),
        "measured": limit is not None,
        "rule": CAPACITY_RULE,
        "run_id": config.run_identifier(),
        "source_directory": _relative(base),
        "last_complete_run": window,
        "profiles": rows,
        "stops_holding_at": {
            "highest_broken_profile": str(highest_broken["profile"]) if highest_broken else "",
            "highest_broken_requests_per_second": highest_broken["delivered_requests_per_second"]
            if highest_broken
            else None,
            "highest_broken_iterations_per_second": highest_broken["delivered_iterations_per_second"]
            if highest_broken
            else None,
            "profiles": breaking,
        },
        "binding": _binding(base),
    }
    if limit is not None:
        document["capacity_limit"] = {
            "profile": limit["profile"],
            "scenario": limit["scenario"],
            "status": limit["status"],
            "directory": limit["directory"],
            "result_file": limit["result_file"],
            "requests_per_second": limit["delivered_requests_per_second"],
            "iterations_per_second": limit["delivered_iterations_per_second"],
            "planned_peak_iterations_per_second": limit["planned_peak_iterations_per_second"],
            "planned_peak_requests_per_second": limit["planned_peak_requests_per_second"],
            "recorded_rate_ratio": limit["recorded_rate_ratio"],
            "business_success": limit["business_success"],
            "business_failure": limit["business_failure"],
            "business_refusal": limit["business_refusal"],
            "shed_requests": limit["shed_requests"],
            "invariant_violations": limit["invariant_violations"],
            "invariant_status": limit["invariant_status"],
        }
    else:
        document["capacity_limit"] = None
    return document


def read_recorded_capacity_limit(state_path: Path | None = None) -> dict[str, Any]:
    state = read_json(state_path or capacity_state_path(), {}) or {}
    recorded = state.get(CAPACITY_STATE_KEY)
    if isinstance(recorded, dict) and recorded:
        return recorded
    return {}


def record_capacity_limit(state_path: Path | None = None, root: Path | None = None) -> dict[str, Any]:
    path = state_path or capacity_state_path()
    state = read_json(path, {}) or {}
    document = derive_capacity_limit(root)
    state[CAPACITY_STATE_KEY] = document
    write_json(path, state)
    return document


def capacity_limit_document(state_path: Path | None = None, root: Path | None = None) -> dict[str, Any]:
    recorded = read_recorded_capacity_limit(state_path)
    if recorded:
        document = dict(recorded)
        document["source"] = "recorded state " + _relative(state_path or capacity_state_path())
        return document
    document = record_capacity_limit(state_path, root)
    document["source"] = (
        "derived from the recorded run results and recorded into "
        + _relative(state_path or capacity_state_path())
    )
    return document


def _run_directory(row: dict[str, Any]) -> str:
    return str(Path(str(row.get("result_file", ""))).parent)


def _rate_cell(value: Any) -> str:
    if value is None:
        return "not recorded"
    return format(float(value), ".4f")


def render_capacity_report(document: dict[str, Any]) -> str:
    limit = document.get("capacity_limit") or {}
    window = document.get("last_complete_run") or {}
    stops = document.get("stops_holding_at") or {}
    binding = document.get("binding") or {}
    rows = list(document.get("profiles") or [])
    breaking = list(stops.get("profiles") or [])
    lines: list[str] = []
    add = lines.append
    add("# Measured capacity limit of the platform")
    add("")
    add("- Run: `" + str(document.get("run_id", "")) + "`")
    add("- Recorded at: " + str(document.get("recorded_at", "")))
    add("- Rule: " + str(document.get("rule", "")) + ".")
    add("- Every number below is read from the recorded results in `"
        + str(document.get("source_directory", "")) + "` and the table names the file it was read from.")
    add("")
    add("## 1. The limit")
    add("")
    if not limit:
        add("No profile of the last complete measured run satisfied the rule, so no limit is recorded.")
        add("")
        return "\n".join(lines) + "\n"
    add("**" + _rate_cell(limit.get("requests_per_second")) + " requests per second, which is "
        + _rate_cell(limit.get("iterations_per_second")) + " iterations per second.**")
    add("")
    add("- Profile that sustained it: `" + str(limit.get("profile", "")) + "`, scenario `"
        + str(limit.get("scenario", "")) + "`, recorded status `" + str(limit.get("status", "")) + "`.")
    add("- Recorded result: `" + str(limit.get("result_file", "")) + "`.")
    add("- Successful business outcomes: " + str(limit.get("business_success", 0)) + ".")
    add("- Business failures: " + str(limit.get("business_failure", 0)) + ".")
    add("- Business refusals: " + str(limit.get("business_refusal", 0)) + ".")
    add("- Requests the gateway shed: " + str(limit.get("shed_requests", 0)) + ".")
    add("- Invariant violations: " + str(limit.get("invariant_violations", 0))
        + ", invariant status `" + str(limit.get("invariant_status", "")) + "`.")
    add("- Planned peak offered to the load generator: "
        + _rate_cell(limit.get("planned_peak_iterations_per_second")) + " iterations per second, which is "
        + _rate_cell(limit.get("planned_peak_requests_per_second"))
        + " requests per second at the requests per iteration the harness declares for the scenario.")
    add("- Delivered: " + _rate_cell(limit.get("iterations_per_second")) + " iterations per second and "
        + _rate_cell(limit.get("requests_per_second")) + " requests per second.")
    add("- Recorded ratio: " + str(limit.get("recorded_rate_ratio", "not recorded")) + ".")
    add("")
    add("## 2. The last complete measured run")
    add("")
    add("- Selection: " + str(window.get("selection", "")) + ".")
    add("- Window: " + str(window.get("window_start_utc", "")) + " to " + str(window.get("window_end_utc", ""))
        + " (directory stamps " + str(window.get("window_start_stamp", "")) + " to "
        + str(window.get("window_end_stamp", "")) + ").")
    add("- The window holds exactly the recorded results of one sweep: "
        + ("yes" if window.get("complete") else "no") + ".")
    add("- Profiles of the sweep: " + ", ".join(str(profile) for profile in window.get("profiles", [])) + ".")
    add("- Recorded results of the sweep:")
    for directory in window.get("directories_in_run_order", window.get("directories", [])):
        add("  - `" + str(directory) + "`")
    add("")
    add("## 3. The numbers of every profile")
    add("")
    add("| Profile | Planned peak iter/s | Planned peak req/s | Delivered iter/s | Delivered req/s | Recorded ratio | Source |")
    add("| --- | --- | --- | --- | --- | --- | --- |")
    for row in rows:
        add("| " + str(row["profile"]) + " | " + _rate_cell(row["planned_peak_iterations_per_second"]) + " | "
            + _rate_cell(row["planned_peak_requests_per_second"]) + " | "
            + _rate_cell(row["delivered_iterations_per_second"]) + " | "
            + _rate_cell(row["delivered_requests_per_second"]) + " | "
            + str(row["recorded_rate_ratio"]) + " | `" + str(row["result_file"]) + "`, planned peak from `"
            + _run_directory(row) + "/k6-stdout.log` |")
    add("")
    add("| Profile | Business success | Business failure | Refusals | Shed | Business failure rate | Shed rate | Invariant violations | Invariant status | Recorded status | Source |")
    add("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |")
    for row in rows:
        add("| " + str(row["profile"]) + " | " + str(row["business_success"]) + " | "
            + str(row["business_failure"]) + " | " + str(row["business_refusal"]) + " | "
            + str(row["shed_requests"]) + " | " + str(row["business_failure_rate"]) + " | "
            + str(row["shed_rate"]) + " | " + str(row["invariant_violations"]) + " | "
            + str(row["invariant_status"]) + " | " + str(row["status"]) + " | `"
            + str(row["result_file"]) + "` |")
    add("")
    add("The planned peak is the arrival rate the load generator was given, read from the header of `k6-stdout.log` "
        "of the same run. The request equivalent uses the requests per iteration the harness declares for the "
        "scenario in `production-audit/harness/loadtesting/profiles.py`.")
    add("")
    add("## 4. Where the platform stops holding")
    add("")
    if breaking:
        add("The platform held " + _rate_cell(limit.get("requests_per_second")) + " requests per second with no "
            "business failure and no invariant violation. The next rate it was measured at broke:")
        add("")
        for row in breaking:
            add("- `" + str(row["profile"]) + "` delivered " + _rate_cell(row["delivered_requests_per_second"])
                + " requests per second and recorded " + str(row["business_failure"]) + " business failures, "
                + str(row["business_refusal"]) + " business refusals, " + str(row["shed_requests"])
                + " requests shed by the gateway and " + str(row["invariant_violations"])
                + " invariant violations, out of " + str(row["business_success"]) + " successful business outcomes.")
            add("  - The load generator of that run dropped " + str(row["dropped_iterations"])
                + " iterations it had planned to start and exited with the code "
                + str(row["k6_exit_code"]) + ", so the platform never served the whole offered rate.")
        add("")
        add("The highest rate at which the platform broke is `" + str(stops.get("highest_broken_profile", "")) + "` at "
            + _rate_cell(stops.get("highest_broken_requests_per_second")) + " requests per second and "
            + _rate_cell(stops.get("highest_broken_iterations_per_second")) + " iterations per second.")
        add("")
        add("The platform therefore stops holding above " + _rate_cell(limit.get("requests_per_second"))
            + " requests per second.")
    else:
        add("No profile of the sweep broke.")
    add("")
    add("## 5. The unit of the recorded ratio")
    add("")
    add("- The recorded ratio is the delivered iteration rate divided by the request rate the profile declares. "
        "The harness that produced these results compared an iteration rate with a request target.")
    add("- The requests per iteration the harness declares for the scenario and the requests per iteration the "
        "recorded results show:")
    add("")
    add("| Profile | Declared requests per iteration | Observed requests per iteration |")
    add("| --- | --- | --- |")
    for row in rows:
        add("| " + str(row["profile"]) + " | " + str(row["requests_per_iteration_declared"]) + " | "
            + str(row["requests_per_iteration_observed"]) + " |")
    add("")
    add("- The observed value is `requests / iterations` of the recorded result. The `full` scenario issues one "
        "request more per iteration than the harness declares, so the request equivalent of a planned arrival rate "
        "of that scenario is higher than the table of section 3 states.")
    add("- The ratio of a profile that failed is not a statement about the capacity of the platform, because the "
        "failure was recorded against a business outcome that the harness of that run booked as a failure when the "
        "gateway shed the request.")
    add("")
    add("## 6. Does the recorded run still bind to the current source")
    add("")
    add("**No.**")
    add("")
    add("- Reason: " + str(binding.get("reason", "")) + ".")
    if binding.get("harness_commits_after_the_measurement"):
        add("- The load harness and the load generator changed after the measurement:")
        for commit in binding["harness_commits_after_the_measurement"]:
            add("  - `" + str(commit["commit"]) + "` " + str(commit["date"]) + " — " + str(commit["subject"]))
    if binding.get("service_commits_after_the_measurement"):
        add("- The services changed after the measurement:")
        for commit in binding["service_commits_after_the_measurement"]:
            add("  - `" + str(commit["commit"]) + "` " + str(commit["date"]) + " — " + str(commit["subject"]))
    add("- The recorded results do not hold the fields the current harness writes into every run: "
        + ", ".join(str(name) for name in binding.get("current_harness_fields_missing_from_the_recorded_results", []))
        + ".")
    add("- The load generator of the recorded run was given the request rate of the profile as an iteration rate, "
        "so it offered more traffic than the current harness plans for the same profile.")
    add("")
    add("## 7. The run that must be repeated")
    add("")
    add("The whole sweep of this run must be repeated with the harness at the current revision, because the "
        "measurement itself changed: the profiles are now planned in iterations per second, the planned rate is "
        "recorded beside the delivered rate, a run that delivers less than the accepted ratio of the planned rate "
        "fails, a request the gateway shed is booked as shedding and not as a business failure, and every virtual "
        "user gets its own customer.")
    add("")
    add("The runs to repeat are these six, in the order they were measured:")
    add("")
    for directory in window.get("directories_in_run_order", window.get("directories", [])):
        add("- `" + str(directory) + "`")
    add("")
    add("## 8. The files this document was derived from")
    add("")
    add("- `production-audit/harness/loadtesting/profiles.py` — the profile definitions and the requests per "
        "iteration of every scenario.")
    add("- `production-audit/harness/loadtesting/runner.py` — how the runner records the rates and the outcome.")
    add("- `production-audit/harness/loadtesting/invariants.py` — the eight invariants that are verified after "
        "every run.")
    add("- `production-audit/harness/loadtesting/capacity.py` — the derivation that produced this document.")
    add("- `production-audit/runs/run-20261118-a2b6e6fc/load-results/<run>/result.json` — the recorded outcome of "
        "every profile.")
    add("- `production-audit/runs/run-20261118-a2b6e6fc/load-results/<run>/k6-stdout.log` — the arrival plan the "
        "load generator was given.")
    add("- `production-audit/runs/run-20261118-a2b6e6fc/load-results/<run>/k6-summary.json` — the raw counters of "
        "the load generator.")
    add("")
    return "\n".join(lines) + "\n"


def write_capacity_report(path: Path | None = None, root: Path | None = None) -> Path:
    base = root if root is not None else results_root()
    target = path or (base / CAPACITY_REPORT_NAME)
    document = derive_capacity_limit(base)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(render_capacity_report(document), encoding="utf-8")
    return target
