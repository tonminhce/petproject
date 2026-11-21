from __future__ import annotations

import json
import re
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any

PROMETHEUS_URL = "http://127.0.0.1:9095"
TOKEN_PATTERN = re.compile(r"[a-zA-Z_][a-zA-Z0-9_]*")
LABEL_SECTION = re.compile(r"\{[^}]*\}")
GROUPING = re.compile(r"\b(?:by|without|on|ignoring|group_left|group_right)\s*\(([^)]*)\)")
NAME_MATCHER = re.compile(r"__name__\s*=~\s*\"([^\"]*)\"")
LABEL_NAME = re.compile(r"[a-zA-Z_][a-zA-Z0-9_]*")
PROMQL_FUNCTIONS = {
    "abs",
    "absent",
    "absent_over_time",
    "avg",
    "avg_over_time",
    "bucket_quantile",
    "changes",
    "clamp_max",
    "clamp_min",
    "count",
    "count_over_time",
    "day_of_month",
    "day_of_week",
    "day_of_year",
    "delta",
    "deriv",
    "histogram_quantile",
    "histogram_fraction",
    "holt_winters",
    "hour",
    "idelta",
    "increase",
    "irate",
    "label_join",
    "label_replace",
    "last_over_time",
    "ln",
    "log10",
    "log2",
    "max",
    "max_over_time",
    "min",
    "min_over_time",
    "minute",
    "month",
    "predict_linear",
    "quantile",
    "quantile_over_time",
    "rate",
    "resets",
    "round",
    "scalar",
    "sgn",
    "sort",
    "sort_desc",
    "stddev_over_time",
    "stdvar_over_time",
    "sum",
    "sum_over_time",
    "time",
    "timestamp",
    "vector",
    "year",
}
PROMQL_KEYWORDS = {
    "and",
    "bool",
    "by",
    "group_left",
    "group_right",
    "ignoring",
    "offset",
    "on",
    "or",
    "unless",
    "without",
}


def _strip_labels(expression: str) -> str:
    return LABEL_SECTION.sub(" ", expression)


def _strip_groupings(expression: str) -> str:
    return GROUPING.sub(" ", expression)


def referenced_metrics(expression: str) -> list[str]:
    candidates: set[str] = set()
    body = _strip_groupings(_strip_labels(expression))
    for token in TOKEN_PATTERN.findall(body):
        lowered = token.lower()
        if lowered in PROMQL_FUNCTIONS or lowered in PROMQL_KEYWORDS:
            continue
        if "_" not in token:
            continue
        candidates.add(token)
    return sorted(candidates)


def name_matchers(expression: str) -> list[str]:
    return sorted(set(NAME_MATCHER.findall(expression)))


def referenced_groupings(expression: str) -> list[str]:
    names: set[str] = set()
    for group in GROUPING.findall(expression):
        for candidate in LABEL_NAME.findall(group):
            lowered = candidate.lower()
            if lowered in PROMQL_FUNCTIONS or lowered in PROMQL_KEYWORDS:
                continue
            names.add(candidate)
    return sorted(names)


def exported_metric_names(url: str = PROMETHEUS_URL, timeout: int = 25) -> tuple[list[str] | None, str]:
    try:
        request = urllib.request.Request(url.rstrip("/") + "/api/v1/label/__name__/values")
        with urllib.request.urlopen(request, timeout=timeout) as response:
            payload = json.loads(response.read().decode("utf-8"))
    except (urllib.error.URLError, OSError, json.JSONDecodeError, ValueError) as error:
        return None, "prometheus is not reachable at " + url + ": " + str(error)
    names = payload.get("data")
    if not isinstance(names, list):
        return None, "prometheus returned an unexpected metric inventory"
    return sorted(str(name) for name in names), ""


def exported_label_names(url: str = PROMETHEUS_URL, timeout: int = 25) -> tuple[list[str] | None, str]:
    try:
        request = urllib.request.Request(url.rstrip("/") + "/api/v1/labels")
        with urllib.request.urlopen(request, timeout=timeout) as response:
            payload = json.loads(response.read().decode("utf-8"))
    except (urllib.error.URLError, OSError, json.JSONDecodeError, ValueError) as error:
        return None, "prometheus is not reachable at " + url + ": " + str(error)
    names = payload.get("data")
    if not isinstance(names, list):
        return None, "prometheus returned an unexpected label inventory"
    return sorted(str(name) for name in names), ""


def scraped_applications(url: str = PROMETHEUS_URL, timeout: int = 25) -> tuple[list[str] | None, str]:
    try:
        request = urllib.request.Request(url.rstrip("/") + "/api/v1/query?query=up")
        with urllib.request.urlopen(request, timeout=timeout) as response:
            payload = json.loads(response.read().decode("utf-8"))
    except (urllib.error.URLError, OSError, json.JSONDecodeError, ValueError) as error:
        return None, "prometheus is not reachable at " + url + ": " + str(error)
    results = payload.get("data", {}).get("result")
    if not isinstance(results, list):
        return None, "prometheus returned an unexpected target inventory"
    applications = set()
    for entry in results:
        labels = entry.get("metric", {})
        application = str(labels.get("application", "")).strip()
        if application:
            applications.add(application)
            continue
        instance = str(labels.get("instance", "")).strip()
        if instance:
            applications.add(instance.split(":")[0])
    return sorted(applications), ""


def _matcher_matches(matcher: str, known: set[str]) -> bool:
    try:
        pattern = re.compile(matcher)
    except re.error:
        return False
    return any(pattern.fullmatch(name) for name in known)


def rule_metric_audit(rules_path: Any, url: str = PROMETHEUS_URL) -> dict[str, Any]:
    from .. import yamlmini

    try:
        source = Path(rules_path).read_text(encoding="utf-8")
        document = yamlmini.loads(source)
    except (OSError, yamlmini.YamlError) as error:
        return {"status": "BLOCKED", "reason": "the runtime alert rules could not be read: " + str(error)}

    exported, reason = exported_metric_names(url)
    if exported is None:
        return {"status": "BLOCKED", "reason": reason}
    labels, label_reason = exported_label_names(url)
    if labels is None:
        return {"status": "BLOCKED", "reason": label_reason}
    applications, application_reason = scraped_applications(url)
    if applications is None:
        return {"status": "BLOCKED", "reason": application_reason}

    known = set(exported)
    known_labels = set(labels)
    audit: list[dict[str, Any]] = []
    for group in document.get("groups") or []:
        for rule in group.get("rules") or []:
            name = str(rule.get("alert", ""))
            expression = str(rule.get("expr", ""))
            unknown = [metric for metric in referenced_metrics(expression) if metric not in known]
            matchers = name_matchers(expression)
            unmatched = [matcher for matcher in matchers if not _matcher_matches(matcher, known)]
            groupings = referenced_groupings(expression)
            unknown_labels = [label for label in groupings if label not in known_labels]
            audit.append(
                {
                    "alert": name,
                    "referenced": referenced_metrics(expression),
                    "name_matchers": matchers,
                    "unknown": unknown + unmatched,
                    "groupings": groupings,
                    "unknown_groupings": unknown_labels,
                }
            )
    failing = [entry for entry in audit if entry["unknown"] or entry["unknown_groupings"]]
    return {
        "status": "FAIL" if failing else "PASS",
        "exported_metric_count": len(exported),
        "exported_label_count": len(known_labels),
        "scraped_applications": applications,
        "rules": audit,
        "rules_with_unknown_metrics": [entry["alert"] for entry in audit if entry["unknown"]],
        "rules_with_unknown_groupings": [entry["alert"] for entry in audit if entry["unknown_groupings"]],
    }
