from __future__ import annotations

import os
from pathlib import Path


def repository_root() -> Path:
    override = os.environ.get("HARNESS_REPO_ROOT")
    if override:
        return Path(override).resolve()
    return Path(__file__).resolve().parents[2]


def audit_root() -> Path:
    override = os.environ.get("HARNESS_AUDIT_ROOT")
    if override:
        return Path(override).resolve()
    return repository_root() / "production-audit"


def control_root() -> Path:
    return audit_root() / "control"


def state_root() -> Path:
    return audit_root() / "state"


def runs_root() -> Path:
    return audit_root() / "runs"


def reports_root() -> Path:
    return audit_root() / "reports"


def architecture_root() -> Path:
    return audit_root() / "architecture"


def config_root() -> Path:
    return audit_root() / "config"


def source_manifest_path() -> Path:
    return control_root() / "source-manifest.json"


def source_coverage_path() -> Path:
    return control_root() / "source-coverage.json"


def architecture_state_path() -> Path:
    return control_root() / "architecture-state.json"


def execution_state_path() -> Path:
    return control_root() / "execution-state.json"


def tasks_path() -> Path:
    return state_root() / "tasks.json"


def findings_path() -> Path:
    return state_root() / "findings.json"


def dependencies_path() -> Path:
    return state_root() / "dependencies.json"


def verification_path() -> Path:
    return state_root() / "verification.json"


def git_deliveries_path() -> Path:
    return state_root() / "git-deliveries.json"


def research_path() -> Path:
    return state_root() / "research.json"


def events_path() -> Path:
    return state_root() / "events.jsonl"


def comment_policy_path() -> Path:
    return config_root() / "comment-policy.json"


def secret_scan_exceptions_path() -> Path:
    return config_root() / "secret-scan-exceptions.json"


def run_identifier() -> str:
    override = os.environ.get("HARNESS_RUN_ID")
    if override:
        return override
    existing = sorted(p for p in runs_root().glob("run-*") if p.is_dir()) if runs_root().exists() else []
    if existing:
        return existing[-1].name
    return "run-default"


def run_root(run_id: str | None = None) -> Path:
    return runs_root() / (run_id or run_identifier())


SERVICE_MODULES = (
    "auth-service",
    "product-service",
    "order-service",
    "payment-service",
    "inventory-service",
    "shipping-service",
    "notification-service",
    "rating-service",
    "search-service",
    "promotion-service",
    "tax-service",
    "favourite-service",
    "media-service",
    "gateway-service",
)

SHARED_MODULES = (
    "common-core",
    "common-kafka",
    "common-keycloak",
    "common-logging",
    "common-patterns",
    "common-security",
    "common-spring",
    "common-storage",
)

PROTECTED_BRANCH_NAMES = (
    "main",
    "master",
    "production",
    "prod",
    "release",
    "develop",
    "staging",
)

EXIT_OK = 0
EXIT_GATE_FAILED = 1
EXIT_BLOCKED = 2
EXIT_USAGE = 3
