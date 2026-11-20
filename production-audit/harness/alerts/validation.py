from __future__ import annotations

import re
import subprocess
from pathlib import Path
from typing import Any

from .. import config
from .. import yamlmini
from ..telegram.client import BOT_TOKEN_PATTERN, credential_status
from .metrics import rule_metric_audit
from .render import (
    alertmanager_rules_path,
    alertmanager_template_path,
    placeholders_present,
)

REQUIRED_ALERT_CATEGORIES = ("application", "database")
TELEGRAM_TOKEN_LITERAL = re.compile(r"TELEGRAM_BOT_TOKEN\s*[=:]\s*([0-9]{6,12}:[A-Za-z0-9_-]{20,})")
ANY_BOT_TOKEN = re.compile(r"\b[0-9]{6,12}:[A-Za-z0-9_-]{20,}\b")
ANY_STRIPE_KEY = re.compile(r"\b[srp]k_(live|test)_[A-Za-z0-9]{10,}\b")
LIVE_STRIPE_KEY = re.compile(r"sk_live_[A-Za-z0-9]{10,}")
TEMPLATE_SUFFIXES = (".yml", ".yaml", ".env", ".example", ".sh", ".py", ".java", ".properties")


def _check(identifier: str, name: str, status: str, detail: str, evidence: list[str]) -> dict[str, Any]:
    return {"id": identifier, "name": name, "status": status, "detail": detail, "evidence": evidence}


def _tracked_files() -> list[str]:
    try:
        result = subprocess.run(
            ["git", "ls-files", "--cached", "--others", "--exclude-standard"],
            cwd=str(config.repository_root()),
            capture_output=True,
            text=True,
            check=False,
        )
    except OSError:
        return []
    if result.returncode != 0:
        return []
    return [line for line in result.stdout.split("\n") if line.strip()]


def _scan_for_secrets() -> list[dict[str, str]]:
    findings: list[dict[str, str]] = []
    repository = config.repository_root()
    for relative in _tracked_files():
        path = repository / relative
        if not path.is_file() or path.stat().st_size > 2_000_000:
            continue
        if not relative.endswith(TEMPLATE_SUFFIXES) and "/workflows/" not in relative:
            continue
        if Path(relative).name.startswith(".env") and Path(relative).name != ".env.example":
            continue
        if relative.startswith("production-audit/harness/tests/"):
            continue
        try:
            content = path.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        for match in TELEGRAM_TOKEN_LITERAL.finditer(content):
            findings.append({"file": relative, "kind": "telegram-bot-token", "detail": match.group(0)[:24] + "..."})
        for match in LIVE_STRIPE_KEY.finditer(content):
            findings.append({"file": relative, "kind": "stripe-live-key", "detail": match.group(0)[:12] + "..."})
        for match in ANY_BOT_TOKEN.finditer(content):
            if "TELEGRAM_BOT_TOKEN" not in content[max(0, match.start() - 40):match.start()]:
                findings.append({"file": relative, "kind": "unlabelled-bot-token", "detail": match.group(0)[:8] + "..."})
        for match in ANY_STRIPE_KEY.finditer(content):
            if not any(token in content for token in ("STRIPE_SECRET_KEY", "STRIPE_API_KEY", "sk_test_placeholder")):
                findings.append({"file": relative, "kind": "stripe-key-literal", "detail": match.group(0)[:8] + "..."})
    return findings


def _validate_template() -> tuple[dict[str, Any], list[str]]:
    evidence: list[str] = []
    path = alertmanager_template_path()
    if not path.exists():
        return _check("ALT-01", "ALERTMANAGER_TEMPLATE", "FAIL", "template file is missing", []), evidence
    text = path.read_text(encoding="utf-8")
    try:
        document = yamlmini.loads(text)
    except yamlmini.YamlError as error:
        return _check("ALT-01", "ALERTMANAGER_TEMPLATE", "FAIL", "template is not valid YAML: " + str(error), []), evidence
    receivers = document.get("receivers") or []
    telegram_receivers = [
        receiver for receiver in receivers if receiver.get("telegram_configs")
    ]
    route = document.get("route") or {}
    runtime_routes = [
        entry
        for entry in route.get("routes") or []
        if any("severity" in str(matcher) for matcher in entry.get("matchers") or [])
    ]
    routed_receivers = {str(entry.get("receiver")) for entry in runtime_routes}
    problems: list[str] = []
    if not telegram_receivers:
        problems.append("no receiver defines telegram_configs")
    if not runtime_routes:
        problems.append("no route matches the runtime alert severities")
    for severity in ("critical", "warning"):
        if not any(severity in " ".join(str(item) for item in entry.get("matchers") or [])
                   for entry in runtime_routes):
            problems.append("no route matches the " + severity + " severity")
    for receiver in telegram_receivers:
        if str(receiver.get("name")) not in routed_receivers:
            problems.append("the telegram receiver " + str(receiver.get("name")) + " is not reachable by any route")
    if not route.get("group_by"):
        problems.append("the root route does not group alerts")
    if not route.get("repeat_interval"):
        problems.append("the root route does not set a repeat interval")
    if not document.get("inhibit_rules"):
        problems.append("no inhibition rules are configured")
    if not placeholders_present(text):
        problems.append("the committed template contains no credential placeholders")
    for receiver in telegram_receivers:
        for telegram in receiver.get("telegram_configs") or []:
            token = str(telegram.get("bot_token", ""))
            if BOT_TOKEN_PATTERN.match(token):
                problems.append("a committed bot token is present in receiver " + str(receiver.get("name")))
    evidence.append("receivers: " + ", ".join(str(item.get("name")) for item in receivers))
    evidence.append("telegram receivers: " + str(len(telegram_receivers)))
    evidence.append("runtime routes: " + str(len(runtime_routes)))
    evidence.append("routed receivers: " + ", ".join(sorted(routed_receivers)))
    evidence.append("inhibit rules: " + str(len(document.get("inhibit_rules") or [])))
    evidence.append("group_by: " + ", ".join(str(item) for item in (route.get("group_by") or [])))
    if problems:
        return _check("ALT-01", "ALERTMANAGER_TEMPLATE", "FAIL", "; ".join(problems), evidence), evidence
    return (
        _check("ALT-01", "ALERTMANAGER_TEMPLATE", "PASS", "alertmanager template is complete and contains no committed credential", evidence),
        evidence,
    )


def _validate_rules() -> tuple[dict[str, Any], list[str]]:
    evidence: list[str] = []
    path = alertmanager_rules_path()
    if not path.exists():
        return _check("ALT-02", "RUNTIME_ALERT_RULES", "FAIL", "runtime alert rules file is missing", []), evidence
    try:
        document = yamlmini.loads(path.read_text(encoding="utf-8"))
    except yamlmini.YamlError as error:
        return _check("ALT-02", "RUNTIME_ALERT_RULES", "FAIL", "runtime alert rules are not valid YAML: " + str(error), []), evidence
    categories: set[str] = set()
    rule_names: list[str] = []
    problems: list[str] = []
    for group in document.get("groups") or []:
        for rule in group.get("rules") or []:
            name = str(rule.get("alert", ""))
            rule_names.append(name)
            labels = rule.get("labels") or {}
            annotations = rule.get("annotations") or {}
            category = str(labels.get("category", ""))
            categories.add(category)
            if labels.get("severity") not in {"critical", "warning"}:
                problems.append("rule " + name + " has no severity label")
            if not annotations.get("summary"):
                problems.append("rule " + name + " has no summary annotation")
            if not annotations.get("metric"):
                problems.append("rule " + name + " has no metric annotation")
            if not annotations.get("runbook"):
                problems.append("rule " + name + " has no runbook annotation")
    missing = [item for item in REQUIRED_ALERT_CATEGORIES if item not in categories]
    if missing:
        problems.append("missing alert categories: " + ", ".join(missing))
    evidence.append("rules: " + str(len(rule_names)))
    evidence.append("categories: " + ", ".join(sorted(categories)))
    audit = rule_metric_audit(alertmanager_rules_path())
    if audit.get("status") == "FAIL":
        for entry in audit.get("rules", []):
            if entry.get("unknown"):
                problems.append(
                    "rule " + str(entry.get("alert")) + " references metrics that the platform does not publish: "
                    + ", ".join(entry["unknown"])
                )
            if entry.get("unknown_groupings"):
                problems.append(
                    "rule " + str(entry.get("alert")) + " groups by labels the platform does not publish: "
                    + ", ".join(entry["unknown_groupings"])
                )
        evidence.append("rules with unknown metrics: " + ", ".join(audit.get("rules_with_unknown_metrics", [])))
        evidence.append(
            "rules with unknown grouping labels: " + ", ".join(audit.get("rules_with_unknown_groupings", []))
        )
    elif audit.get("status") == "BLOCKED":
        problems.append(
            "the metric audit could not run, so no rule was proven to reference a published metric: "
            + str(audit.get("reason", ""))
        )
        evidence.append("metric audit: " + str(audit.get("reason", "")))
    else:
        evidence.append(
            "metric audit: " + str(audit.get("exported_metric_count")) + " published metrics and "
            + str(audit.get("exported_label_count")) + " published labels checked"
        )
    if problems:
        return _check("ALT-02", "RUNTIME_ALERT_RULES", "FAIL", "; ".join(problems), evidence), evidence
    return (
        _check("ALT-02", "RUNTIME_ALERT_RULES", "PASS", "runtime alert rules cover every required incident category and reference only published metrics", evidence),
        evidence,
    )


def _validate_prometheus() -> tuple[dict[str, Any], list[str]]:
    evidence: list[str] = []
    path = config.repository_root() / "config" / "observability" / "prometheus.yml"
    if not path.exists():
        return _check("ALT-03", "PROMETHEUS_ROUTING", "FAIL", "prometheus configuration is missing", []), evidence
    text = path.read_text(encoding="utf-8")
    try:
        document = yamlmini.loads(text)
    except yamlmini.YamlError as error:
        return _check("ALT-03", "PROMETHEUS_ROUTING", "FAIL", "prometheus configuration is not valid YAML: " + str(error), []), evidence
    problems: list[str] = []
    if not document.get("rule_files"):
        problems.append("prometheus loads no rule files")
    alertmanagers = (document.get("alerting") or {}).get("alertmanagers") or []
    targets: list[str] = []
    for manager in alertmanagers:
        for static in manager.get("static_configs") or []:
            targets.extend(str(item) for item in static.get("targets") or [])
    if not targets:
        problems.append("prometheus has no alertmanager target")
    evidence.append("rule_files: " + ", ".join(str(item) for item in document.get("rule_files") or []))
    evidence.append("alertmanager targets: " + ", ".join(targets))
    if problems:
        return _check("ALT-03", "PROMETHEUS_ROUTING", "FAIL", "; ".join(problems), evidence), evidence
    return (
        _check("ALT-03", "PROMETHEUS_ROUTING", "PASS", "prometheus evaluates rules and forwards alerts to alertmanager", evidence),
        evidence,
    )


def _validate_compose() -> tuple[dict[str, Any], list[str]]:
    evidence: list[str] = []
    path = config.repository_root() / "docker-compose.yml"
    if not path.exists():
        return _check("ALT-04", "COMPOSE_ALERTMANAGER", "FAIL", "docker-compose.yml is missing", []), evidence
    text = path.read_text(encoding="utf-8")
    problems: list[str] = []
    if "\n  alertmanager:" not in text:
        problems.append("docker-compose.yml does not define an alertmanager service")
    if "alertmanager" not in text:
        problems.append("docker-compose.yml does not reference alertmanager")
    if "rules:/etc/prometheus/rules" not in text:
        problems.append("prometheus does not mount the runtime alert rules")
    evidence.append("alertmanager service present: " + str("\n  alertmanager:" in text))
    evidence.append("rules mounted by prometheus: " + str("rules:/etc/prometheus/rules" in text))
    if problems:
        return _check("ALT-04", "COMPOSE_ALERTMANAGER", "FAIL", "; ".join(problems), evidence), evidence
    return (
        _check("ALT-04", "COMPOSE_ALERTMANAGER", "PASS", "the compose stack runs alertmanager and evaluates the runtime rules", evidence),
        evidence,
    )


def _validate_ci_workflow() -> tuple[dict[str, Any], list[str]]:
    evidence: list[str] = []
    path = config.repository_root() / ".github" / "workflows" / "ci.yml"
    if not path.exists():
        return _check("ALT-05", "CI_NOTIFICATION", "FAIL", "the CI workflow is missing", []), evidence
    text = path.read_text(encoding="utf-8")
    problems: list[str] = []
    if "notify-telegram" not in text:
        problems.append("the CI workflow does not run the telegram notification step")
    if "failure()" not in text:
        problems.append("the CI workflow does not notify on failure")
    if "secrets.TELEGRAM_BOT_TOKEN" not in text:
        problems.append("the CI workflow does not read the bot token from repository secrets")
    if "secrets.TELEGRAM_CHAT_ID" not in text:
        problems.append("the CI workflow does not read the chat identifier from repository secrets")
    evidence.append("notification step present: " + str("notify-telegram" in text))
    evidence.append("failure condition present: " + str("failure()" in text))
    evidence.append("secrets referenced: " + str("secrets.TELEGRAM_BOT_TOKEN" in text))
    if problems:
        return _check("ALT-05", "CI_NOTIFICATION", "FAIL", "; ".join(problems), evidence), evidence
    return (
        _check("ALT-05", "CI_NOTIFICATION", "PASS", "CI failures are reported through the telegram notification step", evidence),
        evidence,
    )


def _validate_secret_hygiene() -> tuple[dict[str, Any], list[str]]:
    findings = _scan_for_secrets()
    evidence = ["tracked files scanned: " + str(len(_tracked_files())),
               "scope: committed files that can carry configuration; the synthetic fixtures of the auditor own test suite are excluded"]
    if findings:
        evidence.extend(
            item["file"] + " contains " + item["kind"] for item in findings[:10]
        )
        return (
            _check("ALT-06", "SECRET_HYGIENE", "FAIL", str(len(findings)) + " committed credential literals detected", evidence),
            evidence,
        )
    return (
        _check("ALT-06", "SECRET_HYGIENE", "PASS", "no committed telegram token or live stripe key was found", evidence),
        evidence,
    )


def _validate_credentials() -> tuple[dict[str, Any], list[str]]:
    status = credential_status()
    evidence = [
        "alerts enabled: " + str(status["enabled"]),
        "bot token present: " + str(status["bot_token_present"]),
        "chat identifier present: " + str(status["chat_id_present"]),
        "ci chat identifier present: " + str(status["ci_chat_id_present"]),
    ]
    if status["ready"]:
        return (
            _check("ALT-07", "DELIVERY_CREDENTIALS", "PASS", "telegram delivery credentials are present and valid", evidence),
            evidence,
        )
    return (
        _check(
            "ALT-07",
            "DELIVERY_CREDENTIALS",
            "BLOCKED",
            "telegram delivery credentials are not available in this environment",
            evidence,
        ),
        evidence,
    )


def validate_alerting() -> dict[str, Any]:
    checks: list[dict[str, Any]] = []
    for validator in (
        _validate_template,
        _validate_rules,
        _validate_prometheus,
        _validate_compose,
        _validate_ci_workflow,
        _validate_secret_hygiene,
        _validate_credentials,
    ):
        check, _ = validator()
        checks.append(check)
    failed = [item["id"] for item in checks if item["status"] == "FAIL"]
    blocked = [item["id"] for item in checks if item["status"] == "BLOCKED"]
    if failed:
        status = "FAIL"
    elif blocked:
        status = "BLOCKED"
    else:
        status = "PASS"
    return {
        "status": status,
        "failed_checks": failed,
        "blocked_checks": blocked,
        "checks": checks,
    }
